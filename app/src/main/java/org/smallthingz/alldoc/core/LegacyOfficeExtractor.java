package org.smallthingz.alldoc.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hslf.extractor.QuickButCruddyTextExtractor;
import org.apache.poi.hslf.record.CurrentUserAtom;
import org.apache.poi.hslf.record.RecordTypes;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.*;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.LittleEndian;

/** Bounded, read-only text preview. Never evaluates formulas, executes active content or writes OLE. */
public final class LegacyOfficeExtractor {
  public static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
  public static final int MAX_TEXT_CHARS = 1024 * 1024;
  private static final int MAX_ENTRIES = 2048, MAX_RECORDS = 100_000, MAX_DEPTH = 64;
  private LegacyOfficeExtractor() {}

  public static final class Section {
    public final String title;
    public final String text;
    Section(String title, String text) { this.title = title; this.text = text; }
  }
  public static final class Result {
    public final List<Section> sections;
    public final String warning;
    Result(List<Section> sections, String warning) {
      this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
      this.warning = warning;
    }
  }

  /** Serialized because POI's allocation guard is process-global. No other POI API is used by app. */
  public static synchronized Result extract(String extension, byte[] bytes) throws IOException {
    if (bytes == null || bytes.length < 512 || bytes.length > MAX_INPUT_BYTES)
      throw new IOException("Legacy Office preview is limited to 8 MiB OLE files");
    String ext = extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
    int previous = IOUtils.getByteArrayMaxOverride();
    IOUtils.setByteArrayMaxOverride(MAX_INPUT_BYTES);
    try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
      scanDirectory(fs.getRoot(), 0, new int[] {0}, new long[] {0});
      switch (ext) {
        case "doc": return word(fs);
        case "xls": return excel(fs);
        case "ppt": return presentation(fs);
        default: throw new IOException("Unsupported legacy Office extension");
      }
    } catch (IOException e) {
      throw e;
    } catch (LinkageError e) {
      throw new IOException("This file requires an unsupported desktop-only Office feature", e);
    } catch (RuntimeException e) {
      // Do not stringify a POI record: diagnostic writers may require desktop graphics classes.
      throw new IOException("Legacy Office file is encrypted, malformed, or exceeds preview limits", e);
    } finally {
      IOUtils.setByteArrayMaxOverride(previous);
    }
  }

  private static void scanDirectory(DirectoryEntry dir, int depth, int[] entries, long[] total)
      throws IOException {
    if (depth > MAX_DEPTH) throw new IOException("OLE directory depth limit exceeded");
    for (Entry e : dir) {
      if (++entries[0] > MAX_ENTRIES) throw new IOException("OLE directory entry limit exceeded");
      String n = e.getName().toLowerCase(Locale.ROOT);
      if (n.contains("vba") || n.equals("macros") || n.equals("project")
          || n.equals("projectwm") || n.contains("digitalsignature")
          || n.equals("signatures") || n.equals("encryptioninfo")
          || n.equals("encryptedpackage") || n.equals("encryptedsummary"))
        throw new IOException("Macro-enabled, signed, or encrypted legacy Office files are not previewed");
      if (e.isDirectoryEntry()) scanDirectory((DirectoryEntry) e, depth + 1, entries, total);
      else {
        int size = ((DocumentEntry) e).getSize();
        total[0] += size;
        if (size < 0 || size > MAX_INPUT_BYTES || total[0] > MAX_INPUT_BYTES)
          throw new IOException("OLE stream size limit exceeded");
      }
    }
  }

  private static Result word(POIFSFileSystem fs) throws IOException {
    try (HWPFDocument doc = new HWPFDocument(fs)) {
      if (doc.getEncryptionInfo() != null) throw new IOException("Encrypted DOC is not previewed");
      StringBuilder source = doc.getText();
      if (source.length() > MAX_TEXT_CHARS) throw new IOException("Legacy text limit exceeded");
      // Text pieces preserve text without invoking the desktop-oriented Word layout/converter API.
      String text = source.toString().replace('\r', '\n').replace('\u0007', '\t');
      return result("Extracted document text", text,
          "Read-only extracted text. Original formatting, fields, images, and page layout are not reproduced.");
    }
  }

  private static Result excel(POIFSFileSystem fs) throws IOException {
    XlsText listener = new XlsText();
    HSSFRequest request = new HSSFRequest();
    request.addListenerForAllRecords(listener);
    new HSSFEventFactory().processWorkbookEvents(request, fs);
    return result("Extracted workbook text", listener.out.toString(),
        "Read-only raw cell values with row/column addresses. Dates remain serial numbers; formatting is omitted. Formula results are cached and may be stale; formulas are never recalculated.");
  }

  private static final class XlsText implements HSSFListener {
    final StringBuilder out = new StringBuilder();
    SSTRecord strings;
    int records, sheet, pendingRow = -1, pendingCol;
    boolean inSheet;
    @Override public void processRecord(org.apache.poi.hssf.record.Record r) {
      if (++records > MAX_RECORDS) throw new Limit("Legacy record limit exceeded");
      if (r instanceof FilePassRecord) throw new Limit("Encrypted XLS is not previewed");
      if (r instanceof BOFRecord) {
        int type = ((BOFRecord) r).getType();
        if (type == BOFRecord.TYPE_VB_MODULE || type == BOFRecord.TYPE_EXCEL_4_MACRO)
          throw new Limit("Macro worksheets are not previewed");
        inSheet = type == BOFRecord.TYPE_WORKSHEET;
        if (inSheet) append(out, "\nWorksheet " + (++sheet) + "\n");
      } else if (r instanceof BoundSheetRecord) {
        append(out, "Sheet name: " + ((BoundSheetRecord) r).getSheetname() + "\n");
      } else if (r instanceof SSTRecord) {
        strings = (SSTRecord) r;
        if (strings.getNumUniqueStrings() > MAX_RECORDS) throw new Limit("Shared string limit exceeded");
        long chars = 0;
        for (int i = 0; i < strings.getNumUniqueStrings(); i++) {
          chars += strings.getString(i).getString().length();
          if (chars > MAX_TEXT_CHARS) throw new Limit("Shared string text limit exceeded");
        }
      } else if (inSheet) {
        if (r instanceof LabelSSTRecord) {
          LabelSSTRecord c = (LabelSSTRecord) r;
          if (strings == null || c.getSSTIndex() < 0 || c.getSSTIndex() >= strings.getNumUniqueStrings())
            throw new Limit("Invalid shared string index");
          cell(c.getRow(), c.getColumn(), strings.getString(c.getSSTIndex()).getString());
        } else if (r instanceof LabelRecord) {
          LabelRecord c = (LabelRecord) r; cell(c.getRow(), c.getColumn(), c.getValue());
        } else if (r instanceof NumberRecord) {
          NumberRecord c = (NumberRecord) r; cell(c.getRow(), c.getColumn(), Double.toString(c.getValue()));
        } else if (r instanceof RKRecord) {
          RKRecord c = (RKRecord) r; cell(c.getRow(), c.getColumn(), Double.toString(c.getRKNumber()));
        } else if (r instanceof MulRKRecord) {
          MulRKRecord c = (MulRKRecord) r;
          for (int i = 0; i < c.getNumColumns(); i++) cell(c.getRow(), c.getFirstColumn() + i, Double.toString(c.getRKNumberAt(i)));
        } else if (r instanceof BoolErrRecord) {
          BoolErrRecord c = (BoolErrRecord) r;
          cell(c.getRow(), c.getColumn(), c.isBoolean() ? Boolean.toString(c.getBooleanValue()) : "Error " + c.getErrorValue());
        } else if (r instanceof FormulaRecord) {
          FormulaRecord c = (FormulaRecord) r;
          if (c.hasCachedResultString()) { pendingRow = c.getRow(); pendingCol = c.getColumn(); }
          else {
            String value;
            switch (c.getCachedResultTypeEnum()) {
              case BOOLEAN: value = Boolean.toString(c.getCachedBooleanValue()); break;
              case ERROR: value = "Error " + c.getCachedErrorValue(); break;
              case NUMERIC: value = Double.toString(c.getValue()); break;
              default: value = "";
            }
            cell(c.getRow(), c.getColumn(), value + " [cached formula]");
          }
        } else if (r instanceof StringRecord && pendingRow >= 0) {
          cell(pendingRow, pendingCol, ((StringRecord) r).getString() + " [cached formula]"); pendingRow = -1;
        }
      }
    }
    private void cell(int row, int col, String value) {
      append(out, "R" + (row + 1) + "C" + (col + 1) + "\t"); append(out, value); append(out, "\n");
    }
  }

  private static Result presentation(POIFSFileSystem fs) throws IOException {
    if (new CurrentUserAtom(fs.getRoot()).isEncrypted()) throw new IOException("Encrypted PPT is not previewed");
    DocumentEntry entry = (DocumentEntry) fs.getRoot().getEntry("PowerPoint Document");
    byte[] stream;
    try (DocumentInputStream in = new DocumentInputStream(entry)) {
      stream = IOUtils.toByteArray(in, entry.getSize(), MAX_INPUT_BYTES);
    }
    validatePptRecords(stream);
    QuickButCruddyTextExtractor extractor = new QuickButCruddyTextExtractor(fs);
    BoundedStrings text = new BoundedStrings();
    try {
      int pos = 0, records = 0;
      while (pos >= 0) {
        if (++records > MAX_RECORDS || pos > stream.length - 8) throw new IOException("PPT record limit exceeded");
        int next = extractor.findTextRecords(pos, text);
        if (next != -1 && (next <= pos || next > stream.length - 8)) throw new IOException("Invalid PPT record traversal");
        pos = next;
      }
    } finally { extractor.close(); }
    StringBuilder out = new StringBuilder();
    for (String s : text) { append(out, s); append(out, "\n"); }
    return result("Extracted presentation text", out.toString(),
        "Read-only unstructured text. Slide order, layout, images, and animations are not reproduced. Master, hidden, repeated, and historical text may be included.");
  }

  /** Header-boundary validation only; Apache POI performs all record and text decoding. */
  private static void validatePptRecords(byte[] bytes) throws IOException {
    int[] ends = new int[MAX_DEPTH + 1]; ends[0] = bytes.length;
    int depth = 0, pos = 0, count = 0;
    while (pos < bytes.length) {
      while (depth > 0 && pos == ends[depth]) depth--;
      if (++count > MAX_RECORDS || ends[depth] - pos < 8) throw new IOException("Invalid PPT record boundary");
      long len = LittleEndian.getUInt(bytes, pos + 4), end = (long) pos + 8 + len;
      if (end > ends[depth]) throw new IOException("PPT record exceeds its container");
      int type = LittleEndian.getUShort(bytes, pos + 2);
      if (type == RecordTypes.DocumentEncryptionAtom.typeID) throw new IOException("Encrypted PPT is not previewed");
      if ((bytes[pos] & 15) == 15 && len > 0) {
        if (++depth > MAX_DEPTH) throw new IOException("PPT nesting limit exceeded");
        ends[depth] = (int) end; pos += 8;
      } else pos = (int) end;
    }
  }

  private static final class BoundedStrings extends ArrayList<String> {
    int chars;
    @Override public boolean add(String value) {
      if (size() >= MAX_RECORDS || value.length() > MAX_TEXT_CHARS - chars - 1)
        throw new Limit("Legacy text limit exceeded");
      chars += value.length() + 1; return super.add(value);
    }
  }
  private static void append(StringBuilder b, String text) {
    if (text.length() > MAX_TEXT_CHARS - b.length()) throw new Limit("Legacy text limit exceeded");
    b.append(text);
  }
  private static Result result(String title, String text, String warning) {
    return new Result(Collections.singletonList(new Section(title, text)), warning);
  }
  private static final class Limit extends RuntimeException {
    Limit(String message) { super(message); }
  }
}
