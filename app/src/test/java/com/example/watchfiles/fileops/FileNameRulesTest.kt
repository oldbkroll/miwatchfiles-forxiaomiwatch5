package com.example.watchfiles.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameRulesTest {
    @Test
    fun acceptsOrdinaryChineseAndExtensionNames() {
        assertEquals(FileNameValidation.Valid, FileNameRules.validate("新文件夹"))
        assertEquals(FileNameValidation.Valid, FileNameRules.validate("photo 01.jpg"))
    }

    @Test
    fun rejectsEmptyReservedAndSeparatorNames() {
        val invalid = listOf("", ".", "..", "a/b", "a\\b", "a\u0000b")
        invalid.forEach { name ->
            assertTrue(
                "Expected invalid: $name",
                FileNameRules.validate(name) is FileNameValidation.Invalid,
            )
        }
    }

    @Test
    fun rejectsLeadingOrTrailingWhitespaceWithoutTrimming() {
        assertTrue(FileNameRules.validate(" folder") is FileNameValidation.Invalid)
        assertTrue(FileNameRules.validate("folder ") is FileNameValidation.Invalid)
        assertTrue(FileNameRules.validate("\tfolder") is FileNameValidation.Invalid)
    }
}
