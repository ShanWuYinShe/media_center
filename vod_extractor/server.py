import ipaddress
import json
import os
import selectors
import signal
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8790"))
EXTRACTOR_BIN = os.environ.get("VOD_EXTRACTOR_BIN", "yt-dlp")
TIMEOUT_SECONDS = max(1, int(os.environ.get("VOD_EXTRACTOR_TIMEOUT_MS", "30000")) // 1000)
MAX_OUTPUT_BYTES = int(os.environ.get("VOD_EXTRACTOR_MAX_OUTPUT_BYTES", str(20 * 1024 * 1024)))
MAX_REQUEST_BYTES = 16 * 1024
MAX_ERROR_BYTES = 64 * 1024
MAX_CONCURRENT = max(1, int(os.environ.get("VOD_EXTRACTOR_MAX_CONCURRENT", "2")))
DEFAULT_COOKIES_FILE = "/config/youtube-cookies.txt"
COOKIES_FILE_ENV = os.environ.get("VOD_EXTRACTOR_COOKIES_FILE", "").strip()
COOKIES_FILE = COOKIES_FILE_ENV or DEFAULT_COOKIES_FILE
COOKIES_FILE_EXPLICIT = bool(COOKIES_FILE_ENV)
EXTRACTOR_ARGS = os.environ.get("VOD_EXTRACTOR_EXTRACTOR_ARGS", "").strip()
EXTRACTOR_SEMAPHORE = threading.BoundedSemaphore(MAX_CONCURRENT)
# 并发连接数上限:ThreadingHTTPServer 每连接一线程,无上限时大量并发连接
# (即使有 30s socket 超时)也会线性堆线程。超限的新连接直接关闭——yt-dlp
# 并发只有 2,正常流量远到不了这个值,纯属资源防线。
MAX_CONNECTIONS = max(4, int(os.environ.get("VOD_EXTRACTOR_MAX_CONNECTIONS", "32")))
MAX_QUERY_BYTES = 128
DEFAULT_SEARCH_LIMIT = 20
MAX_SEARCH_LIMIT = 30

# /extract 目标 host 白名单:本服务当前只服务 youtube provider(media_server 只会
# 传入 youtube watch URL),白名单把 SSRF 暴露面(含重定向后的落点之外)压到
# "youtube 域"一个出口。可用 VOD_EXTRACTOR_ALLOWED_EXTRACT_HOSTS(逗号分隔)
# 整体覆盖;留空用内置默认。
DEFAULT_ALLOWED_EXTRACT_HOSTS = (
    "youtube.com",
    "www.youtube.com",
    "youtu.be",
    "m.youtube.com",
    "music.youtube.com",
)
ALLOWED_EXTRACT_HOSTS = tuple(
    host.strip().lower().lstrip(".")
    for host in os.environ.get("VOD_EXTRACTOR_ALLOWED_EXTRACT_HOSTS", "").split(",")
    if host.strip()
) or DEFAULT_ALLOWED_EXTRACT_HOSTS

# 版本自报:GIT_VERSION 由镜像构建期写入 /app/version(仓库 git describe);
# yt-dlp 版本启动时取一次——YouTube 风控对抗依赖 yt-dlp 版本,排障必查。
try:
    with open("/app/version", encoding="utf-8") as f:
        SERVICE_VERSION = f.read().strip() or "dev"
except OSError:
    SERVICE_VERSION = "dev"


def _ytdlp_version():
    try:
        out = subprocess.run(
            [EXTRACTOR_BIN, "--version"], capture_output=True, text=True, timeout=10
        )
        return out.stdout.strip() or "unknown"
    except Exception as error:  # 版本探测失败不影响服务
        log_internal(f"yt-dlp --version failed: {error}")
        return "unknown"


class ExtractorBusyError(RuntimeError):
    pass


class ExtractorConfigError(RuntimeError):
    """服务端自身配置错误(如显式 cookies 文件缺失/不可读)。

    必须与请求方错误(ValueError→400)区分:配置错误返回 503,
    否则排障时会把服务端问题误判为客户端请求问题。
    """


class Handler(BaseHTTPRequestHandler):
    server_version = "vod-extractor/1.0"
    # socket 级读超时:慢速连接(慢发请求头/体)不能无限占用线程,
    # 内网暴露也保留这道防线。
    timeout = 30

    def do_GET(self):
        self.started_at = time.monotonic()
        if self.path == "/health":
            self.send_json(
                200,
                {
                    "ok": True,
                    "version": SERVICE_VERSION,
                    "ytdlp": YTDLP_VERSION,
                    # cookies 健康信号:cookies 过期/缺失会让 YouTube 解析与搜索
                    # 全部失败且不产生主动告警,外部监控靠此字段告警(见 README)。
                    "cookies": cookies_health(),
                },
            )
            return
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        self.started_at = time.monotonic()
        if self.path not in {"/extract", "/search"}:
            self.send_json(404, {"error": "not found"})
            return

        try:
            payload = self.read_json_body()
            if self.path == "/extract":
                url = payload.get("url")
                if not isinstance(url, str) or not is_http_url(url):
                    self.send_json(400, {"error": "url must be an http(s) URL"})
                    return
                if not is_safe_target(url):
                    self.send_json(400, {"error": "url target is not allowed"})
                    return
                if not is_allowed_extract_host(urlparse(url).hostname):
                    self.send_json(400, {"error": "url host is not allowed"})
                    return
                extracted = extract_vod(url)
            else:
                query, limit = parse_search_payload(payload)
                extracted = search_youtube(query, limit)
        except TimeoutError:
            self.send_json(504, {"error": "extractor timed out"})
            return
        except ValueError as error:
            self.send_json(400, {"error": str(error)})
            return
        except ExtractorBusyError as error:
            self.send_json(503, {"error": str(error)})
            return
        except ExtractorConfigError as error:
            # 服务端配置问题(如 cookies 不可用)是 503,不是客户端的 400
            self.send_json(503, {"error": str(error)})
            return
        except RuntimeError as error:
            self.send_json(502, {"error": str(error)})
            return
        except Exception as error:  # 兜底:不让 handler 崩溃成空响应
            log_internal(f"unhandled error: {error}")
            self.send_json(500, {"error": "internal error"})
            return

        self.send_json(200, extracted)

    def read_json_body(self):
        length = int(self.headers.get("content-length", "0"))
        if length <= 0:
            raise ValueError("request body is required")
        if length > MAX_REQUEST_BYTES:
            raise ValueError("request body is too large")
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ValueError("request body must be JSON") from error
        if not isinstance(payload, dict):
            raise ValueError("request body must be a JSON object")
        return payload

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.log_json(status, payload)

    def log_json(self, status, payload):
        path = urlparse(self.path).path
        if path == "/health" and status < 400:
            return
        started_at = getattr(self, "started_at", time.monotonic())
        entry = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": "error" if status >= 500 else "warn" if status >= 400 else "info",
            "client": self.client_address[0],
            "method": self.command,
            "path": path,
            "status": status,
            "duration_ms": round((time.monotonic() - started_at) * 1000, 3),
        }
        error = payload.get("error") if isinstance(payload, dict) else None
        if error:
            entry["error"] = str(error)[:2000]
        print(json.dumps(entry, ensure_ascii=False), file=sys.stdout, flush=True)

    def log_request(self, code="-", size="-"):
        return

    def log_message(self, format, *args):
        return


def is_http_url(value):
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def is_safe_target(value):
    """SSRF 防护:拒绝解析到私有/环回/链路本地/保留网段的目标。

    yt-dlp 会以容器身份访问该 URL,若不限制,攻击者可让它打云元数据端点
    (169.254.169.254)、Docker 内网网关、同 compose 网络的 admin 接口。

    判定策略:
    - 字面 IP:直接校验,私网/环回/链路本地/保留/组播/未指定一律拒绝。
    - 域名:解析全部 A/AAAA 记录,**所有地址都命中私网才拒绝**;只要有任一
      公网地址就放行(CDN 域名常返回混合的 IPv4 公网 + IPv6 占位记录,
      如 YouTube 在国内返回 2001::1)。DNS 失败时交由 yt-dlp 自行处理。
    注意:DNS 与实际请求之间存在 TOCTOU,但此处挡掉了纯内网目标。
    """
    parsed = urlparse(value)
    host = parsed.hostname
    if not host:
        return False
    # 先按字面 IP 校验(域名可能是 IP 字面量)
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        # 域名:解析后只要有任一公网地址即放行
        try:
            infos = socket.getaddrinfo(host, None)
        except socket.gaierror:
            return True
        has_any_address = False
        for info in infos:
            try:
                ip = ipaddress.ip_address(info[4][0])
            except (ValueError, IndexError):
                continue
            has_any_address = True
            if not _is_private_or_reserved(ip):
                return True
        # 所有解析到的地址都是私网(或完全没解析到地址)→ 拒绝
        return not has_any_address
    return not _is_private_or_reserved(ip)


def _is_private_or_reserved(ip):
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    )


def is_allowed_extract_host(host):
    """host 白名单判定:与允许项相等或为其子域(大小写/末尾点归一化)。

    host 取自 urlparse().hostname(已剥端口与 user:pass);YouTube 各区域
    前缀(www./m./music.)靠子域规则覆盖。
    """
    host = (host or "").strip().lower().rstrip(".")
    if not host:
        return False
    for allowed in ALLOWED_EXTRACT_HOSTS:
        if host == allowed or host.endswith("." + allowed):
            return True
    return False


def extract_vod(url):
    if not EXTRACTOR_SEMAPHORE.acquire(blocking=False):
        raise ExtractorBusyError("extractor is busy")
    try:
        return run_extractor(url, single_video=True)
    finally:
        EXTRACTOR_SEMAPHORE.release()


def parse_search_payload(payload):
    """校验 /search 请求体,返回 (query, limit)。非法时抛 ValueError(→400)。"""
    query = payload.get("query")
    if not isinstance(query, str) or not query.strip():
        raise ValueError("query is required")
    query = query.strip()
    if len(query.encode("utf-8")) > MAX_QUERY_BYTES:
        raise ValueError("query is too long")
    limit = payload.get("limit", DEFAULT_SEARCH_LIMIT)
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1 or limit > MAX_SEARCH_LIMIT:
        raise ValueError(f"limit must be an integer in [1, {MAX_SEARCH_LIMIT}]")
    return query, limit


def search_youtube(query, limit):
    """YouTube 搜索:走 yt-dlp 的 ytsearch 伪播放列表(--flat-playlist 只抓
    搜索结果页,不逐个解析视频),与观众浏览器同一条公开前端路径,无 Data API 配额。"""
    if not EXTRACTOR_SEMAPHORE.acquire(blocking=False):
        raise ExtractorBusyError("extractor is busy")
    try:
        return run_extractor(f"ytsearch{limit}:{query}", single_video=False)
    finally:
        EXTRACTOR_SEMAPHORE.release()


def run_extractor(url, single_video=True):
    # start_new_session=True 让 yt-dlp 及其子进程(ffmpeg 等)独占进程组,
    # stop_process 用 os.killpg 杀整组,避免 SIGKILL 只杀主进程留下孤儿。
    process = subprocess.Popen(
        extractor_command(url, single_video),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    stdout, stderr = read_limited_process_output(process)
    if process.returncode != 0:
        detail = stderr.decode("utf-8", errors="replace").strip()[:2000]
        if detail:
            log_internal(f"yt-dlp exited {process.returncode}: {detail}")
        raise RuntimeError("extractor failed")

    try:
        return json.loads(stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("extractor returned invalid JSON") from error


def log_internal(message):
    entry = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "level": "error",
        "msg": str(message)[:2000],
    }
    print(json.dumps(entry, ensure_ascii=False), file=sys.stdout, flush=True)


def extractor_command(target, single_video=True):
    command = [EXTRACTOR_BIN, "--dump-single-json", "--no-warnings"]
    if single_video:
        command.append("--no-playlist")
    else:
        # 搜索结果是一个"播放列表":flat 模式只抓列表页(快),绝不逐个解析视频。
        command.append("--flat-playlist")
    if EXTRACTOR_ARGS:
        command.extend(["--extractor-args", EXTRACTOR_ARGS])
    cookies_file = extractor_cookies_file()
    if cookies_file:
        command.extend(["--cookies", cookies_file])
    command.append(target)
    return command


def extractor_cookies_file():
    if not COOKIES_FILE:
        return None
    if os.path.isfile(COOKIES_FILE) and os.access(COOKIES_FILE, os.R_OK):
        return COOKIES_FILE
    if COOKIES_FILE_EXPLICIT:
        # 存在但不可读(宿主属主 0600 直挂)与缺失同样致命,统一走配置错误(503)
        raise ExtractorConfigError("configured cookies file is missing or not readable")
    return None


def cookies_health():
    """/health 暴露的 cookies 文件健康信号(只读元数据,不含内容)。

    cookies 过期/缺失时解析与搜索全部失败但不产生主动告警,外部监控
    依赖本字段:present=false 或 age_seconds 异常增大即应告警。
    """
    if not COOKIES_FILE:
        return {"configured": False}
    try:
        stat = os.stat(COOKIES_FILE)
    except OSError:
        return {"configured": COOKIES_FILE_EXPLICIT, "present": False}
    return {
        "configured": COOKIES_FILE_EXPLICIT,
        "present": True,
        "readable": os.access(COOKIES_FILE, os.R_OK),
        "age_seconds": max(0, int(time.time() - stat.st_mtime)),
    }


def read_limited_process_output(process):
    selector = selectors.DefaultSelector()
    stdout = bytearray()
    stderr = bytearray()
    deadline = time.monotonic() + TIMEOUT_SECONDS

    for name, stream in (("stdout", process.stdout), ("stderr", process.stderr)):
        if stream is None:
            continue
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ, name)

    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                stop_process(process)
                raise TimeoutError("extractor timed out")
            for key, _ in selector.select(remaining):
                stream = key.fileobj
                name = key.data
                try:
                    chunk = stream.read1(65536)
                except BlockingIOError:
                    continue
                if chunk is None:
                    continue
                if chunk == b"":
                    selector.unregister(stream)
                    continue
                if name == "stdout":
                    if len(stdout) + len(chunk) > MAX_OUTPUT_BYTES:
                        stop_process(process)
                        raise RuntimeError("extractor output is too large")
                    stdout.extend(chunk)
                else:
                    remaining_error = MAX_ERROR_BYTES - len(stderr)
                    if remaining_error > 0:
                        stderr.extend(chunk[:remaining_error])
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired as error:
            stop_process(process)
            raise TimeoutError("extractor timed out") from error
        return bytes(stdout), bytes(stderr)
    finally:
        selector.close()
        # 显式关闭 pipe fd,不依赖 Popen GC(异常路径下可能滞后回收)
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                try:
                    stream.close()
                except OSError:
                    pass


def stop_process(process):
    # 杀整个进程组(start_new_session=True 创建的),收掉 yt-dlp 派生的 ffmpeg
    # 等子进程,避免孤儿继续消耗资源。进程组已失效时退回杀单个进程。
    if process.poll() is None:
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        log_internal(f"process {process.pid} did not exit after kill")


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """并发连接数有上限的 ThreadingHTTPServer。

    超限的新连接在 process_request 阶段直接关闭(不发响应,客户端表现为连接
    被重置);已接入的连接不受影响。槽位在连接处理线程结束时释放。
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._conn_slots = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def process_request(self, request, client_address):
        if not self._conn_slots.acquire(blocking=False):
            self.close_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._conn_slots.release()


def main():
    global YTDLP_VERSION
    YTDLP_VERSION = _ytdlp_version()
    # 启动期校验:显式配置的 cookies 文件必须存在且可读,否则 fail-fast
    # (避免运行期每次请求都报错,且静默降级会让登录受限内容解析全失败)。
    if COOKIES_FILE_EXPLICIT and not (
        os.path.isfile(COOKIES_FILE) and os.access(COOKIES_FILE, os.R_OK)
    ):
        print(
            f"error: configured cookies file missing or not readable: {COOKIES_FILE}",
            file=sys.stderr,
        )
        sys.exit(1)

    server = BoundedThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"vod extractor {SERVICE_VERSION} (yt-dlp {YTDLP_VERSION}) listening on {HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
