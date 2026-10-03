package org.smallthingz.alldoc.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hslf.record.PersistPtrHolder;
import org.apache.poi.hslf.record.SlidePersistAtom;
import org.apache.poi.hslf.record.SSSlideInfoAtom;
import org.apache.poi.hslf.record.TextBytesAtom;
import org.apache.poi.hslf.record.TextCharsAtom;
import org.apache.poi.hslf.record.UserEditAtom;
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
    CurrentUserAtom current = new CurrentUserAtom(fs.getRoot());
    if (current.isEncrypted()) throw new IOException("Encrypted PPT is not previewed");
    DocumentEntry entry = (DocumentEntry) fs.getRoot().getEntry("PowerPoint Document");
    byte[] stream;
    try (DocumentInputStream in = new DocumentInputStream(entry)) {
      stream = IOUtils.toByteArray(in, entry.getSize(), MAX_INPUT_BYTES);
    }
    return new PptText(stream).extract(current.getCurrentEditOffset());
  }

  /**
   * Resolve live slides as specified by MS-PPT 2.1.2, instead of scanning every saved edit.
   * POI handles OLE and individual record/text decoding; only bounded record envelopes and
   * relationships are traversed here. Never construct the desktop-oriented HSLF slide model.
   */
  private static final class PptText {
    private static final int CLIENT_TEXTBOX = 0xf00d;
    private static final String WARNING = "Read-only text from current slides in presentation order. "
        + "Hidden slides are labeled. Text order within a slide may differ; master text, notes, "
        + "images, formatting, and animations are omitted.";
    final byte[] data;
    final Map<Integer, PptRecord> roots = new HashMap<>();
    int records, pointers, chars;

    PptText(byte[] data) throws IOException {
      this.data = data;
      for (PptRecord r : readRecords(0, data.length, 0)) roots.put(r.start, r);
    }

    Result extract(long currentOffset) throws IOException {
      if (currentOffset <= 0 || currentOffset > Integer.MAX_VALUE)
        throw new IOException("Invalid PPT current edit reference");
      Map<Integer, Integer> persisted = new HashMap<>();
      Set<Integer> edits = new HashSet<>();
      int editOffset = (int) currentOffset, documentId = -1;
      while (editOffset != 0) {
        if (!edits.add(editOffset) || edits.size() > MAX_RECORDS)
          throw new IOException("Invalid PPT edit chain");
        PptRecord edit = root(editOffset, RecordTypes.UserEditAtom.typeID);
        if (edit.options != 0 || (edit.length() != 28 && edit.length() != 32))
          throw new IOException("Invalid PPT edit record");
        UserEditAtom user = (UserEditAtom) atom(edit);
        if (user.getEncryptSessionPersistIdRef() != -1)
          throw new IOException("Encrypted PPT is not previewed");
        if (edits.size() == 1) {
          documentId = user.getDocPersistRef();
          if (documentId <= 0 || documentId > 0xfffff)
            throw new IOException("Invalid PPT document persist reference");
        }
        int pointerOffset = user.getPersistPointersOffset();
        PptRecord ptr = roots.get(pointerOffset);
        if (ptr == null || pointerOffset >= editOffset || ptr.options != 0
            || (ptr.type != RecordTypes.PersistPtrFullBlock.typeID
                && ptr.type != RecordTypes.PersistPtrIncrementalBlock.typeID))
          throw new IOException("Invalid PPT persist directory reference");
        validatePointers(ptr);
        PersistPtrHolder holder = (PersistPtrHolder) atom(ptr);
        for (Map.Entry<Integer, Integer> p : holder.getSlideLocationsLookup().entrySet())
          persisted.putIfAbsent(p.getKey(), p.getValue()); // Newest edit wins.
        int previous = user.getLastUserEditAtomOffset();
        if (previous < 0 || previous >= editOffset)
          throw new IOException("Invalid PPT previous edit reference");
        editOffset = previous;
      }
      PptRecord document = resolve(persisted, documentId, RecordTypes.Document.typeID);
      PptRecord slides = null;
      for (PptRecord r : document.children) {
        if (r.type == RecordTypes.SlideListWithText.typeID && r.instance() == 0) {
          if (slides != null || !r.isContainer()) throw new IOException("Invalid PPT slide list");
          slides = r;
        }
      }
      List<Section> sections = new ArrayList<>();
      Set<Integer> usedSlides = new HashSet<>();
      if (slides != null) {
        for (int i = 0; i < slides.children.size();) {
          PptRecord ref = slides.children.get(i++);
          if (ref.type != RecordTypes.SlidePersistAtom.typeID || ref.length() < 20
              || ref.length() > 32 || ref.isContainer())
            throw new IOException("Invalid PPT slide reference");
          SlidePersistAtom spa = (SlidePersistAtom) atom(ref);
          PptRecord slide = resolve(persisted, spa.getRefID(), RecordTypes.Slide.typeID);
          if (!usedSlides.add(slide.start)) throw new IOException("Duplicate PPT slide reference");
          StringBuilder text = new StringBuilder();
          int textHeaders = 0;
          while (i < slides.children.size()
              && slides.children.get(i).type != RecordTypes.SlidePersistAtom.typeID) {
            PptRecord r = slides.children.get(i++);
            if (r.type == RecordTypes.TextHeaderAtom.typeID) {
              if (r.length() != 4) throw new IOException("Invalid PPT text header");
              textHeaders++;
            }
            addText(r, text);
          }
          boolean hidden = false;
          for (PptRecord r : slide.children) {
            if (r.type == RecordTypes.SSSlideInfoAtom.typeID) {
              if (r.length() != 16 || r.options != 0)
                throw new IOException("Invalid PPT slide visibility record");
              hidden |= ((SSSlideInfoAtom) atom(r)).getEffectTransitionFlagByBit(SSSlideInfoAtom.HIDDEN_BIT);
            } else if (r.type == RecordTypes.PPDrawing.typeID) {
              if (!r.isContainer()) throw new IOException("Invalid PPT drawing container");
              addTextBoxes(r, text, textHeaders);
            }
          }
          sections.add(new Section("Slide " + (sections.size() + 1) + (hidden ? " (hidden)" : ""), text.toString()));
        }
      }
      if (sections.isEmpty()) sections.add(new Section("Presentation", ""));
      return new Result(sections, WARNING);
    }

    private PptRecord root(int offset, int type) throws IOException {
      PptRecord r = roots.get(offset);
      if (r == null || r.type != type) throw new IOException("Invalid PPT record reference");
      return r;
    }

    private PptRecord resolve(Map<Integer, Integer> persisted, int id, int type) throws IOException {
      Integer offset = persisted.get(id);
      if (id <= 0 || id > 0xfffff || offset == null) throw new IOException("Missing PPT persist reference");
      PptRecord r = root(offset, type);
      if (!r.isContainer()) throw new IOException("Invalid PPT persist container");
      return r;
    }

    private void validatePointers(PptRecord ptr) throws IOException {
      Set<Integer> ids = new HashSet<>();
      int pos = ptr.start + 8;
      while (pos < ptr.end) {
        if (ptr.end - pos < 4) throw new IOException("Truncated PPT persist directory");
        int info = LittleEndian.getInt(data, pos), id = info & 0xfffff, count = info >>> 20;
        pos += 4;
        if (id == 0 || count == 0 || count > (ptr.end - pos) / 4 || id + count > 0x100000
            || (pointers += count) > MAX_RECORDS)
          throw new IOException("Invalid PPT persist directory bounds");
        for (int n = 0; n < count; n++, pos += 4) {
          int offset = LittleEndian.getInt(data, pos);
          if (!ids.add(id + n) || offset >= ptr.start || !roots.containsKey(offset))
            throw new IOException("Invalid PPT persist object reference");
        }
      }
    }

    private void addTextBoxes(PptRecord r, StringBuilder text, int textHeaders) throws IOException {
      if (r.type == CLIENT_TEXTBOX) {
        for (PptRecord child : r.children) {
          if (child.type == RecordTypes.OutlineTextRefAtom.typeID) {
            if (child.length() != 4) throw new IOException("Invalid PPT outline text reference");
            int index = LittleEndian.getInt(data, child.start + 8);
            if (index < 0 || index >= textHeaders) throw new IOException("Missing PPT outline text reference");
            // Already included from this slide's SlideListWithText records; don't duplicate it.
          } else addText(child, text);
        }
      } else {
        // Only traverse OfficeArt containers. Client data/animation metadata is not slide text.
        for (PptRecord child : r.children)
          if (child.type == CLIENT_TEXTBOX || (child.type >= 0xf000 && child.isContainer()))
            addTextBoxes(child, text, textHeaders);
      }
    }

    private void addText(PptRecord r, StringBuilder out) throws IOException {
      String value;
      if (r.type == RecordTypes.TextBytesAtom.typeID) {
        if (r.isContainer()) throw new IOException("Invalid PPT text record");
        value = ((TextBytesAtom) atom(r)).getText();
      } else if (r.type == RecordTypes.TextCharsAtom.typeID) {
        if (r.isContainer() || (r.length() & 1) != 0) throw new IOException("Invalid PPT Unicode text record");
        value = ((TextCharsAtom) atom(r)).getText();
      } else return;
      if (value.length() > MAX_TEXT_CHARS - chars - 1) throw new IOException("Legacy text limit exceeded");
      chars += value.length() + 1;
      append(out, value.replace('\r', '\n').replace('\u000b', '\n'));
      append(out, "\n");
    }

    private org.apache.poi.hslf.record.Record atom(PptRecord r) {
      return org.apache.poi.hslf.record.Record.createRecordForType(r.type, data, r.start, r.end - r.start);
    }

    private List<PptRecord> readRecords(int start, int end, int depth) throws IOException {
      if (depth > MAX_DEPTH) throw new IOException("PPT nesting limit exceeded");
      List<PptRecord> result = new ArrayList<>();
      for (int pos = start; pos < end;) {
        if (++records > MAX_RECORDS || end - pos < 8) throw new IOException("Invalid PPT record boundary");
        long recordEnd = (long) pos + 8 + LittleEndian.getUInt(data, pos + 4);
        if (recordEnd > end) throw new IOException("PPT record exceeds its container");
        PptRecord r = new PptRecord(pos, (int) recordEnd,
            LittleEndian.getUShort(data, pos + 2), LittleEndian.getUShort(data, pos));
        if (r.type == RecordTypes.DocumentEncryptionAtom.typeID)
          throw new IOException("Encrypted PPT is not previewed");
        if (r.type == RecordTypes.VBAInfoAtom.typeID
            && (r.length() < 12 || LittleEndian.getUInt(data, pos + 12) != 0))
          throw new IOException("Macro-enabled PPT is not previewed");
        if (r.isContainer() || r.type == CLIENT_TEXTBOX)
          r.children = readRecords(pos + 8, r.end, depth + 1);
        result.add(r);
        pos = r.end;
      }
      return result;
    }
  }

  private static final class PptRecord {
    final int start, end, type, options;
    List<PptRecord> children = Collections.emptyList();
    PptRecord(int start, int end, int type, int options) {
      this.start = start; this.end = end; this.type = type; this.options = options;
    }
    int length() { return end - start - 8; }
    int instance() { return options >>> 4; }
    boolean isContainer() { return (options & 15) == 15; }
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
