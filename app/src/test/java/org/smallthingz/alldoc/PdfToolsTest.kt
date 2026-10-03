package org.smallthingz.alldoc

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfToolsTest {
    private fun encryptionMarker(content: String) =
        ByteArrayInputStream(content.toByteArray(Charsets.ISO_8859_1)).use {
            PdfTools.formRestrictionMarker(it) == PdfFormRestriction.ENCRYPTION
        }

    @Test
    fun realRestrictedFixtureIsRefusedWithoutPdfParsing() {
        assertNull(
            File("../fixtures/fillable-form.pdf").inputStream().use {
                PdfTools.formRestrictionMarker(it)
            }
        )
        assertEquals(
            PdfFormRestriction.ENCRYPTION,
            File("../fixtures/restricted-empty-password-form.pdf").inputStream().use {
                PdfTools.formRestrictionMarker(it)
            },
        )
        assertEquals(
            PdfFormRestriction.SIGNATURE,
            File("../fixtures/signature-field-form.pdf").inputStream().use {
                PdfTools.formRestrictionMarker(it)
            },
        )
    }

    @Test
    fun encryptionMarkerRecognizesTrailerAndCrossReferenceDictionaryNames() {
        assertTrue(encryptionMarker("trailer << /Root 1 0 R /Encrypt 9 0 R >>"))
        assertTrue(encryptionMarker("12 0 obj << /Type /XRef /Encrypt<</Filter/Standard>> >>"))
        assertTrue(encryptionMarker("/Encrypt"))
        assertTrue(encryptionMarker("/Encrypt\u0000"))
    }

    @Test
    fun encryptionNameHexEscapesAreDecodedCaseInsensitively() {
        assertTrue(encryptionMarker("/En#63rypt 9 0 R"))
        assertTrue(encryptionMarker("/#45#6E#63#72#79#70#74 9 0 R"))
        assertTrue(encryptionMarker("/#45#6e#63#72#79#70#74 9 0 R"))
        assertFalse(encryptionMarker("/encrypt 9 0 R"))
    }

    @Test
    fun encryptionMarkerMatchesWholeNamesRatherThanPrefixes() {
        listOf(
                "/Encryption",
                "/EncryptMetadata false",
                "/Encrypt#20",
                "/Encryptx",
                "/NotEncrypt",
                "Encrypt",
                "/En#6rypt",
            )
            .forEach { assertFalse(it, encryptionMarker(it)) }
    }

    @Test
    fun encryptionFilterIntentionallyRefusesMarkersInCommentsStringsAndOldRevisions() {
        assertTrue(encryptionMarker("% unrelated comment /Encrypt\ntrailer << /Root 1 0 R >>"))
        assertTrue(encryptionMarker("(This text mentions /En#63rypt)"))
        assertTrue(encryptionMarker("stream\n/Encrypt\nendstream"))
        assertTrue(encryptionMarker("trailer << /Encrypt 5 0 R >>\n%%EOF\ntrailer << /Prev 12 >>"))
    }

    @Test
    fun encryptionEscapesCanCrossReadBufferBoundaries() {
        assertTrue(encryptionMarker(" ".repeat(8188) + "/En#63rypt 8 0 R"))
        assertTrue(encryptionMarker(" ".repeat(8191) + "/Encrypt 8 0 R"))
    }

    @Test
    fun encryptionInspectionPropagatesReadFailuresAndRejectsOversizedInput() {
        val failing =
            object : InputStream() {
                override fun read(): Int = throw IOException("synthetic read failure")
            }
        assertThrows(IOException::class.java) { PdfTools.formRestrictionMarker(failing) }
        val oversized =
            object : InputStream() {
                private var remaining = PdfTools.MAX_FILE_BYTES + 1

                override fun read(): Int = if (remaining-- > 0) 32 else -1

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    bytes.fill(32, offset, offset + count)
                    remaining -= count
                    return count
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.formRestrictionMarker(oversized)
        }
    }

    @Test
    fun signatureNamesAndEscapesAreRefusedWithoutNeedingPageWidgets() {
        listOf(
                "<</Type/Sig>>",
                "<</ByteRange [0 120 300 40]>>",
                "/Type/DocTimeStamp",
                "/#53ig",
                "/Byte#52ange",
                "/DocTime#53tamp",
            )
            .forEach { content ->
                assertEquals(
                    content,
                    PdfFormRestriction.SIGNATURE,
                    ByteArrayInputStream(content.toByteArray()).use {
                        PdfTools.formRestrictionMarker(it)
                    },
                )
            }
    }

    @Test
    fun signatureRefusalUsesExactNamesAndAllowsHarmlessPrefixes() {
        listOf("/Signature", "/SigFlags", "/ByteRanges", "/DocTimeStamps", "/sig", "/Sig#20")
            .forEach { content ->
                assertNull(
                    content,
                    ByteArrayInputStream(content.toByteArray()).use {
                        PdfTools.formRestrictionMarker(it)
                    },
                )
            }
    }

    @Test
    fun signatureMarkersAreConservativeAcrossCommentsOldRevisionsAndBufferBoundaries() {
        listOf(
                "% comment /Sig\n",
                "(Signature-like marker /ByteRange)",
                " ".repeat(8187) + "/Doc#54imeStamp ",
                "/Sig %%EOF trailer << /Prev 1 >>",
            )
            .forEach { content ->
                assertEquals(
                    PdfFormRestriction.SIGNATURE,
                    ByteArrayInputStream(content.toByteArray()).use {
                        PdfTools.formRestrictionMarker(it)
                    },
                )
            }
        assertTrue(PdfTools.SIGNATURE_FORM_MESSAGE.contains("signature-like marker"))
    }

    @Test
    fun formEditingIsExplicitlyGatedAtAndroid15() {
        assertFalse(PdfTools.supportsFormEditing(26))
        assertFalse(PdfTools.supportsFormEditing(34))
        assertTrue(PdfTools.supportsFormEditing(35))
        assertTrue(PdfTools.supportsFormEditing(37))
    }

    @Test
    fun editsRoundTripUnicodeQuotesAndEmptyValuesWithoutChangingTheirMeaning() {
        val edits =
            mapOf(
                "pdf:0:3" to "A & B < C \"quoted\" — café",
                "pdf:2:1" to "",
                "pdf:2:5" to "one\ntwo",
            )
        assertEquals(edits, PdfTools.decodeEdits(PdfTools.encodeEdits(edits)))
        assertEquals(
            emptyMap<String, String>(),
            PdfTools.decodeEdits(PdfTools.encodeEdits(emptyMap())),
        )
    }

    @Test
    fun formMetadataRoundTripsWithStableRecoveryKeys() {
        val field = PdfFormField(7, 9, "Full name", "Ada", 50, false)
        val page = PdfFormPage(listOf(field), "Only text fields are supported")
        assertEquals("pdf:7:9", field.id)
        assertEquals(page, PdfTools.decodeFields(PdfTools.encodeFields(page)))
        assertEquals(
            PdfFormPage(emptyList()),
            PdfTools.decodeFields(PdfTools.encodeFields(PdfFormPage(emptyList()))),
        )
    }

    @Test
    fun editsRejectInvalidOrAmbiguousIds() {
        listOf(
                "b0",
                "pdf:-1:0",
                "pdf:500:0",
                "pdf:0:-1",
                "pdf:00:1",
                "pdf:0:01",
                "pdf:0:1:2",
                "pdf:1:2147483648",
            )
            .forEach { id ->
                assertThrows("Accepted $id", Exception::class.java) {
                    PdfTools.encodeEdits(mapOf(id to "x"))
                }
            }
    }

    @Test
    fun duplicateAndNonStringEditsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeEdits(
                "[{\"id\":\"pdf:0:0\",\"value\":\"a\"},{\"id\":\"pdf:0:0\",\"value\":\"b\"}]"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeEdits("[{\"id\":\"pdf:0:0\",\"value\":123}]")
        }
    }

    @Test
    fun excessiveTextAndEditCountsAreRejectedBeforeNativePdfCalls() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.encodeEdits(mapOf("pdf:0:0" to "a".repeat(PdfTools.MAX_FIELD_CHARACTERS + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.encodeEdits((0..PdfTools.MAX_EDITS).associate { "pdf:0:$it" to "" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.encodeEdits(
                (0..16).associate { "pdf:0:$it" to "a".repeat(PdfTools.MAX_FIELD_CHARACTERS) }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeEdits(" ".repeat(PdfTools.MAX_PAYLOAD_CHARACTERS + 1))
        }
    }

    @Test
    fun fieldLimitsAreEnforcedWithoutSilentlyTruncatingText() {
        val field = PdfFormField(0, 0, "Name", "", 3, false)
        PdfTools.validateValue(field, "Ada")
        PdfTools.validateValue(field, "")
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.validateValue(field, "Lovelace")
        }
        assertThrows(IllegalArgumentException::class.java) { PdfTools.validateValue(field, "A\nB") }
        assertThrows(IllegalArgumentException::class.java) { PdfTools.validateValue(field, "A\rB") }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.validateValue(field, "A\u0000B")
        }
        PdfTools.validateValue(field.copy(multiline = true), "A\nB")
    }

    @Test
    fun maliciousNulAndOversizedEncodedPayloadAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.encodeEdits(mapOf("pdf:0:0" to "a\u0000b"))
        }
        // Control characters JSON-escape to six characters each. The wire budget must still apply,
        // even though the unencoded draft fits inside the independent edit-character budget.
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.encodeEdits(
                (0..15).associate { "pdf:0:$it" to "\u0001".repeat(PdfTools.MAX_FIELD_CHARACTERS) }
            )
        }
    }

    @Test
    fun tooManyFieldsAndOversizedServiceMessagesAreRejected() {
        val field = PdfFormField(0, 0, "Name", "", 3, false)
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.encodeFields(
                PdfFormPage((0..PdfTools.MAX_FIELDS_PER_PAGE).map { field.copy(index = it) })
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeFields(PdfTools.encodeFields(PdfFormPage(emptyList(), "x".repeat(1025))))
        }
    }

    @Test
    fun badFieldMetadataAndDuplicateFieldsAreRejected() {
        val field = PdfFormField(0, 0, "Name", "", 3, false)
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeFields(PdfTools.encodeFields(PdfFormPage(listOf(field, field))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeFields(
                PdfTools.encodeFields(PdfFormPage(listOf(field.copy(maxLength = -1))))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTools.decodeFields(
                PdfTools.encodeFields(PdfFormPage(listOf(field.copy(label = "x".repeat(201)))))
            )
        }
    }
}
