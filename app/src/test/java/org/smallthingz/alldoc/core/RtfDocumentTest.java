package org.smallthingz.alldoc.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RtfDocumentTest {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }
    private static RtfDocument open(String body) throws IOException { return RtfDocument.open(bytes(body)); }
    private static String source(byte[] bytes) { return new String(bytes, StandardCharsets.ISO_8859_1); }
    private static void rejected(String source) { assertThrows(IOException.class, () -> open(source)); }
    private static String wrap(String body) { return "{\\rtf1\\ansi " + body + "}"; }
    private static RtfDocument.Run run(RtfDocument document, String text) {
        return document.runs.stream().filter(r -> r.text.equals(text)).findFirst().orElseThrow();
    }

    @Test public void bodyTextEmphasisAndParagraphs() throws Exception {
        RtfDocument d = open(wrap("First {\\b bold {\\i both} bold} end\\par Next\\line soft\\tab tab"));
        assertEquals("First bold both bold end\nNext\nsoft\ttab", d.plainText());
        assertTrue(run(d, "bold ").bold);
        assertFalse(run(d, "bold ").italic);
        assertTrue(run(d, "both").bold); assertTrue(run(d, "both").italic);
        assertFalse(run(d, " end").bold);
        assertEquals("rtf-p0", run(d, "both").paragraphId);
        assertEquals("rtf-p1", run(d, "Next\nsoft\ttab").paragraphId);
    }
    @Test public void unchangedExportIsIdenticalAndDefensive() throws Exception {
        byte[] b = bytes("{\\rtf1\\ansi A\r\n{\\b B}\\par }\r\n"); byte[] expected = b.clone();
        RtfDocument d = RtfDocument.open(b); Arrays.fill(b, (byte)0);
        byte[] output = d.export(Map.of()); assertArrayEquals(expected, output);
        Arrays.fill(output, (byte)0); assertArrayEquals(expected, d.export(Map.of()));
        assertArrayEquals(expected, d.export(Map.of(d.runs.get(0).id, d.runs.get(0).text)));
    }
    @Test public void escapedReplacementPreservesAllUntouchedBytesAndStyles() throws Exception {
        String prefix = "{\\rtf1\\ansi{\\fonttbl{\\f0\\fnil Arial;}}{\\*\\vendor untouched \\{data\\}}Before {\\b ";
        String suffix = "} after{\\pict\\pngblip 001122aabb}\\par End}";
        RtfDocument d = open(prefix + "old" + suffix);
        String edit = "{not a command} \\b 世界 😀\nline\ttab";
        byte[] result = d.export(Map.of(run(d, "old").id, edit));
        assertTrue(source(result).startsWith(prefix)); assertTrue(source(result).endsWith(suffix));
        RtfDocument reopened = RtfDocument.open(result);
        assertEquals("Before " + edit + " after\nEnd", reopened.plainText());
        assertTrue(run(reopened, edit).bold); assertFalse(run(reopened, " after").bold);
        assertArrayEquals(bytes(prefix + "old" + suffix), d.export(Map.of()));
    }
    @Test public void repeatedExportsStayIndependentAndEmptyEditWorks() throws Exception {
        RtfDocument d = open(wrap("old{\\i other}"));
        String id = run(d, "old").id;
        assertEquals("Aother", RtfDocument.open(d.export(Map.of(id, "A"))).plainText());
        assertEquals("Bother", RtfDocument.open(d.export(Map.of(id, "B"))).plainText());
        assertEquals("other", RtfDocument.open(d.export(Map.of(id, ""))).plainText());
        assertEquals("oldother", d.plainText());
    }
    @Test public void unicodeAndSignedSurrogatePairs() throws Exception {
        RtfDocument d = open(wrap("\\u19990?\\u30028? \\u-10179?\\u-8704?"));
        assertEquals("世界 😀", d.plainText());
        assertEquals("👩🏽‍💻 مرحبا", RtfDocument.open(d.export(Map.of("rtf0", "👩🏽‍💻 مرحبا"))).plainText());
    }
    @Test public void unicodeFallbackCountsEscapesControlsAndBinaryAsOne() throws Exception {
        assertEquals("αZ", open(wrap("\\uc2\\u945\\'61\\~Z")).plainText());
        assertEquals("αZ", open(wrap("\\uc1\\u945\\b Z")).plainText());
        assertEquals("αZ", open(wrap("\\uc1\\u945\\bin3 {}\\Z")).plainText());
        assertEquals("αZ", open(wrap("\\uc1\\u945\r\n?Z")).plainText());
        assertEquals("αZ", open(wrap("\\uc1\\u945\\{Z")).plainText());
    }
    @Test public void unicodeFallbackScopeAndBraces() throws Exception {
        assertEquals("αβXγY", open(wrap("\\uc1\\u945?{\\uc0\\u946X}\\u947?Y")).plainText());
        assertEquals("αB", open(wrap("\\uc2\\u945?{B}")).plainText());
        assertEquals("α?", open(wrap("\\uc0\\u945?")).plainText());
    }
    @Test public void unicodeAlternativeUsesSecondBranchAndIsReadOnly() throws Exception {
        RtfDocument d = open(wrap("Before {\\upr{ANSI}{\\*\\ud Unicode \\u945?}} after"));
        assertEquals("Before Unicode α after", d.plainText());
        assertFalse(run(d, "Unicode α").editable); assertTrue(run(d, "Before ").editable);
        assertThrows(IOException.class, () -> d.export(Map.of(run(d, "Unicode α").id, "changed")));
        byte[] output = d.export(Map.of(run(d, "Before ").id, "Start "));
        assertTrue(source(output).contains("{\\upr{ANSI}{\\*\\ud Unicode \\u945?}}"));
        rejected(wrap("{\\upr{only one}}"));
        rejected(wrap("{\\upr{first}{second}}"));
        rejected(wrap("{\\upr{first}{\\ud second}}"));
        rejected(wrap("{\\upr{first}{\\*\\wrong second}}"));
    }
    @Test public void codePagesAndGroupRestoration() throws Exception {
        assertEquals("café Привет café", open(wrap("caf\\'e9 {\\ansicpg1251 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2} caf\\'e9")).plainText());
        assertEquals("日本", open("{\\rtf1\\ansi\\ansicpg932 \\'93\\'fa\\'96\\'7b}").plainText());
        assertEquals("é", open("{\\rtf1\\ansi\\ansicpg65001 \\'c3\\'a9}").plainText());
    }
    @Test public void fontSpecificCodePageAndExplicitOverride() throws Exception {
        String header = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fcharset0 Latin;}{\\f1\\fcharset204 Cyrillic;}{\\f2\\fcharset0\\cpg932 Japanese;}}";
        assertEquals("café Привет 日本", open(header + "\\f0 caf\\'e9 \\f1 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2 \\f2 \\'93\\'fa\\'96\\'7b}").plainText());
        assertEquals("é", open("{\\rtf1{\\fonttbl{\\f0\\fcharset0{\\*\\falt fake \\f9\\cpg932}Latin;}}\\f0 \\'e9}").plainText());
    }
    @Test public void skippedDestinationsNeverBecomeVisibleAndAreBytePreserved() throws Exception {
        String original = wrap("{\\info{\\title private metadata}}{\\header hidden header}{\\footer hidden footer}Before {\\*\\future {\\b hidden}\\bin5 }{\\xy}After{\\pict 001122}");
        RtfDocument d = open(original);
        assertEquals("Before After", d.plainText());
        String result = source(d.export(Map.of(run(d, "Before ").id, "Changed ")));
        assertTrue(result.contains("{\\*\\future {\\b hidden}\\bin5 }{\\xy}"));
        assertTrue(result.contains("{\\pict 001122}"));
        assertFalse(d.warnings.isEmpty());
    }
    @Test public void fieldsExposeInertReadOnlyResultsNotInstructions() throws Exception {
        RtfDocument d = open(wrap("Go {\\field{\\*\\fldinst HYPERLINK \"https://example.invalid\"}{\\fldrslt link}} now"));
        assertEquals("Go link now", d.plainText()); assertFalse(run(d, "link").editable);
        assertThrows(IOException.class, () -> d.export(Map.of(run(d, "link").id, "changed")));
        String changed = source(d.export(Map.of(run(d, "Go ").id, "See ")));
        assertTrue(changed.contains("{\\field{\\*\\fldinst HYPERLINK \"https://example.invalid\"}{\\fldrslt link}}"));
    }
    @Test public void objectsAreSkippedAndMakeWholeDocumentReadOnly() throws Exception {
        RtfDocument d = open(wrap("Before{\\object{\\*\\objdata 00ff}{\\result cached object}}After"));
        assertEquals("BeforeAfter", d.plainText()); assertFalse(d.editable);
        assertTrue(d.runs.stream().noneMatch(r -> r.editable));
        assertThrows(IOException.class, () -> d.export(Map.of("rtf0", "changed")));
        assertTrue(source(d.export(Map.of())).contains("objdata"));
    }
    @Test public void protectedTrackedAndHtmlDocumentsCannotBeChanged() throws Exception {
        for (String flag : List.of("\\formprot", "\\readprot", "\\protect0", "\\revised", "\\fromhtml1", "\\htmlrtf0", "\\af1", "\\cchs204")) {
            RtfDocument d = open(wrap("before" + flag + " after")); assertFalse(flag, d.editable);
            assertThrows(IOException.class, () -> d.export(Map.of(d.runs.get(0).id, "changed")));
        }
        RtfDocument d = open(wrap("A{\\deleted removed}B"));
        assertEquals("AB", d.plainText()); assertFalse(d.editable);
    }
    @Test public void hiddenTextDoesNotLeakAndPlainResetsStyle() throws Exception {
        RtfDocument d = open(wrap("A{\\v hidden}B{\\b\\i\\ul\\strike\\fs36 decorated\\plain plain}C"));
        assertEquals("ABdecoratedplainC", d.plainText());
        RtfDocument.Run styled = run(d, "decorated");
        assertTrue(styled.bold && styled.italic && styled.underline && styled.strike);
        assertEquals(36, styled.fontSizeHalfPoints);
        RtfDocument.Run plain = run(d, "plain");
        assertFalse(plain.bold || plain.italic || plain.underline || plain.strike);
        assertEquals(24, plain.fontSizeHalfPoints);
    }
    @Test public void tableSeparatorsAndSpecialCharacters() throws Exception {
        assertEquals("A\tB\t\nNext", open(wrap("\\trowd\\intbl A\\cell B\\cell\\row Next")).plainText());
        assertEquals("{x}\\\u00a0\u2011\u00ad—–•‘’“”", open(wrap("\\{x\\}\\\\\\~\\_\\-\\emdash\\endash\\bullet\\lquote\\rquote\\ldblquote\\rdblquote")).plainText());
        assertEquals("A\nB", open(wrap("A\\\r\nB")).plainText());
    }
    @Test public void unsafeUnknownAndMalformedEditsAreRejectedWithoutPoisoning() throws Exception {
        RtfDocument d = open(wrap("old"));
        assertThrows(IOException.class, () -> d.export(Map.of("nope", "x")));
        assertThrows(IOException.class, () -> d.export(Collections.singletonMap("rtf0", null)));
        for (String value : List.of("\u0000", "\u0001", "\r", "\ud800", "\udc00", "\u007f"))
            assertThrows(IOException.class, () -> d.export(Map.of("rtf0", value)));
        assertEquals("valid", RtfDocument.open(d.export(Map.of("rtf0", "valid"))).plainText());
    }
    @Test public void malformedSyntaxAndBinaryLengthsFailClosed() {
        for (String value : List.of("not rtf", "{\\rtf2 hi}", "{\\rtf1 hi", "{\\rtf1 hi}}", "{\\rtf1 hi}garbage",
                "{\\rtf1 \\'zz}", "{\\rtf1 \\'a", "{\\rtf1 \\u}", "{\\rtf1 \\u999999}",
                "{\\rtf1 \\uc-1}", "{\\rtf1 \\uc999}", "{\\rtf1 \\bin1 A}",
                "{\\rtf1{\\pict\\bin9 xy}}", "{\\rtf1{\\pict\\bin-1 x}}", "{\\rtf1{\\pict\\bin2147483648 x}}",
                "{\\rtf1 x\\pict 0011}", "{\\rtf1 x\\*\\foo ignored}", "{\\rtf1{\\*}}", "{\\rtf1 \\u-10179?}")) rejected(value);
    }
    @Test public void unsupportedAndInvalidEncodingsAreNotGuessed() {
        rejected("{\\rtf1\\ansicpg9999 text}");
        rejected("{\\rtf1\\ansicpg65001 \\'c3x}");
        rejected("{\\rtf1\\ansicpg932 \\'81}");
        rejected("{\\rtf1{\\fonttbl{\\f0\\fcharset2 Symbol;}}\\f0 ABC}");
        rejected("{\\rtf1{\\fonttbl{\\f0 First;}{\\f0 Second;}}text}");
        rejected("{\\rtf1{\\fonttbl{\\f0 First;}}\\f99 text}");
    }
    @Test public void depthRunTokenTextAndFileBounds() throws Exception {
        rejected(wrap("{".repeat(RtfDocument.MAX_DEPTH) + "x" + "}".repeat(RtfDocument.MAX_DEPTH)));
        rejected(wrap("{x}".repeat(RtfDocument.MAX_RUNS + 1)));
        rejected(wrap("\\unknown ".repeat(RtfDocument.MAX_TOKENS + 1)));
        rejected(wrap("x".repeat(RtfDocument.MAX_TEXT + 1)));
        assertThrows(IOException.class, () -> RtfDocument.open(new byte[RtfDocument.MAX_FILE + 1]));
        RtfDocument d = open(wrap("old"));
        assertThrows(IOException.class, () -> d.export(Map.of("rtf0", "x".repeat(RtfDocument.MAX_TEXT + 1))));
    }
    @Test public void aggregateEditBudgetAndEncodedOutputBound() throws Exception {
        RtfDocument d = open(wrap("one{\\b two}"));
        String half = "x".repeat(RtfDocument.MAX_TEXT / 2 + 1);
        assertThrows(IOException.class, () -> d.export(Map.of("rtf0", half, "rtf1", half)));
        // Source text is short but escaping expands each character. Bound before returning output.
        assertThrows(IOException.class, () -> d.export(Map.of("rtf0", "\uffff".repeat(RtfDocument.MAX_TEXT))));
    }
    @Test public void skippedNestedFontDefinitionsCannotChangeVisibleEncoding() throws Exception {
        assertEquals("é", open(wrap("{\\*\\future{\\fonttbl{\\f0\\fcharset204 Forged;}}}\\'e9")).plainText());
    }
    @Test public void sourceBytesWithRawSingleByteAndCrLfRemainExact() throws Exception {
        byte[] original = bytes("{\\rtf1\\ansi Caf\u00e9\r\n and rest}");
        RtfDocument d = RtfDocument.open(original);
        assertEquals("Café and rest", d.plainText());
        assertArrayEquals(original, d.export(Map.of()));
    }
    @Test public void omittedBinaryPayloadIsNotParsedAsRtfSyntax() throws Exception {
        String prefix = "{\\rtf1\\ansi Before{\\pict\\bin7 ";
        byte[] raw = bytes(prefix + "}{{\\\u0000xy" + "}After}");
        assertEquals("BeforeAfter", RtfDocument.open(raw).plainText());
    }
    @Test public void fallbackEditDoesNotConsumeFollowingGroup() throws Exception {
        RtfDocument d = open(wrap("\\uc2\\u945?{\\b B}C"));
        byte[] changed = d.export(Map.of(run(d, "α").id, "日本 😀"));
        assertEquals("日本 😀BC", RtfDocument.open(changed).plainText());
        assertTrue(source(changed).contains("{\\b B}C"));
    }
    @Test public void htmlDestinationsNeverBecomeRenderedMarkup() throws Exception {
        RtfDocument d = open(wrap("\\fromhtml1 {\\*\\htmltag <script>alert('never')</script>}Inert body"));
        assertEquals("Inert body", d.plainText()); assertFalse(d.editable);
    }
    @Test public void tinyRandomMalformedInputsDoNotEscapeAsUncheckedErrors() throws Exception {
        Random random = new Random(3901);
        for (int i = 0; i < 2000; i++) {
            byte[] input = new byte[6 + random.nextInt(90)];
            byte[] prefix = bytes("{\\rtf1"); System.arraycopy(prefix, 0, input, 0, prefix.length);
            for (int j = prefix.length; j < input.length; j++) input[j] = (byte)random.nextInt(256);
            try { RtfDocument parsed = RtfDocument.open(input); assertArrayEquals(input, parsed.export(Map.of())); }
            catch (IOException expected) { /* Explicit refusal is the expected safe result. */ }
        }
    }
    @Test public void tableCellSeparatorsAreReadOnlyNativeRuns() throws Exception {
        RtfDocument d = open(wrap("A\\cell B\\cell\\row C"));
        assertEquals("A\tB\tC", d.runs.stream().map(r -> r.text).reduce("", String::concat));
        assertFalse(d.runs.get(1).editable);
        assertEquals(d.runs.get(0).paragraphId, d.runs.get(2).paragraphId);
        assertNotEquals(d.runs.get(2).paragraphId, d.runs.get(4).paragraphId);
        assertThrows(IOException.class, () -> d.export(Map.of(d.runs.get(1).id, "x")));
    }
    @Test public void randomUnicodeEditsReopenWithoutEscapingIntoControls() throws Exception {
        RtfDocument d = open(wrap("before{\\b old}after"));
        String[] fragments = {"a", " ", "{", "}", "\\", "\n", "\t", "é", "世", "م", "😀", "👩🏽‍💻", "\\object", "\\bin999 "};
        Random random = new Random(391);
        for (int i = 0; i < 250; i++) {
            StringBuilder edit = new StringBuilder();
            for (int j = 0, count = random.nextInt(30); j < count; j++) edit.append(fragments[random.nextInt(fragments.length)]);
            RtfDocument result = RtfDocument.open(d.export(Map.of(run(d, "old").id, edit.toString())));
            assertEquals("before" + edit + "after", result.plainText());
            assertTrue(result.editable);
        }
    }
    @Test public void unicodeFallbackInFontTableCannotRedefineAFont() throws Exception {
        assertEquals("é", open("{\\rtf1{\\fonttbl{\\f0\\fcharset0 Name \\uc1\\u945\\f0 ;}}\\f0 \\'e9}").plainText());
        assertEquals("Visible", open(wrap("{\\*\\hidden \\uc1\\u945\\bin4 }{{\\}Visible")).plainText());
    }
    @Test public void immutableViewsCannotBeAltered() throws Exception {
        RtfDocument d = open(wrap("text"));
        assertThrows(UnsupportedOperationException.class, () -> d.runs.clear());
        assertThrows(UnsupportedOperationException.class, () -> d.warnings.clear());
    }
    @Test public void fixtureRoundTrip() throws Exception {
        Path root = Files.isDirectory(Path.of("fixtures")) ? Path.of("fixtures") : Path.of("../fixtures");
        byte[] fixture = Files.readAllBytes(root.resolve("formatted.rtf"));
        RtfDocument d = RtfDocument.open(fixture);
        assertTrue(d.plainText().contains("alldoc RTF example"));
        assertTrue(d.plainText().contains("世界 😀"));
        assertArrayEquals(fixture, d.export(Map.of()));
        RtfDocument.Run title = run(d, "alldoc RTF example");
        RtfDocument changed = RtfDocument.open(d.export(Map.of(title.id, "Edited RTF heading")));
        assertTrue(changed.plainText().contains("Edited RTF heading"));
        assertTrue(run(changed, "Edited RTF heading").bold);
    }
}
