package org.smallthingz.alldoc

import org.junit.Assert.*
import org.junit.Test

class RtfRunEditingTest {
    @Test fun insertionsRetainUnaffectedRuns() { assertEquals(listOf("Hello ","wonderful world"),reconcileTextRuns(listOf("Hello ","world"),"Hello wonderful world")) }
    @Test fun deletionAcrossRunBoundariesLeavesOutsideStylesAnchored() { assertEquals(listOf("abcX","","ghi"),reconcileTextRuns(listOf("abcd","ef","ghi"),"abcXghi")) }
    @Test fun changingEmojiNeverSplitsItsSurrogates() { assertEquals(listOf("Hi 😄","!"),reconcileTextRuns(listOf("Hi 😀","!"),"Hi 😄!")) }
    @Test fun emptyParagraphAndAppendRemainEditable() { assertEquals(listOf("","hello"),reconcileTextRuns(listOf("",""),"hello"));assertEquals(listOf("A","B!"),reconcileTextRuns(listOf("A","B"),"AB!")) }
    @Test fun malformedSurrogatesAreNotIntroduced() { assertThrows(IllegalArgumentException::class.java) { reconcileTextRuns(listOf("good"),"bad\uD800") } }
}
