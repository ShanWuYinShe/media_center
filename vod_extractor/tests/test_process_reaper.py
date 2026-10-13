# read_limited_process_output / run_extractor 真实子进程测试。
#
# 此前这层只有 mock 测试(test_extractor.py):编排决策被覆盖,但 selector
# 轮询循环本身(非阻塞读、双流复用、超时杀组、输出上限)从未真实执行过。
# 这里用 sys.executable 派生真实子进程验证:
#   - 超时:整组被 SIGKILL,不留孤儿(失控进程防线);
#   - stdout 超限:立即杀灭并抛 RuntimeError;
#   - stderr 截断:超上限部分丢弃,不影响正常退出;
#   - run_extractor 端到端:真实 stderr 文案(U+2019 撇号)→ bot_check 分类。
#
# TIMEOUT_SECONDS / MAX_OUTPUT_BYTES / MAX_ERROR_BYTES 在函数调用时读取
# 模块全局,测试直接改 server 模块属性即可生效,无需环境变量。

import os
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server  # noqa: E402


def spawn(code):
    """派生一个独立进程组的真实子进程(与 yt-dlp 同参数形状)。"""
    return subprocess.Popen(
        [sys.executable, "-c", code],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )


class ReadLimitedProcessOutputTest(unittest.TestCase):
    def tearDown(self):
        # 防御:任何用例失败都不允许留下活着的子进程
        for proc in getattr(self, "_procs", []):
            if proc.poll() is None:
                proc.kill()

    def _track(self, proc):
        self._procs = getattr(self, "_procs", []) + [proc]
        return proc

    def test_normal_output_captures_stdout_and_stderr(self):
        proc = self._track(spawn("print('hello'); print('oops', file=__import__('sys').stderr)"))
        out, err = server.read_limited_process_output(proc)
        self.assertEqual(out, b"hello\n")
        self.assertIn(b"oops", err)
        self.assertEqual(proc.wait(timeout=5), 0)

    def test_timeout_kills_whole_process_group(self):
        original = server.TIMEOUT_SECONDS
        server.TIMEOUT_SECONDS = 1
        try:
            proc = self._track(spawn("import time; print('start', flush=True); time.sleep(60)"))
            with self.assertRaises(TimeoutError):
                server.read_limited_process_output(proc)
            # stop_process 内部已 wait:子进程必须真的死了,不能泄漏孤儿
            self.assertLess(proc.returncode, 0, "子进程应被信号杀死(SIGKILL),而非自然退出")
        finally:
            server.TIMEOUT_SECONDS = original

    def test_output_over_limit_kills_and_raises(self):
        original = server.MAX_OUTPUT_BYTES
        server.MAX_OUTPUT_BYTES = 1024
        try:
            proc = self._track(spawn("import sys; sys.stdout.write('x' * 100000)"))
            with self.assertRaises(RuntimeError) as ctx:
                server.read_limited_process_output(proc)
            self.assertIn("too large", str(ctx.exception))
            self.assertLess(proc.returncode, 0, "超限进程必须被杀灭,防止继续产出")
        finally:
            server.MAX_OUTPUT_BYTES = original

    def test_stderr_over_cap_is_truncated_not_fatal(self):
        original = server.MAX_ERROR_BYTES
        server.MAX_ERROR_BYTES = 1024
        try:
            proc = self._track(spawn("import sys; sys.stderr.write('e' * 100000)"))
            out, err = server.read_limited_process_output(proc)
            self.assertEqual(out, b"")
            self.assertLessEqual(len(err), 1024, "stderr 只截断保存,不触发失败")
            self.assertEqual(proc.wait(timeout=5), 0)
        finally:
            server.MAX_ERROR_BYTES = original

    def test_pipe_fds_closed_after_return(self):
        # 异常路径(超时/超限)也要关闭管道 fd,不依赖 Popen 的 GC 回收
        proc = self._track(spawn("import time; time.sleep(60)"))
        server.TIMEOUT_SECONDS = 1
        try:
            with self.assertRaises(TimeoutError):
                server.read_limited_process_output(proc)
            with self.assertRaises(ValueError):
                # fd 已关闭:再读应报错,而非静默挂着
                proc.stdout.fileno()
        finally:
            server.TIMEOUT_SECONDS = 30


class RunExtractorRealSubprocessTest(unittest.TestCase):
    """run_extractor 真实子进程端到端(仅替换命令构造,其余全真)。"""

    def setUp(self):
        server.FAILURE_STATS.update(
            {"total": 0, "last_reason": None, "last_at": None, "counts": {}}
        )
        self.addCleanup(
            lambda: server.FAILURE_STATS.update(
                {"total": 0, "last_reason": None, "last_at": None, "counts": {}}
            )
        )

    def _run_with_code(self, code):
        def fake_command(target, single_video=True):
            return [sys.executable, "-c", code]

        with mock.patch.object(server, "extractor_command", side_effect=fake_command):
            return server.run_extractor("https://www.youtube.com/watch?v=x")

    def test_success_parses_real_json_output(self):
        result = self._run_with_code("print('{\"title\": \"ok\", \"id\": \"x\"}')")
        self.assertEqual(result, {"title": "ok", "id": "x"})
        self.assertEqual(server.FAILURE_STATS["total"], 0)

    def test_unicode_apostrophe_stderr_classified_as_bot_check(self):
        # 用真实子进程复现线上风控文案(yt-dlp 输出 U+2019 撇号),
        # 端到端验证分类修复——mock 测试只覆盖了 classify_failure 本身。
        code = (
            "import sys; sys.stderr.write("
            "'ERROR: [youtube] abc: Sign in to confirm you\u2019re not a bot.'); sys.exit(1)"
        )
        with self.assertRaises(server.ExtractorFailedError) as ctx:
            self._run_with_code(code)
        self.assertEqual(ctx.exception.reason, "bot_check")
        self.assertEqual(server.FAILURE_STATS["counts"].get("bot_check"), 1)

    def test_nonzero_exit_without_stderr_still_raises(self):
        code = "import sys; sys.exit(3)"
        with self.assertRaises(server.ExtractorFailedError) as ctx:
            self._run_with_code(code)
        self.assertEqual(ctx.exception.reason, "unknown")
