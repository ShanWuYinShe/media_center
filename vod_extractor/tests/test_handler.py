# vod_extractor handler 层测试:do_POST / do_GET 的状态码映射与分支覆盖。
#
# 既有测试覆盖了纯函数(read_json_body / parse_search_payload / 分类 / SSRF
# 判定),但 handler 本身未被验证:错误类型到 HTTP 状态码的映射表
# (400/404/408/500/502/503/504)此前完全没有测试锁定,改动极易静默破坏
# media_server 侧依赖的语义(尤其 504 与 408 的区分、502 的 reason 分类)。
#
# Handler 用 __new__ 绕过 __init__(避免真实 socket);send_json 被 mock 成
# 记录调用,不发真实响应。

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server  # noqa: E402


def make_handler(path):
    """构造一个不绑 socket 的 Handler,并捕获 send_json 调用。"""
    handler = server.Handler.__new__(server.Handler)
    handler.path = path
    handler.close_connection = False
    handler.command = "POST"
    handler.client_address = ("127.0.0.1", 12345)
    handler.headers = {"content-length": "0"}
    handler.rfile = None
    sent = []

    def _send_json(status, payload):
        sent.append((status, payload))

    handler.send_json = _send_json
    return handler, sent


class DoPostRoutingTest(unittest.TestCase):
    """路径白名单:未知路径 404,不读 body。"""

    def test_unknown_path_returns_404(self):
        handler, sent = make_handler("/nope")
        with mock.patch.object(server.Handler, "read_json_body") as read_body:
            handler.do_POST()
        self.assertEqual(sent, [(404, {"error": "not found"})])
        read_body.assert_not_called()

    def test_health_post_not_routed(self):
        # /health 只在 do_GET 提供,POST 应 404。
        handler, sent = make_handler("/health")
        handler.do_POST()
        self.assertEqual(sent[0][0], 404)


class DoPostExtractValidationTest(unittest.TestCase):
    """ /extract 的三道前置校验(URL 形状 / SSRF / host 白名单)都返回 400,
    且必须在调用 extract_vod 之前短路。"""

    def _post_extract(self, payload, *, safe=True, allowed=True):
        handler, sent = make_handler("/extract")
        with mock.patch.object(server.Handler, "read_json_body", return_value=payload), \
             mock.patch.object(server, "is_safe_target", return_value=safe), \
             mock.patch.object(server, "is_allowed_extract_host", return_value=allowed), \
             mock.patch.object(server, "extract_vod") as extract:
            handler.do_POST()
        return sent, extract

    def test_missing_url_returns_400(self):
        sent, extract = self._post_extract({})
        self.assertEqual(sent, [(400, {"error": "url must be an http(s) URL"})])
        extract.assert_not_called()

    def test_non_http_url_returns_400(self):
        sent, extract = self._post_extract({"url": "ftp://example.com/x"})
        self.assertEqual(sent, [(400, {"error": "url must be an http(s) URL"})])
        extract.assert_not_called()

    def test_non_string_url_returns_400(self):
        sent, extract = self._post_extract({"url": 12345})
        self.assertEqual(sent, [(400, {"error": "url must be an http(s) URL"})])
        extract.assert_not_called()

    def test_unsafe_target_returns_400(self):
        sent, extract = self._post_extract(
            {"url": "https://www.youtube.com/watch?v=x"}, safe=False
        )
        self.assertEqual(sent, [(400, {"error": "url target is not allowed"})])
        extract.assert_not_called()

    def test_disallowed_host_returns_400(self):
        sent, extract = self._post_extract(
            {"url": "https://evil.example.com/watch?v=x"}, allowed=False
        )
        self.assertEqual(sent, [(400, {"error": "url host is not allowed"})])
        extract.assert_not_called()

    def test_valid_url_calls_extract_and_returns_200(self):
        handler, sent = make_handler("/extract")
        with mock.patch.object(
            server.Handler, "read_json_body", return_value={"url": "https://www.youtube.com/watch?v=x"}
        ), mock.patch.object(server, "is_safe_target", return_value=True), \
           mock.patch.object(server, "is_allowed_extract_host", return_value=True), \
           mock.patch.object(server, "extract_vod", return_value={"title": "t"}) as extract:
            handler.do_POST()
        self.assertEqual(sent, [(200, {"title": "t"})])
        extract.assert_called_once_with("https://www.youtube.com/watch?v=x")


class DoPostSearchTest(unittest.TestCase):
    def test_search_calls_search_youtube(self):
        handler, sent = make_handler("/search")
        with mock.patch.object(
            server.Handler, "read_json_body", return_value={"query": "cats", "limit": 5}
        ), mock.patch.object(server, "search_youtube", return_value={"entries": []}) as search:
            handler.do_POST()
        self.assertEqual(sent, [(200, {"entries": []})])
        search.assert_called_once_with("cats", 5)

    def test_invalid_search_payload_returns_400(self):
        handler, sent = make_handler("/search")
        with mock.patch.object(server.Handler, "read_json_body", return_value={}), \
             mock.patch.object(server, "search_youtube") as search:
            handler.do_POST()
        self.assertEqual(sent[0][0], 400)
        search.assert_not_called()


class DoPostErrorMappingTest(unittest.TestCase):
    """错误类型 → 状态码映射表。这层语义被 media_server 依赖:
    504 仅表示上游 yt-dlp 超时,408 仅表示客户端 body 慢发,502 带 reason。"""

    def _post_search_raising(self, error):
        handler, sent = make_handler("/search")
        with mock.patch.object(
            server.Handler, "read_json_body", return_value={"query": "q"}
        ), mock.patch.object(server, "search_youtube", side_effect=error):
            handler.do_POST()
        return handler, sent

    def test_upstream_timeout_returns_504(self):
        _, sent = self._post_search_raising(TimeoutError("timed out"))
        self.assertEqual(sent, [(504, {"error": "extractor timed out"})])

    def test_body_timeout_returns_408_and_closes_connection(self):
        handler, sent = self._post_search_raising(
            server.RequestBodyTimeoutError("request body read failed")
        )
        self.assertEqual(sent[0][0], 408)
        self.assertTrue(handler.close_connection)

    def test_value_error_returns_400(self):
        _, sent = self._post_search_raising(ValueError("query is required"))
        self.assertEqual(sent, [(400, {"error": "query is required"})])

    def test_busy_returns_503(self):
        _, sent = self._post_search_raising(server.ExtractorBusyError("extractor is busy"))
        self.assertEqual(sent, [(503, {"error": "extractor is busy"})])

    def test_config_error_returns_503_not_400(self):
        # 服务端自身配置问题(cookies 缺失)必须是 503,不能误报成客户端 400
        _, sent = self._post_search_raising(
            server.ExtractorConfigError("configured cookies file is missing or not readable")
        )
        self.assertEqual(sent[0][0], 503)

    def test_extractor_failed_returns_502_with_reason(self):
        _, sent = self._post_search_raising(
            server.ExtractorFailedError("bot_check", "Sign in to confirm you're not a bot")
        )
        status, payload = sent[0]
        self.assertEqual(status, 502)
        self.assertEqual(payload["reason"], "bot_check")

    def test_plain_runtime_error_returns_502_without_reason(self):
        _, sent = self._post_search_raising(RuntimeError("extractor returned invalid JSON"))
        status, payload = sent[0]
        self.assertEqual(status, 502)
        self.assertNotIn("reason", payload)

    def test_unexpected_exception_returns_500(self):
        _, sent = self._post_search_raising(KeyError("boom"))
        self.assertEqual(sent, [(500, {"error": "internal error"})])


class DoGetTest(unittest.TestCase):
    """/health 返回 cookies 与失败计数信号(外部监控依赖),其余 404。"""

    def _get(self, path):
        handler = server.Handler.__new__(server.Handler)
        handler.path = path
        handler.command = "GET"
        handler.client_address = ("127.0.0.1", 1)
        handler.close_connection = False
        sent = []
        handler.send_json = lambda status, payload: sent.append((status, payload))
        handler.do_GET()
        return sent

    def test_health_returns_ok_with_signals(self):
        sent = self._get("/health")
        self.assertEqual(len(sent), 1)
        status, payload = sent[0]
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertIn("cookies", payload)
        self.assertIn("failures", payload)
        self.assertIn("version", payload)

    def test_unknown_path_returns_404(self):
        sent = self._get("/extract")
        self.assertEqual(sent, [(404, {"error": "not found"})])


class LogJsonTest(unittest.TestCase):
    """log_json:/health 成功不记日志,错误响应带 error 字段且按级别分类。"""

    def _handler(self):
        handler = server.Handler.__new__(server.Handler)
        handler.path = "/extract"
        handler.command = "POST"
        handler.client_address = ("127.0.0.1", 1)
        handler.started_at = 0.0
        return handler

    def test_health_success_not_logged(self):
        handler = self._handler()
        handler.path = "/health"
        with mock.patch("builtins.print") as printed:
            handler.log_json(200, {"ok": True})
        printed.assert_not_called()

    def test_error_payload_logged_with_level(self):
        handler = self._handler()
        captured = []
        with mock.patch("builtins.print", side_effect=lambda *a, **k: captured.append(a[0])):
            handler.log_json(502, {"error": "boom"})
        self.assertEqual(len(captured), 1)
        import json as _json

        entry = _json.loads(captured[0])
        self.assertEqual(entry["level"], "error")
        self.assertEqual(entry["error"], "boom")
        self.assertEqual(entry["status"], 502)

    def test_4xx_logged_as_warn(self):
        handler = self._handler()
        captured = []
        with mock.patch("builtins.print", side_effect=lambda *a, **k: captured.append(a[0])):
            handler.log_json(400, {"error": "bad"})
        import json as _json

        self.assertEqual(_json.loads(captured[0])["level"], "warn")

    def test_health_error_is_logged(self):
        handler = self._handler()
        handler.path = "/health"
        captured = []
        with mock.patch("builtins.print", side_effect=lambda *a, **k: captured.append(a[0])):
            handler.log_json(500, {"error": "boom"})
        self.assertEqual(len(captured), 1)
