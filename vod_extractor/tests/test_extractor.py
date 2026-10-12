# run_extractor / stop_process 测试:子进程编排的失败分类与清理语义。
#
# 这层是解析服务最核心的健壮性逻辑,此前完全未测:超时、输出超限、非零退出
# 都要 record_failure 并映射成正确的异常类型,供 /health 的失败计数告警
# (bot_check 持续增长 = cookies 失效)与 handler 的状态码映射使用。
#
# 不启动真实 yt-dlp:subprocess.Popen 与 read_limited_process_output 被 mock,
# 只验证编排决策与失败记录,不做进程级集成。

import os
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server  # noqa: E402


class _FakeProcess:
    """最小 Popen 替身:只需 poll/returncode/pid。"""

    def __init__(self, returncode=0, pid=4321):
        self.returncode = returncode
        self.pid = pid
        self.killed = False

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.killed = True


class RunExtractorTest(unittest.TestCase):
    """run_extractor 的失败分类记录:每次失败都要落进 FAILURE_STATS,
    /health 的 failures 计数与告警依赖它。"""

    def setUp(self):
        server.FAILURE_STATS.update(
            {"total": 0, "last_reason": None, "last_at": None, "counts": {}}
        )
        self.addCleanup(
            lambda: server.FAILURE_STATS.update(
                {"total": 0, "last_reason": None, "last_at": None, "counts": {}}
            )
        )

    def _run(self, process, output=None, error=None):
        with mock.patch.object(server.subprocess, "Popen", return_value=process), \
             mock.patch.object(
                 server,
                 "read_limited_process_output",
                 side_effect=error if error else None,
                 return_value=output,
             ):
            return server.run_extractor("https://www.youtube.com/watch?v=x")

    def test_success_returns_parsed_json(self):
        result = self._run(_FakeProcess(0), output=(b'{"title": "ok"}', b""))
        self.assertEqual(result, {"title": "ok"})
        self.assertEqual(server.FAILURE_STATS["total"], 0)

    def test_timeout_records_timeout_and_reraises(self):
        with self.assertRaises(TimeoutError):
            self._run(_FakeProcess(0), error=TimeoutError("extractor timed out"))
        self.assertEqual(server.FAILURE_STATS["counts"].get("timeout"), 1)

    def test_output_too_large_records_and_reraises_runtime_error(self):
        with self.assertRaises(RuntimeError):
            self._run(_FakeProcess(0), error=RuntimeError("extractor output is too large"))
        self.assertEqual(server.FAILURE_STATS["counts"].get("output_too_large"), 1)

    def test_nonzero_exit_records_classified_reason(self):
        process = _FakeProcess(1)
        with self.assertRaises(server.ExtractorFailedError) as ctx:
            self._run(
                process,
                output=(b"", b"ERROR: Sign in to confirm you're not a bot."),
            )
        self.assertEqual(ctx.exception.reason, "bot_check")
        self.assertEqual(server.FAILURE_STATS["counts"].get("bot_check"), 1)

    def test_nonzero_exit_unknown_stderr_classified_unknown(self):
        with self.assertRaises(server.ExtractorFailedError) as ctx:
            self._run(_FakeProcess(2), output=(b"", b"some other failure"))
        self.assertEqual(ctx.exception.reason, "unknown")

    def test_nonzero_exit_with_empty_stderr_still_classified(self):
        with self.assertRaises(server.ExtractorFailedError) as ctx:
            self._run(_FakeProcess(1), output=(b"", b""))
        self.assertEqual(ctx.exception.reason, "unknown")

    def test_invalid_json_records_and_raises_runtime_error(self):
        with self.assertRaises(RuntimeError) as ctx:
            self._run(_FakeProcess(0), output=(b"not json at all", b""))
        self.assertIn("invalid JSON", str(ctx.exception))
        self.assertEqual(server.FAILURE_STATS["counts"].get("invalid_json"), 1)

    def test_flat_playlist_flag_for_search(self):
        # 搜索走 --flat-playlist(只抓列表页),单视频走 --no-playlist
        captured = {}

        def _capture(command, **kwargs):
            captured["command"] = command
            return _FakeProcess(0)

        with mock.patch.object(server.subprocess, "Popen", side_effect=_capture), \
             mock.patch.object(
                 server, "read_limited_process_output", return_value=(b"{}", b"")
             ):
            server.run_extractor("ytsearch5:cats", single_video=False)
        self.assertIn("--flat-playlist", captured["command"])
        self.assertNotIn("--no-playlist", captured["command"])

    def test_single_video_flag_for_extract(self):
        captured = {}

        def _capture(command, **kwargs):
            captured["command"] = command
            return _FakeProcess(0)

        with mock.patch.object(server.subprocess, "Popen", side_effect=_capture), \
             mock.patch.object(
                 server, "read_limited_process_output", return_value=(b"{}", b"")
             ):
            server.run_extractor("https://www.youtube.com/watch?v=x", single_video=True)
        self.assertIn("--no-playlist", captured["command"])
        self.assertNotIn("--flat-playlist", captured["command"])

    def test_process_started_in_own_session(self):
        # start_new_session=True 是 killpg 收掉 ffmpeg 等子进程的前提
        captured = {}

        def _capture(command, **kwargs):
            captured.update(kwargs)
            return _FakeProcess(0)

        with mock.patch.object(server.subprocess, "Popen", side_effect=_capture), \
             mock.patch.object(
                 server, "read_limited_process_output", return_value=(b"{}", b"")
             ):
            server.run_extractor("https://www.youtube.com/watch?v=x")
        self.assertTrue(captured.get("start_new_session"))


class StopProcessTest(unittest.TestCase):
    """stop_process 必须杀整个进程组(收掉 ffmpeg 孤儿),组失效时退回 kill。"""

    def test_kills_process_group(self):
        process = _FakeProcess(0, pid=9999)
        process.poll = lambda: None  # 仍在运行
        with mock.patch.object(server.os, "getpgid", return_value=9999) as getpgid, \
             mock.patch.object(server.os, "killpg") as killpg:
            server.stop_process(process)
        getpgid.assert_called_once_with(9999)
        killpg.assert_called_once()
        self.assertEqual(killpg.call_args[0][1], server.signal.SIGKILL)

    def test_falls_back_to_kill_when_group_gone(self):
        process = _FakeProcess(0, pid=8888)
        process.poll = lambda: None
        with mock.patch.object(server.os, "getpgid", side_effect=ProcessLookupError()), \
             mock.patch.object(server.os, "killpg") as killpg:
            server.stop_process(process)
        killpg.assert_not_called()
        self.assertTrue(process.killed)

    def test_falls_back_to_kill_on_permission_error(self):
        process = _FakeProcess(0, pid=7777)
        process.poll = lambda: None
        with mock.patch.object(server.os, "getpgid", side_effect=PermissionError()), \
             mock.patch.object(server.os, "killpg") as killpg:
            server.stop_process(process)
        killpg.assert_not_called()
        self.assertTrue(process.killed)

    def test_already_exited_process_not_killed(self):
        process = _FakeProcess(0, pid=6666)
        with mock.patch.object(server.os, "killpg") as killpg:
            server.stop_process(process)
        killpg.assert_not_called()
        self.assertFalse(process.killed)

    def test_hung_process_after_kill_is_logged(self):
        process = _FakeProcess(0, pid=5555)
        process.poll = lambda: None
        process.wait = mock.Mock(side_effect=subprocess.TimeoutExpired("x", 5))
        with mock.patch.object(server.os, "getpgid", return_value=5555), \
             mock.patch.object(server.os, "killpg"), \
             mock.patch.object(server, "log_internal") as logged:
            server.stop_process(process)
        logged.assert_called_once()
        self.assertIn("did not exit after kill", logged.call_args[0][0])


class ExtractorCommandTest2(unittest.TestCase):
    """extractor_command 的参数拼装:必须用参数数组(不经过 shell),
    cookies 与 extractor-args 仅在配置存在时附加。"""

    def test_base_command_uses_argv_not_shell(self):
        with mock.patch.object(server, "EXTRACTOR_ARGS", ""), \
             mock.patch.object(server, "extractor_cookies_file", return_value=None):
            command = server.extractor_command("https://www.youtube.com/watch?v=x")
        self.assertIsInstance(command, list)
        self.assertEqual(command[0], server.EXTRACTOR_BIN)
        self.assertIn("--dump-single-json", command)
        self.assertEqual(command[-1], "https://www.youtube.com/watch?v=x")

    def test_extractor_args_appended_when_configured(self):
        with mock.patch.object(server, "EXTRACTOR_ARGS", "youtube:player_client=tv"), \
             mock.patch.object(server, "extractor_cookies_file", return_value=None):
            command = server.extractor_command("https://www.youtube.com/watch?v=x")
        index = command.index("--extractor-args")
        self.assertEqual(command[index + 1], "youtube:player_client=tv")

    def test_cookies_appended_when_available(self):
        with mock.patch.object(server, "EXTRACTOR_ARGS", ""), \
             mock.patch.object(
                 server, "extractor_cookies_file", return_value="/config/youtube-cookies.txt"
             ):
            command = server.extractor_command("https://www.youtube.com/watch?v=x")
        index = command.index("--cookies")
        self.assertEqual(command[index + 1], "/config/youtube-cookies.txt")

    def test_no_cookies_flag_when_unavailable(self):
        with mock.patch.object(server, "EXTRACTOR_ARGS", ""), \
             mock.patch.object(server, "extractor_cookies_file", return_value=None):
            command = server.extractor_command("https://www.youtube.com/watch?v=x")
        self.assertNotIn("--cookies", command)


class ExtractVodBusyTest(unittest.TestCase):
    """并发上限:信号量已被占用时必须抛 ExtractorBusyError(→503),
    不能排队等待(否则请求线程会被 yt-dlp 时长拖住)。"""

    def test_extract_vod_busy_when_semaphore_taken(self):
        with mock.patch.object(
            server.EXTRACTOR_SEMAPHORE, "acquire", return_value=False
        ), mock.patch.object(server, "run_extractor") as run:
            with self.assertRaises(server.ExtractorBusyError):
                server.extract_vod("https://www.youtube.com/watch?v=x")
        run.assert_not_called()

    def test_search_youtube_busy_when_semaphore_taken(self):
        with mock.patch.object(
            server.EXTRACTOR_SEMAPHORE, "acquire", return_value=False
        ), mock.patch.object(server, "run_extractor") as run:
            with self.assertRaises(server.ExtractorBusyError):
                server.search_youtube("cats", 5)
        run.assert_not_called()

    def test_semaphore_released_on_failure(self):
        # 失败路径必须释放信号量,否则一次超时会让并发额度永久少一个
        released = []
        with mock.patch.object(server.EXTRACTOR_SEMAPHORE, "acquire", return_value=True), \
             mock.patch.object(
                 server.EXTRACTOR_SEMAPHORE, "release", side_effect=lambda: released.append(1)
             ), mock.patch.object(
                 server, "run_extractor", side_effect=RuntimeError("boom")
             ):
            with self.assertRaises(RuntimeError):
                server.extract_vod("https://www.youtube.com/watch?v=x")
        self.assertEqual(len(released), 1)
