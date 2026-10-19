package app.smallthingz.alldoc.ui

import app.smallthingz.alldoc.state.CopySettings
import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsTest {
    @Test fun storageChoicesUseTheRequestedDiscreteLimits() {
        val megabytes = listOf<Long>(0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 768,
            1024, 1280, 1536, 1792, 2048, 2560, 3072, 3584, 4096)
        assertEquals(megabytes.map { it * 1024 * 1024 } + Long.MAX_VALUE, copyLimitOptions)
    }

    @Test fun selectingOffFiniteAndUnlimitedPreservesTheStorageLocation() {
        val previous = CopySettings(false, 768L * 1024 * 1024, 37, "content://storage/tree/docs")
        val off = copyLimitSelection(previous, 0)
        assertEquals(previous.copy(enabled = false, maxBytes = 0, maxCount = 1_000_000), off)
        val finite = copyLimitSelection(off, 10)
        assertEquals(previous.copy(enabled = true, maxBytes = 512L * 1024 * 1024, maxCount = 1_000_000), finite)
        val unlimited = copyLimitSelection(finite, copyLimitOptions.lastIndex)
        assertEquals(previous.copy(enabled = true, maxBytes = Long.MAX_VALUE, maxCount = 1_000_000), unlimited)
        assertEquals("Off", copyLimitLabel(off.maxBytes))
        assertEquals("512 MB", copyLimitLabel(finite.maxBytes))
        assertEquals("Unlimited", copyLimitLabel(unlimited.maxBytes))
        assertEquals(0, copyLimitIndex(off))
        assertEquals(10, copyLimitIndex(finite))
        assertEquals(21, copyLimitIndex(unlimited))
    }

    @Test fun legacyLimitKeepsItsActualLabelUntilAnExplicitSelection() {
        val previous = CopySettings(true, 600L * 1024 * 1024, 37)
        assertEquals(10, copyLimitIndex(previous))
        assertEquals("600 MB", copyLimitLabel(previous.maxBytes))
        assertEquals(37, previous.maxCount)
        assertEquals(0, copyLimitIndex(previous.copy(enabled = false)))
    }
}
