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
import sys
import unittest
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


if __name__ == "__main__":
    unittest.main()


if __name__ == "__main__":
    unittest.main()
