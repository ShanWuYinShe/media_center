package com.example.mediacenter.update

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateHolderTest {
    @Test
    fun uninitializedHolder_exposesInitialStateFlowsWithoutCrashing() {
        // 验证:在未注入 Context 的纯 JVM 单测环境下,读取 updateState 与
        // pendingConfirmIntent 不应触发 manager 的 eager 求值,更不能抛出
        // IllegalArgumentException(曾因 holder init 块 eager collect manager 导致单测挂死)。
        assertNull(AppUpdateHolder.updateState.value)
        assertNull(AppUpdateHolder.pendingConfirmIntent.value)

        AppUpdateHolder.consumePendingConfirmIntent()
        assertNull(AppUpdateHolder.pendingConfirmIntent.value)
    }

    @Test
    fun uninitializedHolder_managerThrowsClearExceptionWhenAccessedDirectly() {
        val error = runCatching { AppUpdateHolder.manager }.exceptionOrNull()
        // 尚未调用 init(context) 时直接访问 manager 必须抛出明确提示,而不是 NPE
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
    }
}
