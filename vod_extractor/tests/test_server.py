# vod_extractor 服务端纯函数单测（纯标准库 unittest， fixtures 内联）。
#
# server.py 在 import 时只读环境变量与 /app/version（OSError 有兜底）、
# 创建信号量，不启动网络服务，可直接 import；DNS 相关判定通过 mock
# socket.getaddrinfo 离线测试。
#
# 运行：
#   python3 -m unittest discover -s vod_extractor/tests -v

import ipaddress
import os
import socket
import subprocess
import sys
import time
import unittest
from http.server import ThreadingHTTPServer
from unittest import mock
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server  # noqa: E402


def addrinfos(*addresses):
    """构造 getaddrinfo 返回形状的最小 stub（info[4][0] 取地址）。"""
    return [(socket.AF_INET, None, None, "", (a, 0)) for a in addresses]


class IsHttpUrlTest(unittest.TestCase):
    def test_accepts_http_and_https(self):
        self.assertTrue(server.is_http_url("https://www.youtube.com/watch?v=x"))
        self.assertTrue(server.is_http_url("http://example.com"))

    def test_rejects_other_scheme(self):
        self.assertFalse(server.is_http_url("ftp://example.com/file"))
        self.assertFalse(server.is_http_url("file:///etc/passwd"))

    def test_rejects_missing_netloc(self):
        self.assertFalse(server.is_http_url("https:///path/only"))


class IsSafeTargetLiteralIPTest(unittest.TestCase):
    """字面 IP 直接判定，不触发 DNS。"""

    def test_public_ip_allowed(self):
        self.assertTrue(server.is_safe_target("https://8.8.8.8/video"))
        self.assertTrue(server.is_safe_target("https://142.250.196.110/x"))

    def test_private_ranges_denied(self):
        for ip in ("192.168.1.10", "10.0.0.5", "172.16.0.1", "172.31.255.255"):
            with self.subTest(ip=ip):
                self.assertFalse(server.is_safe_target(f"http://{ip}/x"))

    def test_loopback_denied(self):
        self.assertFalse(server.is_safe_target("http://127.0.0.1:8790/x"))
        self.assertFalse(server.is_safe_target("http://[::1]/x"))

    def test_link_local_metadata_denied(self):
        # 云元数据端点是 SSRF 的首要目标
        self.assertFalse(server.is_safe_target("http://169.254.169.254/latest/meta-data/"))

    def test_reserved_multicast_unspecified_denied(self):
        for ip in ("240.0.0.1", "224.0.0.1", "0.0.0.0", "::"):
            with self.subTest(ip=ip):
                self.assertFalse(server.is_safe_target(f"http://{ip}/x"))

    def test_ipv6_private_denied(self):
        self.assertFalse(server.is_safe_target("http://[fe80::1]/x"))
        self.assertFalse(server.is_safe_target("http://[fc00::1]/x"))

    def test_port_and_userinfo_do_not_bypass(self):
        # hostname 会剥掉端口与 user:pass，判定不受干扰
        self.assertFalse(server.is_safe_target("http://user:pass@10.0.0.2:8080/x"))
        self.assertTrue(server.is_safe_target("http://user:pass@8.8.8.8:443/x"))

    def test_missing_host_denied(self):
        self.assertFalse(server.is_safe_target("not-a-url"))
        self.assertFalse(server.is_safe_target("https:///no-host"))


class IsSafeTargetDomainTest(unittest.TestCase):
    """域名分支：解析所有记录，全私网才拒绝；任一公网即放行。"""

    def test_all_private_records_denied(self):
        with mock.patch.object(server.socket, "getaddrinfo",
                               return_value=addrinfos("192.168.0.1", "10.1.2.3")):
            self.assertFalse(server.is_safe_target("https://internal.example.com/x"))

    def test_mixed_records_allowed(self):
        # CDN 常返回混合记录（如 YouTube 在国内返回 2001::1 占位 + 公网 IPv4）
        with mock.patch.object(server.socket, "getaddrinfo",
                               return_value=addrinfos("192.168.0.1", "2001::1", "8.8.8.8")):
            self.assertTrue(server.is_safe_target("https://cdn.example.com/x"))

    def test_all_public_allowed(self):
        with mock.patch.object(server.socket, "getaddrinfo",
                               return_value=addrinfos("142.250.196.110", "2607:f8b0:4004::1")):
            self.assertTrue(server.is_safe_target("https://www.youtube.com/x"))

    def test_dns_failure_allowed(self):
        # DNS 失败交由 yt-dlp 自行处理，不在此拦截
        with mock.patch.object(server.socket, "getaddrinfo",
                               side_effect=socket.gaierror("no dns")):
            self.assertTrue(server.is_safe_target("https://no-resolve.example.com/x"))

    def test_unparseable_records_only_allowed(self):
        # 解析结果全是无法解析的畸形地址 → 视同没解析到地址 → 与 DNS 失败同策略放行
        infos = [(socket.AF_INET, None, None, "", ("not-an-ip", 0))]
        with mock.patch.object(server.socket, "getaddrinfo", return_value=infos):
            self.assertTrue(server.is_safe_target("https://weird.example.com/x"))

    def test_no_dns_query_for_literal_ip(self):
        # 字面 IP 不应触发 DNS 查询
        with mock.patch.object(server.socket, "getaddrinfo") as ga:
            self.assertFalse(server.is_safe_target("http://10.0.0.1/x"))
            ga.assert_not_called()


class IsPrivateOrReservedTest(unittest.TestCase):
    def test_boundary_addresses(self):
        # 网段边界抽样：私网首尾、组播、保留段
        cases = {
            "192.168.255.255": True,
            "192.168.0.0": True,
            "172.15.255.255": False,
            "172.32.0.0": False,
            "239.255.255.255": True,
            "100.64.0.1": False,  # CGNAT 段不算 is_private
        }
        for addr, expected in cases.items():
            with self.subTest(addr=addr):
                self.assertEqual(server._is_private_or_reserved(ipaddress.ip_address(addr)), expected)


class ParseSearchPayloadTest(unittest.TestCase):
    def test_valid_query_with_limit(self):
        self.assertEqual(server.parse_search_payload({"query": " hello ", "limit": 5}), ("hello", 5))

    def test_default_limit(self):
        self.assertEqual(server.parse_search_payload({"query": "abc"}), ("abc", server.DEFAULT_SEARCH_LIMIT))

    def test_query_required(self):
        for payload in ({}, {"query": ""}, {"query": "   "}, {"query": 123}, {"query": None}):
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    server.parse_search_payload(payload)

    def test_query_byte_limit_counts_utf8(self):
        # 128 字节上限按 UTF-8 计：42 个中文 = 126 字节合法，43 个 = 129 字节超限
        self.assertEqual(server.parse_search_payload({"query": "中" * 42}), ("中" * 42, server.DEFAULT_SEARCH_LIMIT))
        with self.assertRaises(ValueError):
            server.parse_search_payload({"query": "中" * 43})

    def test_limit_bounds(self):
        self.assertEqual(server.parse_search_payload({"query": "q", "limit": 1})[1], 1)
        self.assertEqual(server.parse_search_payload({"query": "q", "limit": server.MAX_SEARCH_LIMIT})[1],
                         server.MAX_SEARCH_LIMIT)
        for bad in (0, -1, server.MAX_SEARCH_LIMIT + 1, "5", 1.5, None):
            with self.subTest(limit=bad):
                with self.assertRaises(ValueError):
                    server.parse_search_payload({"query": "q", "limit": bad})

    def test_limit_bool_rejected(self):
        # Python 里 bool 是 int 子类，True==1，必须显式拒绝
        with self.assertRaises(ValueError):
            server.parse_search_payload({"query": "q", "limit": True})


class ExtractorCommandTest(unittest.TestCase):
    def test_single_video_command(self):
        with mock.patch.object(server, "EXTRACTOR_BIN", "yt-dlp"), \
             mock.patch.object(server, "EXTRACTOR_ARGS", ""), \
             mock.patch.object(server, "COOKIES_FILE", ""), \
             mock.patch.object(server, "extractor_cookies_file", return_value=None):
            cmd = server.extractor_command("https://youtu.be/abc", single_video=True)
        self.assertEqual(cmd, ["yt-dlp", "--dump-single-json", "--no-warnings", "--no-playlist",
                               "https://youtu.be/abc"])

    def test_search_command_uses_flat_playlist(self):
        with mock.patch.object(server, "EXTRACTOR_BIN", "yt-dlp"), \
             mock.patch.object(server, "EXTRACTOR_ARGS", ""), \
             mock.patch.object(server, "extractor_cookies_file", return_value=None):
            cmd = server.extractor_command("ytsearch3:cats", single_video=False)
        self.assertIn("--flat-playlist", cmd)
        self.assertNotIn("--no-playlist", cmd)
        self.assertEqual(cmd[-1], "ytsearch3:cats")

    def test_extractor_args_passthrough(self):
        with mock.patch.object(server, "EXTRACTOR_BIN", "yt-dlp"), \
             mock.patch.object(server, "EXTRACTOR_ARGS", "youtube:player_client=tv"), \
             mock.patch.object(server, "extractor_cookies_file", return_value=None):
            cmd = server.extractor_command("https://youtu.be/abc", single_video=True)
        self.assertEqual(cmd[4:6], ["--extractor-args", "youtube:player_client=tv"])


class ExtractorCookiesFileTest(unittest.TestCase):
    def test_explicit_missing_file_raises(self):
        # 显式配置（环境变量注入）的 cookies 文件必须存在，缺失即配置错误（→503）
        with mock.patch.object(server, "COOKIES_FILE", "/nonexistent/cookies.txt"), \
             mock.patch.object(server, "COOKIES_FILE_EXPLICIT", True):
            with self.assertRaises(server.ExtractorConfigError):
                server.extractor_cookies_file()

    def test_explicit_unreadable_file_raises(self):
        # 存在但不可读（如宿主属主 0600 直挂进容器）与缺失同样致命
        import tempfile

        with tempfile.NamedTemporaryFile() as f:
            os.chmod(f.name, 0o000)
            try:
                with mock.patch.object(server, "COOKIES_FILE", f.name), \
                     mock.patch.object(server, "COOKIES_FILE_EXPLICIT", True):
                    with self.assertRaises(server.ExtractorConfigError):
                        server.extractor_cookies_file()
            finally:
                os.chmod(f.name, 0o600)

    def test_default_missing_file_is_none(self):
        # 默认路径缺失是正常状态（未放 cookies），静默降级
        with mock.patch.object(server, "COOKIES_FILE", "/nonexistent/cookies.txt"), \
             mock.patch.object(server, "COOKIES_FILE_EXPLICIT", False):
            self.assertIsNone(server.extractor_cookies_file())

    def test_existing_file_returned(self):
        with mock.patch.object(server, "COOKIES_FILE", __file__), \
             mock.patch.object(server, "COOKIES_FILE_EXPLICIT", False):
            self.assertEqual(server.extractor_cookies_file(), __file__)


class IsAllowedExtractHostTest(unittest.TestCase):
    """/extract 目标 host 白名单：youtube 域精确或子域，其余拒绝。"""

    def test_youtube_hosts_allowed(self):
        for url in (
            "https://www.youtube.com/watch?v=abc",
            "https://youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://m.youtube.com/watch?v=abc",
            "https://music.youtube.com/watch?v=abc",
        ):
            with self.subTest(url=url):
                self.assertTrue(server.is_allowed_extract_host(urlparse(url).hostname))

    def test_subdomain_allowed(self):
        self.assertTrue(server.is_allowed_extract_host("a.b.youtube.com"))

    def test_non_youtube_denied(self):
        for host in ("example.com", "youtube.com.evil.io", "notyoutube.com", "169.254.169.254"):
            with self.subTest(host=host):
                self.assertFalse(server.is_allowed_extract_host(host))

    def test_case_and_trailing_dot_normalized(self):
        self.assertTrue(server.is_allowed_extract_host("WWW.YouTube.COM."))
        self.assertTrue(server.is_allowed_extract_host(" YOUTUBE.com "))

    def test_suffix_lookalike_denied(self):
        # endswith(".youtube.com") 不应放过 "evilyoutube.com" 这类后缀撞名
        self.assertFalse(server.is_allowed_extract_host("evilyoutube.com"))

    def test_empty_host_denied(self):
        self.assertFalse(server.is_allowed_extract_host(""))
        self.assertFalse(server.is_allowed_extract_host(None))


class CookiesHealthTest(unittest.TestCase):
    """cookies 健康信号：只读元数据，不读文件内容。"""

    def test_missing_file_reports_absent(self):
        with mock.patch.object(server, "COOKIES_FILE", "/nonexistent/cookies.txt"), \
             mock.patch.object(server, "COOKIES_FILE_EXPLICIT", False):
            info = server.cookies_health()
        self.assertFalse(info["present"])
        self.assertFalse(info["configured"])

    def test_missing_explicit_reports_configured(self):
        with mock.patch.object(server, "COOKIES_FILE", "/nonexistent/cookies.txt"), \
             mock.patch.object(server, "COOKIES_FILE_EXPLICIT", True):
            info = server.cookies_health()
        self.assertFalse(info["present"])
        self.assertTrue(info["configured"])

    def test_present_file_reports_metadata(self):
        import tempfile

        with tempfile.NamedTemporaryFile() as f:
            with mock.patch.object(server, "COOKIES_FILE", f.name), \
                 mock.patch.object(server, "COOKIES_FILE_EXPLICIT", False):
                info = server.cookies_health()
        self.assertTrue(info["present"])
        self.assertTrue(info["readable"])
        self.assertGreaterEqual(info["age_seconds"], 0)
        self.assertLess(info["age_seconds"], 60)
class BoundedServerSlotsTest(unittest.TestCase):
    """连接数上限子类:槽位容量来自配置,超限拒连、线程结束释放。

    不真开网络:绑随机端口构造实例,process_request 用 mock 打桩验证
    "超限 → close_request、不放行父类"与"处理线程结束后槽位释放"两条语义。
    """

    def _make_server(self):
        s = server.BoundedThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.addCleanup(s.server_close)
        return s

    def _acquire_all_slots(self, s):
        acquired = []
        while s._conn_slots.acquire(blocking=False):
            acquired.append(True)
        return acquired

    def test_slot_capacity_matches_config(self):
        s = self._make_server()
        acquired = self._acquire_all_slots(s)
        self.assertEqual(len(acquired), server.MAX_CONNECTIONS)
        for _ in acquired:
            s._conn_slots.release()

    def test_over_limit_closes_and_parent_not_called(self):
        s = self._make_server()
        self._acquire_all_slots(s)
        with mock.patch.object(s, "close_request") as close_request, \
             mock.patch.object(ThreadingHTTPServer, "process_request") as parent:
            s.process_request(mock.Mock(), ("127.0.0.1", 12345))
            close_request.assert_called_once()
            parent.assert_not_called()

    def test_slot_released_after_thread_finishes(self):
        # 处理线程异步结束并释放槽位;轮询等待(上限 2s),未释放则失败
        s = self._make_server()
        with mock.patch.object(ThreadingHTTPServer, "process_request_thread"):
            s.process_request(mock.Mock(), ("127.0.0.1", 12345))
            deadline = time.time() + 2
            while s._conn_slots._value < s._conn_slots._initial_value and time.time() < deadline:
                time.sleep(0.01)
        self.assertEqual(s._conn_slots._value, s._conn_slots._initial_value)


class MaxConnectionsEnvTest(unittest.TestCase):
    """VOD_EXTRACTOR_MAX_CONNECTIONS 环境变量解析回归。

    compose 以 ${VAR:-} 注入时键存在但值为空串,os.environ.get 的默认值
    不生效:历史实现在模块顶层 int("") 抛 ValueError,import 即死,
    容器 restart: unless-stopped 无限重启。用子进程真实 import 验证
    空串回落默认值、合法值被采纳两条语义。
    """

    def _import_with_env(self, value):
        env = dict(os.environ, VOD_EXTRACTOR_MAX_CONNECTIONS=value)
        return subprocess.run(
            [sys.executable, "-c", "import server; print(server.MAX_CONNECTIONS)"],
            capture_output=True,
            text=True,
            env=env,
            cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            timeout=30,
        )

    def test_empty_value_falls_back_to_default(self):
        proc = self._import_with_env("")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(int(proc.stdout.strip()), max(4, int("32")))

    def test_missing_value_uses_default(self):
        env = {k: v for k, v in os.environ.items() if k != "VOD_EXTRACTOR_MAX_CONNECTIONS"}
        proc = subprocess.run(
            [sys.executable, "-c", "import server; print(server.MAX_CONNECTIONS)"],
            capture_output=True,
            text=True,
            env=env,
            cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(int(proc.stdout.strip()), 32)

    def test_explicit_value_respected(self):
        proc = self._import_with_env("7")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(int(proc.stdout.strip()), 7)


class ReadJsonBodyTest(unittest.TestCase):
    """read_json_body 请求行健壮性:content-length 非数字 → 400 语义;
    body 读取超时/中断 → RequestBodyTimeoutError(408),不与上游 yt-dlp
    超时(同为 TimeoutError → 504)混淆。

    Handler 用 __new__ 绕过 __init__(避免真实 socket);headers 用 dict
    即可——代码只调用 .get()。
    """

    @staticmethod
    def _handler_with_length(value):
        handler = server.Handler.__new__(server.Handler)
        handler.headers = {"content-length": value}
        return handler

    def test_non_numeric_content_length_is_client_error(self):
        handler = self._handler_with_length("abc")
        with self.assertRaises(ValueError):
            handler.read_json_body()

    def test_missing_content_length_is_client_error(self):
        handler = self._handler_with_length("0")
        with self.assertRaises(ValueError):
            handler.read_json_body()

    def test_body_read_timeout_raises_request_body_timeout(self):
        handler = self._handler_with_length("16")

        class _TimeoutFile:
            @staticmethod
            def read(_n):
                raise TimeoutError("socket timed out")

        handler.rfile = _TimeoutFile()
        with self.assertRaises(server.RequestBodyTimeoutError):
            handler.read_json_body()


class DoPostBodyTimeoutTest(unittest.TestCase):
    """do_POST 对 RequestBodyTimeoutError 返回 408 并关闭连接(响应发出后
    连接状态已不可信)。"""

    def test_body_timeout_returns_408_and_closes(self):
        handler = server.Handler.__new__(server.Handler)
        handler.path = "/extract"
        handler.close_connection = False
        sent = {}
        with mock.patch.object(
            server.Handler,
            "read_json_body",
            side_effect=server.RequestBodyTimeoutError("request body read failed"),
        ), mock.patch.object(
            server.Handler,
            "send_json",
            side_effect=lambda status, payload: sent.update(status=status, payload=payload),
        ):
            handler.do_POST()
        self.assertEqual(sent["status"], 408)
        self.assertEqual(sent["payload"], {"error": "request body read failed"})
        self.assertTrue(handler.close_connection)


class SendJsonDisconnectTest(unittest.TestCase):
    """send_json 在客户端断连(写响应失败)时静默关闭连接,且访问日志照记。

    历史实现把 BrokenPipeError 漏给 ThreadingMixIn.handle_error 打
    traceback,且 write 失败跳过 log_json 丢日志。
    """

    @staticmethod
    def _handler(write_error):
        handler = server.Handler.__new__(server.Handler)
        handler.path = "/extract"
        handler.close_connection = False
        handler.send_response = mock.Mock()
        handler.send_header = mock.Mock()
        handler.end_headers = mock.Mock()
        handler.wfile = mock.Mock()
        handler.wfile.write.side_effect = write_error
        logged = []
        handler.log_json = lambda status, payload: logged.append(status)
        return handler, logged

    def test_broken_pipe_silenced_and_logged(self):
        handler, logged = self._handler(BrokenPipeError())
        handler.send_json(200, {"ok": True})  # 不得抛异常
        self.assertTrue(handler.close_connection)
        self.assertEqual(logged, [200])

    def test_connection_reset_silenced_and_logged(self):
        handler, logged = self._handler(ConnectionResetError())
        handler.send_json(200, {"ok": True})
        self.assertTrue(handler.close_connection)
        self.assertEqual(logged, [200])


class HandleErrorSilenceTest(unittest.TestCase):
    """handle_error:连接类异常(断连/读请求行超时)静默,其余仍走默认输出。"""

    @staticmethod
    def _raise_and_handle(s, error):
        try:
            raise error
        except type(error):
            s.handle_error(mock.Mock(), ("127.0.0.1", 12345))

    def test_connection_errors_silenced(self):
        s = server.BoundedThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.addCleanup(s.server_close)
        for error in (BrokenPipeError(), ConnectionResetError(), TimeoutError()):
            self._raise_and_handle(s, error)  # 不抛异常、不打印即通过

    def test_unexpected_error_still_reported(self):
        s = server.BoundedThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.addCleanup(s.server_close)
        with mock.patch.object(ThreadingHTTPServer, "handle_error") as parent:
            self._raise_and_handle(s, RuntimeError("boom"))
            parent.assert_called_once()




class ClassifyFailureTest(unittest.TestCase):
    """yt-dlp stderr 特征分类:bot_check/风控与普通失败可区分,匹配不到回退 unknown。"""

    def test_bot_check(self):
        stderr = "ERROR: [youtube] abc123: Sign in to confirm you're not a bot."
        self.assertEqual(server.classify_failure(stderr), "bot_check")

    def test_rate_limited(self):
        self.assertEqual(server.classify_failure("HTTP Error 429: Too Many Requests"), "rate_limited")

    def test_unavailable(self):
        self.assertEqual(server.classify_failure("ERROR: [youtube] abc123: Video unavailable"), "unavailable")

    def test_geo_blocked(self):
        self.assertEqual(
            server.classify_failure("ERROR: This video is not available in your country."),
            "geo_blocked",
        )

    def test_bot_check_unicode_apostrophe(self):
        """回归:yt-dlp 实测输出 U+2019 右单引号(’re),而非 ASCII('re)。

        只匹配 ASCII 会让真实风控文案落进 unknown,而 bot_check 正是
        cookies 失效告警的唯一信号——2026-10-12 线上实测漏判。
        """
        real = (
            "ERROR: [youtube] abc123: Sign in to confirm you\u2019re not a bot. "
            "Use --cookies-from-browser or --cookies for the authentication."
        )
        self.assertEqual(server.classify_failure(real), "bot_check")

    def test_bot_check_other_apostrophe_variants(self):
        for apostrophe in ("\u2019", "\u2018", "\u02bc", "\u02b9", "'"):
            text = f"Sign in to confirm you{apostrophe}re not a bot"
            self.assertEqual(
                server.classify_failure(text), "bot_check", f"apostrophe U+{ord(apostrophe):04X}"
            )

    def test_unknown_and_empty(self):
        self.assertEqual(server.classify_failure("some totally different error"), "unknown")
        self.assertEqual(server.classify_failure(""), "unknown")


class ExtractorFailedReasonTest(unittest.TestCase):
    """yt-dlp 失败 → 502 body 带 reason 分类,/health failures 计数累计。

    计数是模块全局状态,每个用例先清零,互不污染。
    """

    def setUp(self):
        server.FAILURE_STATS.update(
            {"total": 0, "last_reason": None, "last_at": None, "counts": {}}
        )
        self.addCleanup(
            lambda: server.FAILURE_STATS.update(
                {"total": 0, "last_reason": None, "last_at": None, "counts": {}}
            )
        )

    def test_do_post_returns_reason_and_records(self):
        handler = server.Handler.__new__(server.Handler)
        handler.path = "/extract"
        sent = {}

        def _fake_run(url, single_video=True):
            # 模拟真实 run_extractor 的失败路径:先记录分类再抛错
            server.record_failure("bot_check")
            raise server.ExtractorFailedError("bot_check", "Sign in to confirm...")

        with mock.patch.object(
            server.socket,
            "getaddrinfo",
            return_value=addrinfos("8.8.8.8"),
        ), mock.patch.object(
            server,
            "run_extractor",
            side_effect=_fake_run,
        ), mock.patch.object(
            server.Handler,
            "read_json_body",
            return_value={"url": "https://www.youtube.com/watch?v=abc"},
        ), mock.patch.object(
            server.Handler,
            "send_json",
            side_effect=lambda status, payload: sent.update(status=status, payload=payload),
        ):
            handler.do_POST()
        self.assertEqual(sent["status"], 502)
        self.assertEqual(sent["payload"]["reason"], "bot_check")
        self.assertEqual(sent["payload"]["error"], "extractor failed")
        snap = server.failure_snapshot()
        self.assertEqual(snap["total"], 1)
        self.assertEqual(snap["counts"]["bot_check"], 1)
        self.assertEqual(snap["last_reason"], "bot_check")

    def test_snapshot_returns_isolated_copy(self):
        server.record_failure("timeout")
        snap = server.failure_snapshot()
        snap["counts"]["timeout"] = 999
        self.assertEqual(server.failure_snapshot()["counts"]["timeout"], 1)


if __name__ == "__main__":
    unittest.main()
