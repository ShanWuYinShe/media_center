# vod_extractor /health 运行态指标单测(纯标准库 unittest)。
#
# 覆盖:失败滚动窗口只保留窗口内事件、active 槽位计数在 acquire/release
# 前后自洽(排队超时不会漏减 waiting)、successes 累计。

import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server  # noqa: E402


class FailureWindowTest(unittest.TestCase):
    def setUp(self):
        with server.FAILURE_LOCK:
            server.FAILURE_EVENTS.clear()

    def test_prunes_events_outside_window(self):
        now = time.monotonic()
        with server.FAILURE_LOCK:
            server.FAILURE_EVENTS.append((now - 600, "bot_check"))
            server.FAILURE_EVENTS.append((now, "timeout"))
        snapshot = server.failure_window_snapshot(300)
        self.assertEqual({"timeout": 1}, snapshot["counts"])
        self.assertEqual(1, snapshot["total"])
        self.assertEqual(300, snapshot["window_seconds"])

    def test_groups_recent_by_reason(self):
        now = time.monotonic()
        with server.FAILURE_LOCK:
            server.FAILURE_EVENTS.extend(
                [(now, "bot_check"), (now, "bot_check"), (now, "rate_limited")]
            )
        snapshot = server.failure_window_snapshot(300)
        self.assertEqual({"bot_check": 2, "rate_limited": 1}, snapshot["counts"])
        self.assertEqual(3, snapshot["total"])


class ActiveSlotTest(unittest.TestCase):
    def setUp(self):
        with server.ACTIVE_LOCK:
            server.ACTIVE_STATS["in_flight"] = 0
            server.ACTIVE_STATS["waiting"] = 0
        self._original_queue_timeout = server.QUEUE_TIMEOUT_SECONDS

    def tearDown(self):
        server.QUEUE_TIMEOUT_SECONDS = self._original_queue_timeout

    def test_acquire_release_keeps_counters_consistent(self):
        server.QUEUE_TIMEOUT_SECONDS = 0
        server.acquire_extractor_slot()
        server.acquire_extractor_slot()
        snapshot = server.active_snapshot()
        self.assertEqual(2, snapshot["in_flight"])
        self.assertEqual(0, snapshot["waiting"])
        # 第三台电视在 2 个槽位都被占用时 busy(不阻塞),waiting 必须归零。
        with self.assertRaises(server.ExtractorBusyError):
            server.acquire_extractor_slot()
        self.assertEqual(0, server.active_snapshot()["waiting"])
        self.assertEqual(2, server.active_snapshot()["in_flight"])
        server.release_extractor_slot()
        server.release_extractor_slot()
        snapshot = server.active_snapshot()
        self.assertEqual(0, snapshot["in_flight"])


class SuccessSnapshotTest(unittest.TestCase):
    def test_record_success_updates_total_and_last_at(self):
        with server.SUCCESS_LOCK:
            server.SUCCESS_STATS["total"] = 0
            server.SUCCESS_STATS["last_at"] = None
        server.record_success()
        snapshot = server.success_snapshot()
        self.assertEqual(1, snapshot["total"])
        self.assertIsNotNone(snapshot["last_at"])


if __name__ == "__main__":
    unittest.main()
