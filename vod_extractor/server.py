import json
import os
import selectors
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


class ExtractorBusyError(RuntimeError):
    pass


class Handler(BaseHTTPRequestHandler):
    server_version = "vod-extractor/1.0"

    def do_GET(self):
        self.started_at = time.monotonic()
        if self.path == "/health":
            self.send_json(200, {"ok": True})
            return
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        self.started_at = time.monotonic()
        if self.path != "/extract":
            self.send_json(404, {"error": "not found"})
            return

        try:
            payload = self.read_json_body()
            url = payload.get("url")
            if not isinstance(url, str) or not is_http_url(url):
                self.send_json(400, {"error": "url must be an http(s) URL"})
                return
            extracted = extract_vod(url)
        except TimeoutError:
            self.send_json(504, {"error": "extractor timed out"})
            return
        except ValueError as error:
            self.send_json(400, {"error": str(error)})
            return
        except ExtractorBusyError as error:
            self.send_json(503, {"error": str(error)})
            return
        except RuntimeError as error:
            self.send_json(502, {"error": str(error)})
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


def extract_vod(url):
    if not EXTRACTOR_SEMAPHORE.acquire(blocking=False):
        raise ExtractorBusyError("extractor is busy")
    try:
        return run_extractor(url)
    finally:
        EXTRACTOR_SEMAPHORE.release()


def run_extractor(url):
    process = subprocess.Popen(
        extractor_command(url),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
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


def extractor_command(url):
    command = [EXTRACTOR_BIN, "--dump-single-json", "--no-playlist", "--no-warnings"]
    if EXTRACTOR_ARGS:
        command.extend(["--extractor-args", EXTRACTOR_ARGS])
    cookies_file = extractor_cookies_file()
    if cookies_file:
        command.extend(["--cookies", cookies_file])
    command.append(url)
    return command


def extractor_cookies_file():
    if not COOKIES_FILE:
        return None
    if os.path.isfile(COOKIES_FILE):
        return COOKIES_FILE
    if COOKIES_FILE_EXPLICIT:
        raise RuntimeError("configured cookies file is not readable")
    return None


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


def stop_process(process):
    if process.poll() is None:
        process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"vod extractor listening on {HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
