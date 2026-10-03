package org.smallthingz.alldoc.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bounded, inert RTF body-text reader and focused text editor. This is not a page-layout engine.
 * Source offsets refer to the immutable original, never to a decoded/re-serialized approximation.
 * No fonts, images, objects, fields, URLs, HTML or application instructions are executed or loaded.
 */
public final class RtfDocument {
    public static final int MAX_FILE = 8 * 1024 * 1024;
    public static final int MAX_TEXT = 1024 * 1024;
    public static final int MAX_RUNS = 12000;
    public static final int MAX_DEPTH = 128;
    public static final int MAX_TOKENS = 250000;
    public static final int MAX_FONTS = 2048;

    public static final class Run {
        public final String id, paragraphId, text;
        public final boolean editable, bold, italic, underline, strike;
        /** Half points, bounded to 1..3276 (preview should additionally clamp its display size). */
        public final int fontSizeHalfPoints;
        private final int start, end;
        private Run(int index, int paragraph, String text, State state, int start, int end, boolean editable) {
            this.id = "rtf" + index; this.paragraphId = "rtf-p" + paragraph; this.text = text;
            this.editable = editable; this.bold = state.bold; this.italic = state.italic;
            this.underline = state.underline; this.strike = state.strike;
            this.fontSizeHalfPoints = state.fontSize; this.start = start; this.end = end;
        }
        private Run(Run source, boolean documentEditable) {
            id = source.id; paragraphId = source.paragraphId; text = source.text;
            editable = source.editable && documentEditable; bold = source.bold; italic = source.italic;
            underline = source.underline; strike = source.strike; fontSizeHalfPoints = source.fontSizeHalfPoints;
            start = source.start; end = source.end;
        }
    }

    public final List<Run> runs;
    public final List<String> warnings;
    public final boolean editable;
    private final byte[] original;
    private final List<Piece> pieces;

    private RtfDocument(Parser parser) {
        original = parser.bytes;
        List<Run> copy = new ArrayList<>();
        boolean anyEditable = false;
        for (Run run : parser.runs) {
            Run frozen = new Run(run, !parser.documentReadOnly);
            copy.add(frozen); anyEditable |= frozen.editable;
        }
        runs = Collections.unmodifiableList(copy);
        warnings = Collections.unmodifiableList(new ArrayList<>(parser.warnings));
        pieces = Collections.unmodifiableList(new ArrayList<>(parser.pieces));
        editable = anyEditable;
    }

    public static RtfDocument open(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_FILE) throw new IOException("RTF exceeds the 8 MiB safe input limit");
        Parser parser = new Parser(bytes.clone());
        parser.parse();
        return new RtfDocument(parser);
    }

    /** Includes paragraph/table separators; non-body destinations and hidden text are omitted. */
    public String plainText() { return textWithChanges(Collections.emptyMap()); }

    private String textWithChanges(Map<String, String> changes) {
        StringBuilder result = new StringBuilder();
        for (Piece piece : pieces)
            result.append(piece.run < 0 ? piece.separator : changes.getOrDefault(runs.get(piece.run).id, runs.get(piece.run).text));
        return result.toString();
    }

    /**
     * Replace existing visible runs only. Paragraph structure and every byte outside changed text
     * token spans are retained. Newlines in edits are soft line breaks, not new paragraphs.
     * Each export starts from the original; failures cannot mutate this document.
     */
    public byte[] export(Map<String, String> changes) throws IOException {
        if (changes == null) throw new IOException("Missing RTF edit map");
        Map<String, String> actual = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        long editedCharacters = 0;
        for (Run run : runs) {
            known.add(run.id);
            if (!changes.containsKey(run.id)) continue;
            String replacement = changes.get(run.id);
            if (replacement == null) throw new IOException("RTF edit text is missing");
            if (replacement.equals(run.text)) continue;
            if (!run.editable) throw new IOException("This RTF text is read-only");
            if (replacement.length() > MAX_TEXT || (editedCharacters += replacement.length()) > MAX_TEXT)
                throw new IOException("RTF edits exceed the 1 Mi-character safe limit");
            validateText(replacement);
            actual.put(run.id, replacement);
        }
        if (!known.containsAll(changes.keySet())) throw new IOException("Unknown RTF edit target");
        if (actual.isEmpty()) return original.clone();
        String expected = textWithChanges(actual);
        if (expected.length() > MAX_TEXT) throw new IOException("Edited RTF exceeds the 1 Mi-character text limit");
        BoundedOutput output = new BoundedOutput();
        int cursor = 0;
        for (Run run : runs) {
            if (!actual.containsKey(run.id)) continue;
            output.put(original, cursor, run.start - cursor);
            writeEscaped(output, actual.get(run.id));
            cursor = run.end;
        }
        output.put(original, cursor, original.length - cursor);
        byte[] result = output.bytes.toByteArray();
        // A fresh parser checks syntax, limits and the entire visible text, including unchanged runs.
        if (!expected.equals(open(result).plainText())) throw new IOException("RTF edit did not round-trip safely");
        return result;
    }

    private static void writeEscaped(BoundedOutput output, String text) throws IOException {
        // The local group restores the source's uc count and all inherited formatting on exit.
        output.ascii("{\\uc1 ");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '{' || c == '}') { output.byteValue('\\'); output.byteValue(c); }
            else if (c == '\n') output.ascii("\\line ");
            else if (c == '\t') output.ascii("\\tab ");
            else if (c >= 32 && c < 127) output.byteValue(c);
            else output.ascii("\\u" + (short)c + "?");
        }
        output.byteValue('}');
    }

    private static void validateText(String text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IOException("RTF text contains an unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IOException("RTF text contains an unpaired Unicode surrogate");
            else if ((c < 32 && c != '\t' && c != '\n') || c == 127)
                throw new IOException("RTF text contains an unsupported control character");
        }
    }

    private static final class BoundedOutput {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        void put(byte[] data, int offset, int length) throws IOException {
            if (length < 0 || length > MAX_FILE - bytes.size()) throw new IOException("RTF export exceeds the 8 MiB safe limit");
            bytes.write(data, offset, length);
        }
        void byteValue(int value) throws IOException {
            if (bytes.size() == MAX_FILE) throw new IOException("RTF export exceeds the 8 MiB safe limit");
            bytes.write(value);
        }
        void ascii(String value) throws IOException { byte[] data = value.getBytes(StandardCharsets.US_ASCII); put(data, 0, data.length); }
    }

    private static final class Piece {
        final int run;
        final String separator;
        Piece(int run, String separator) { this.run = run; this.separator = separator; }
    }

    private static final class Font {
        int charset = -1, codePage = -1;
    }

    private static final class State {
        boolean skip, fontTable, first = true, starred, hidden, deleted, readOnly;
        boolean bold, italic, underline, strike, upr, unicodeAlternate;
        int font, codePage = 1252, uc = 1, fontSize = 24, children;
        State copy() {
            State value = new State();
            value.skip = skip; value.fontTable = fontTable; value.hidden = hidden; value.deleted = deleted; value.readOnly = readOnly;
            value.bold = bold; value.italic = italic; value.underline = underline; value.strike = strike;
            value.font = font; value.codePage = codePage; value.uc = uc; value.fontSize = fontSize;
            return value;
        }
    }

    private static final class Control {
        final int start, end;
        final String word;
        final Integer value;
        final int symbol, hex;
        Control(int start, int end, String word, Integer value, int symbol, int hex) {
            this.start = start; this.end = end; this.word = word; this.value = value; this.symbol = symbol; this.hex = hex;
        }
        int required() throws IOException { if (value == null) throw new IOException("RTF control requires a numeric value: " + word); return value; }
        boolean on() { return value == null || value != 0; }
    }

    private static final Set<String> OMIT = new HashSet<>(Arrays.asList(
            "colortbl", "stylesheet", "info", "pict", "object", "objdata", "objclass", "objname", "result",
            "filetbl", "revtbl", "rsidtbl", "listtable", "listoverridetable", "listtext", "pntext", "pn",
            "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
            "footnote", "annotation", "atnauthor", "atnid", "atnref", "atntime", "aftncn", "ftncn",
            "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "atnicon", "atnparent", "fontemb", "fontfile", "fname", "falt",
            "bkmkstart", "bkmkend", "xe", "tc", "txe", "rxe", "template", "docvar", "private",
            "shp", "shpinst", "shppict", "nonshppict", "shprslt", "do", "dptxbxtext",
            "fldinst", "datafield", "formfield", "htmltag", "mhtmltag", "xmlnstbl", "datastore",
            "themedata", "colorschememapping", "latentstyles", "generator", "mmathPr"));
    private static final Set<String> PROTECTION = new HashSet<>(Arrays.asList(
            "protect", "formprot", "readprot", "annotprot", "revprot", "protstart", "protend", "enforceprot"));

    private static final class Parser {
        final byte[] bytes;
        final List<Run> runs = new ArrayList<>();
        final List<Piece> pieces = new ArrayList<>();
        final LinkedHashSet<String> warnings = new LinkedHashSet<>();
        final Map<Integer, Font> fonts = new HashMap<>();
        final Deque<State> stack = new ArrayDeque<>();
        final ByteArrayOutputStream ansi = new ByteArrayOutputStream();
        final StringBuilder text = new StringBuilder();
        State state;
        int position, tokens, paragraph, extractedCharacters, runStart = -1, runEnd, defaultFont;
        boolean documentReadOnly;

        Parser(byte[] bytes) {
            this.bytes = bytes;
            warnings.add("Approximate RTF body text: basic emphasis and size only; page layout, fonts, colours, tables, lists and images are not reproduced.");
            warnings.add("Text edits preserve untouched RTF bytes; review exported copies in the originating application.");
        }

        void parse() throws IOException {
            if (bytes.length < 7 || bytes[0] != '{' || bytes[1] != '\\') throw new IOException("Not an RTF document");
            state = new State(); position = 1;
            Control header = readControl();
            if (!header.word.equals("rtf") || header.required() != 1) throw new IOException("Only RTF version 1 documents are supported");
            state.first = false;
            boolean closed = false;
            while (position < bytes.length) {
                if (++tokens > MAX_TOKENS) throw new IOException("RTF exceeds the safe token limit");
                int c = bytes[position] & 255;
                if (c == '{') {
                    if (state.unicodeAlternate) throw new IOException("Missing RTF Unicode alternative destination");
                    flush();
                    if (stack.size() + 1 >= MAX_DEPTH) throw new IOException("RTF exceeds the safe nesting limit");
                    State parent = state; State child = parent.copy();
                    parent.first = false;
                    if (parent.upr) {
                        if (++parent.children > 2) throw new IOException("Malformed RTF Unicode alternative");
                        child.skip |= parent.children == 1;
                        child.unicodeAlternate = parent.children == 2;
                        child.readOnly = true;
                    }
                    stack.push(parent); state = child; position++;
                } else if (c == '}') {
                    flush();
                    if (state.starred || state.unicodeAlternate) throw new IOException("Missing RTF destination after ignorable marker");
                    if (state.upr && state.children != 2) throw new IOException("Incomplete RTF Unicode alternative");
                    position++;
                    if (stack.isEmpty()) { closed = true; break; }
                    state = stack.pop();
                } else if (c == '\\') {
                    control(readControl());
                } else if (c == '\r' || c == '\n') {
                    position++; // RTF transport line wrapping is not document text.
                } else {
                    if (state.unicodeAlternate) throw new IOException("Missing RTF Unicode alternative destination");
                    state.first = false;
                    int start = position;
                    while (position < bytes.length) {
                        int b = bytes[position] & 255;
                        if (b == '{' || b == '}' || b == '\\' || b == '\r' || b == '\n') break;
                        if (b == 0 || b < 32 && b != '\t' || b == 127) throw new IOException("Invalid raw RTF control byte");
                        position++;
                    }
                    if (visible()) {
                        begin(start); appendAnsi(bytes, start, position - start); runEnd = position;
                    }
                }
            }
            if (!closed || !stack.isEmpty()) throw new IOException("Unclosed RTF group");
            while (position < bytes.length) {
                int b = bytes[position++] & 255;
                if (b != 32 && b != '\r' && b != '\n' && b != '\t') throw new IOException("Unexpected data after RTF root");
            }
        }

        boolean visible() { return !state.skip && !state.hidden && !state.deleted; }
        void begin(int start) { if (runStart < 0) runStart = start; }
        void appendAnsi(byte[] data, int start, int length) throws IOException {
            if (length > MAX_TEXT * 4 - ansi.size()) throw new IOException("RTF encoded text exceeds the safe limit");
            ansi.write(data, start, length);
        }
        void appendAnsi(int b) throws IOException {
            if (ansi.size() == MAX_TEXT * 4) throw new IOException("RTF encoded text exceeds the safe limit");
            ansi.write(b);
        }
        void decodeAnsi() throws IOException {
            if (ansi.size() == 0) return;
            Charset charset = selectedCharset();
            try {
                String decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(ansi.toByteArray())).toString();
                if (decoded.length() > MAX_TEXT - extractedCharacters - text.length()) throw new IOException("RTF exceeds the 1 Mi-character text limit");
                text.append(decoded);
            } catch (CharacterCodingException e) { throw new IOException("RTF contains invalid bytes for its declared code page", e); }
            ansi.reset();
        }
        void appendUnicode(char c) throws IOException {
            decodeAnsi();
            if (text.length() >= MAX_TEXT - extractedCharacters) throw new IOException("RTF exceeds the 1 Mi-character text limit");
            text.append(c);
        }
        void flush() throws IOException {
            decodeAnsi();
            if (text.length() > 0) {
                String value = text.toString(); validateText(value);
                if (runs.size() == MAX_RUNS) throw new IOException("RTF exceeds the safe text-run limit");
                Run run = new Run(runs.size(), paragraph, value, state, runStart, runEnd, !state.readOnly);
                runs.add(run); pieces.add(new Piece(runs.size() - 1, null)); extractedCharacters += value.length();
            }
            text.setLength(0); runStart = -1;
        }
        void separator(String value, boolean newParagraph) throws IOException {
            flush();
            if (visible()) {
                if (value.length() > MAX_TEXT - extractedCharacters) throw new IOException("RTF exceeds the safe text limit");
                if (pieces.size() >= MAX_TOKENS) throw new IOException("RTF exceeds the safe paragraph limit");
                if (newParagraph) {
                    pieces.add(new Piece(-1, value)); paragraph++;
                } else {
                    // Native run-based views need cell separators too; they are structural/read-only.
                    if (runs.size() == MAX_RUNS) throw new IOException("RTF exceeds the safe text-run limit");
                    runs.add(new Run(runs.size(), paragraph, value, state, position, position, false));
                    pieces.add(new Piece(runs.size() - 1, null));
                }
                extractedCharacters += value.length();
            }
        }

        void control(Control c) throws IOException {
            if (c.symbol != 0) {
                if (c.symbol == '*') {
                    flush();
                    if (!state.first || state.starred) throw new IOException("Misplaced RTF ignorable destination marker");
                    state.starred = true; return;
                }
                if (state.starred || state.unicodeAlternate) throw new IOException("Missing RTF ignorable destination");
                state.first = false;
                if (!visible()) return;
                if (c.hex >= 0) { begin(c.start); appendAnsi(c.hex); runEnd = c.end; return; }
                if (c.symbol == '\\' || c.symbol == '{' || c.symbol == '}') {
                    begin(c.start); appendAnsi(c.symbol); runEnd = c.end; return;
                }
                char special = c.symbol == '~' ? '\u00a0' : c.symbol == '_' ? '\u2011' : c.symbol == '-' ? '\u00ad' : 0;
                if (special != 0) { begin(c.start); appendUnicode(special); runEnd = c.end; }
                else if (c.symbol == '\n' || c.symbol == '\r') separator("\n", true);
                else flush();
                return;
            }
            String word = c.word;
            if (word.equals("bin")) {
                flush();
                int count = c.required();
                if (count < 0 || count > bytes.length - position) throw new IOException("Invalid RTF binary length");
                if (!state.skip) throw new IOException("RTF binary data is supported only inside omitted destinations");
                position += count; state.first = false; return;
            }
            if (state.first) {
                if (state.unicodeAlternate && (!word.equals("ud") || !state.starred))
                    throw new IOException("Malformed RTF Unicode alternative destination");
                if (word.equals("fonttbl")) { flush(); state.fontTable = !state.skip; state.skip = true; }
                else if (word.equals("field")) { flush(); state.readOnly = true; warnings.add("Fields are inert; cached results are read-only and may be stale."); }
                else if (word.equals("fldrslt")) { flush(); state.readOnly = true; }
                else if (word.equals("ud") && state.unicodeAlternate) { state.unicodeAlternate = false; }
                else if (word.equals("upr")) {
                    flush(); state.upr = true; state.readOnly = true;
                    warnings.add("Unicode/ANSI alternative text is shown from its Unicode branch and is read-only.");
                } else if (OMIT.contains(word) || state.starred) {
                    flush(); state.skip = true; state.fontTable = false;
                    if (word.equals("object")) {
                        documentReadOnly = true;
                        warnings.add("Embedded objects are not opened; this RTF is read-only and original copies retain those objects.");
                    }
                    if (word.equals("pict") || word.equals("shp") || word.equals("shppict") || word.equals("nonshppict"))
                        warnings.add("Images and drawings are omitted from the preview and preserved in exported copies.");
                    if (word.startsWith("header") || word.startsWith("footer") || word.equals("footnote") || word.equals("annotation"))
                        warnings.add("Headers, footers, notes and comments are omitted from this body-text preview.");
                }
                state.first = false; state.starred = false;
            } else if (word.equals("fonttbl") || OMIT.contains(word) || word.equals("field") || word.equals("fldrslt") || word.equals("upr") || word.equals("ud")) {
                throw new IOException("Misplaced RTF destination: " + word);
            }
            // Fallback tokens must be consumed even in hidden/omitted destinations. Otherwise a
            // fallback control could accidentally redefine a font or be mistaken for RTF structure.
            if (word.equals("uc")) {
                flush(); state.uc = bounded(c.required(), 0, 255, "Unicode fallback count"); return;
            }
            if (word.equals("u")) {
                int number = bounded(c.required(), -32768, 65535, "Unicode value");
                int end = skipFallback(state.uc);
                if (visible()) { begin(c.start); appendUnicode((char)number); runEnd = end; }
                return;
            }
            if (state.fontTable) {
                flush();
                if (word.equals("f")) {
                    int font = bounded(c.required(), 0, 65535, "font index");
                    if (fonts.containsKey(font)) throw new IOException("Duplicate RTF font definition");
                    if (fonts.size() >= MAX_FONTS) throw new IOException("RTF exceeds the safe font limit");
                    fonts.put(font, new Font()); state.font = font;
                } else if (word.equals("fcharset") || word.equals("cpg")) {
                    Font font = fonts.get(state.font);
                    if (font == null) throw new IOException("RTF font encoding has no font definition");
                    if (word.equals("fcharset")) font.charset = bounded(c.required(), 0, 255, "font character set");
                    else font.codePage = bounded(c.required(), 1, 65535, "font code page");
                }
                return;
            }
            if (state.skip) return;
            if (word.equals("rtf")) throw new IOException("Unexpected nested RTF header");
            if (PROTECTION.contains(word)) {
                flush(); documentReadOnly = true; warnings.add("RTF editing protection is present; this document is read-only."); return;
            }
            if (word.equals("fromhtml") || word.equals("htmlrtf")) {
                flush(); documentReadOnly = true;
                warnings.add("HTML-encapsulated RTF is shown as inert RTF text only and is read-only."); return;
            }
            switch (word) {
                case "ansi": flush(); state.codePage = 1252; break;
                case "mac": flush(); state.codePage = 10000; break;
                case "pc": flush(); state.codePage = 437; break;
                case "pca": flush(); state.codePage = 850; break;
                case "ansicpg": flush(); state.codePage = bounded(c.required(), 1, 65535, "code page"); charsetFor(state.codePage); break;
                case "deff": flush(); defaultFont = bounded(c.required(), 0, 65535, "default font"); state.font = defaultFont; break;
                case "f": flush(); state.font = bounded(c.required(), 0, 65535, "font index"); break;
                case "af": case "cchs":
                    flush(); documentReadOnly = true;
                    warnings.add("Composite character-set/font selection is approximate; this RTF is read-only."); break;
                case "par": case "page": case "sect": case "row": case "nestrow": separator("\n", true); break;
                case "cell": case "nestcell": separator("\t", false); break;
                case "line": special(c, '\n'); break;
                case "tab": special(c, '\t'); break;
                case "emdash": special(c, '\u2014'); break;
                case "endash": special(c, '\u2013'); break;
                case "emspace": special(c, '\u2003'); break;
                case "enspace": special(c, '\u2002'); break;
                case "qmspace": special(c, '\u2005'); break;
                case "bullet": special(c, '\u2022'); break;
                case "lquote": special(c, '\u2018'); break;
                case "rquote": special(c, '\u2019'); break;
                case "ldblquote": special(c, '\u201c'); break;
                case "rdblquote": special(c, '\u201d'); break;
                case "b": flush(); state.bold = c.on(); break;
                case "i": flush(); state.italic = c.on(); break;
                case "ul": case "ulw": case "uld": case "uldash": case "uldb": case "ulth": case "ulwave":
                    flush(); state.underline = c.on(); break;
                case "ulnone": flush(); state.underline = false; break;
                case "strike": case "striked": flush(); state.strike = c.on(); break;
                case "fs": flush(); state.fontSize = bounded(c.required(), 1, 3276, "font size"); break;
                case "v":
                    flush(); state.hidden = c.on();
                    if (state.hidden) warnings.add("Hidden text is omitted from the preview and retained in exported copies.");
                    break;
                case "deleted":
                    flush(); state.deleted = c.on(); documentReadOnly = true;
                    warnings.add("Tracked revisions are present; deleted text is omitted and this RTF is read-only."); break;
                case "revised":
                    flush(); documentReadOnly = true; warnings.add("Tracked revisions are present; this RTF is read-only."); break;
                case "plain":
                    flush(); state.bold = state.italic = state.underline = state.strike = state.hidden = state.deleted = false;
                    state.fontSize = 24; state.font = defaultFont; break;
                case "intbl": case "trowd":
                    flush(); warnings.add("Tables are flattened into body text with cell and row separators."); break;
                default: flush(); // Unknown formatting is retained verbatim, never interpreted as text.
            }
        }

        void special(Control control, char value) throws IOException {
            if (visible()) { begin(control.start); appendUnicode(value); runEnd = control.end; }
        }
        int skipFallback(int count) throws IOException {
            while (count > 0 && position < bytes.length) {
                int c = bytes[position] & 255;
                if (c == '{' || c == '}') break;
                if (c == '\r' || c == '\n') { position++; continue; }
                if (++tokens > MAX_TOKENS) throw new IOException("RTF exceeds the safe token limit");
                if (c == '\\') {
                    Control token = readControl();
                    if (token.word.equals("bin")) {
                        int length = token.required();
                        if (length < 0 || length > bytes.length - position) throw new IOException("Invalid RTF fallback binary length");
                        position += length;
                    }
                } else position++;
                count--;
            }
            return position;
        }

        Control readControl() throws IOException {
            int start = position++;
            if (position == bytes.length) throw new IOException("Truncated RTF escape");
            int symbol = bytes[position++] & 255;
            if (symbol == 0 || symbol > 127) throw new IOException("Invalid RTF control symbol");
            if (!letter(symbol)) {
                int hex = -1;
                if (symbol == '\'') {
                    if (bytes.length - position < 2) throw new IOException("Truncated RTF hex escape");
                    int a = Character.digit((char)(bytes[position++] & 255), 16);
                    int b = Character.digit((char)(bytes[position++] & 255), 16);
                    if (a < 0 || b < 0) throw new IOException("Malformed RTF hex escape");
                    hex = a * 16 + b;
                } else if (symbol == '\r' && position < bytes.length && bytes[position] == '\n') position++;
                return new Control(start, position, "", null, symbol, hex);
            }
            int wordStart = position - 1;
            while (position < bytes.length && letter(bytes[position] & 255)) position++;
            if (position - wordStart > 32) throw new IOException("RTF control word exceeds 32 characters");
            String word = new String(bytes, wordStart, position - wordStart, StandardCharsets.US_ASCII);
            Integer value = null;
            int numberStart = position;
            boolean negative = position < bytes.length && bytes[position] == '-';
            if (negative) position++;
            int digitsStart = position;
            long parsed = 0;
            while (position < bytes.length && bytes[position] >= '0' && bytes[position] <= '9') {
                parsed = parsed * 10 + bytes[position++] - '0';
                if (position - digitsStart > 10 || parsed > (negative ? 2147483648L : Integer.MAX_VALUE))
                    throw new IOException("RTF numeric parameter exceeds the safe integer range");
            }
            if (position > digitsStart) value = (int)(negative ? -parsed : parsed);
            else if (negative) position = numberStart; // A hyphen without digits is a text delimiter.
            if (position < bytes.length && bytes[position] == ' ') position++;
            return new Control(start, position, word, value, 0, -1);
        }

        Charset selectedCharset() throws IOException {
            int page = state.codePage;
            Font font = fonts.get(state.font);
            if (font == null && !fonts.isEmpty()) throw new IOException("RTF refers to an undefined font");
            if (font != null) {
                if (font.codePage > 0) page = font.codePage;
                else if (font.charset >= 0 && font.charset != 1) page = charsetPage(font.charset);
            }
            return charsetFor(page);
        }
    }

    private static boolean letter(int b) { return b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z'; }
    private static int bounded(int value, int min, int max, String kind) throws IOException {
        if (value < min || value > max) throw new IOException("RTF " + kind + " is outside the supported range");
        return value;
    }
    private static int charsetPage(int value) throws IOException {
        switch (value) {
            case 0: return 1252; case 77: return 10000; case 128: return 932; case 129: return 949;
            case 130: return 1361; case 134: return 936; case 136: return 950; case 161: return 1253;
            case 162: return 1254; case 163: return 1258; case 177: return 1255; case 178: return 1256;
            case 186: return 1257; case 204: return 1251; case 222: return 874; case 238: return 1250;
            case 254: return 437; case 255: return 850;
            default: throw new IOException("Unsupported RTF font character set " + value + "; symbol fonts are not guessed");
        }
    }
    private static Charset charsetFor(int page) throws IOException {
        String name;
        if (page >= 1250 && page <= 1258) name = "windows-" + page;
        else switch (page) {
            case 437: name = "IBM437"; break; case 850: name = "IBM850"; break;
            case 874: name = "x-windows-874"; break; case 932: name = "windows-31j"; break;
            case 936: name = "GBK"; break; case 949: name = "x-windows-949"; break;
            case 950: name = "Big5"; break; case 1361: name = "Johab"; break;
            case 10000: name = "x-MacRoman"; break; case 65001: name = "UTF-8"; break;
            default: throw new IOException("Unsupported RTF code page " + page);
        }
        try { return Charset.forName(name); }
        catch (IllegalArgumentException e) { throw new IOException("RTF code page " + page + " is unavailable on this device", e); }
    }
}
