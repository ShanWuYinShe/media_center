package com.example.mediacenter.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFailureMessageTest {
    @Test
    fun `常见失败状态映射为可读中文原因`() {
        val blocked = installFailureMessage(PackageInstaller.STATUS_FAILURE_BLOCKED, null)
        assertTrue(blocked, blocked.startsWith("安装被系统拦截"))

        val storage = installFailureMessage(PackageInstaller.STATUS_FAILURE_STORAGE, null)
        assertTrue(storage, storage.startsWith("存储空间不足"))

        val incompatible = installFailureMessage(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null)
        assertTrue(incompatible, incompatible.startsWith("与当前系统不兼容"))

        val aborted = installFailureMessage(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertTrue(aborted, aborted.startsWith("安装被取消"))
    }

    @Test
    fun `无系统原文时附上 status 便于反馈`() {
        val message = installFailureMessage(PackageInstaller.STATUS_FAILURE, null)
        assertEquals("安装失败（status=1）", message)
        assertEquals("安装失败（status=1）", installFailureMessage(PackageInstaller.STATUS_FAILURE, "   "))
    }

    @Test
    fun `保留系统原文用于定位,过长时截断`() {
        val short = installFailureMessage(
            PackageInstaller.STATUS_FAILURE,
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match",
        )
        assertTrue(short, short.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(short, short.endsWith("）"))

        val longRaw = "X".repeat(200)
        val long = installFailureMessage(PackageInstaller.STATUS_FAILURE_BLOCKED, longRaw)
        assertTrue(long, long.contains("安装被系统拦截"))
        // 截断到 120 字符 + 省略号,避免长原文把弹窗撑爆
        assertTrue(long, long.contains("X".repeat(120) + "…"))
        assertTrue(long, !long.contains("X".repeat(121)))
    }
}
