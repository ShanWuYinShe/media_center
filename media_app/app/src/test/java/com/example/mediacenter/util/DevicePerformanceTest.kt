package com.example.mediacenter.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePerformanceTest {
    private val oneGb = 1024L * 1024 * 1024
    private val twoGb = 2 * oneGb

    @Test
    fun `系统低内存标记直接判低端`() {
        assertTrue(DevicePerformance.isLowEndDevice(isLowRamDevice = true, totalMemBytes = 4 * oneGb))
    }

    @Test
    fun `总内存小于 2GB 判低端`() {
        assertTrue(DevicePerformance.isLowEndDevice(isLowRamDevice = false, totalMemBytes = oneGb + oneGb / 2))
    }

    @Test
    fun `2GB 及以上不是低端`() {
        assertFalse(DevicePerformance.isLowEndDevice(isLowRamDevice = false, totalMemBytes = twoGb))
        assertFalse(DevicePerformance.isLowEndDevice(isLowRamDevice = false, totalMemBytes = 4 * oneGb))
    }

    @Test
    fun `内存信息读取失败(totalMem=0)不判低端`() {
        assertFalse(DevicePerformance.isLowEndDevice(isLowRamDevice = false, totalMemBytes = 0))
    }
}
