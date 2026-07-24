package com.example.watchfiles.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeOperationWarningTest {
    @Test
    fun detectsLargeOperationByItemThresholdAtInclusiveBoundary() {
        assertFalse(isLargeOperation(itemCount = 99, totalBytes = null))
        assertTrue(isLargeOperation(itemCount = 100, totalBytes = null))
    }

    @Test
    fun detectsLargeOperationBySizeThresholdAtInclusiveBoundary() {
        assertFalse(isLargeOperation(itemCount = 1, totalBytes = 50L * 1024L * 1024L - 1L))
        assertTrue(isLargeOperation(itemCount = 1, totalBytes = 50L * 1024L * 1024L))
    }

    @Test
    fun formatsUnknownSizeWithoutCallingFormatterOrFallingBackToZero() {
        val formatted = formatLargeOperationScale(itemCount = 7, totalBytes = null) {
            throw AssertionError("formatBytes should not be called for unknown sizes")
        }

        assertEquals("共 7 项 · 大小未知", formatted)
    }

    @Test
    fun formatsKnownSizeUsingProvidedFormatter() {
        val formatted = formatLargeOperationScale(itemCount = 101, totalBytes = 1234L) { bytes ->
            "formatted-$bytes"
        }

        assertEquals("共 101 项 · 总计 formatted-1234", formatted)
    }

    @Test
    fun exposesStableTitleAndRiskMessage() {
        assertEquals("文件较多", LARGE_OPERATION_WARNING_TITLE)
        assertEquals(
            "建议尽量保持手表亮屏。熄屏或系统调度可能导致操作中断或失败。",
            LARGE_OPERATION_WARNING_MESSAGE,
        )
    }
}
