package org.smallthingz.alldoc.core;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.hslf.record.CurrentUserAtom;
import org.apache.poi.hslf.record.PersistPtrHolder;
import org.apache.poi.hslf.record.RecordTypes;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.LittleEndian;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.poifs.filesystem.*;
import org.junit.Test;

public class LegacyOfficeExtractorTest {
  private static byte[] fixture(String ext) throws IOException {
    return namedFixture("legacy-basic." + ext);
  }
  private static byte[] namedFixture(String name) throws IOException {
    Path root = Paths.get("fixtures");
    if (!Files.exists(root)) root = Paths.get("..", "fixtures");
    return Files.readAllBytes(root.resolve(name));
  }
  private static String text(String ext, byte[] data) throws IOException {
    return LegacyOfficeExtractor.extract(ext, data).sections.get(0).text;
  }
  @Test public void extractsWordText() throws Exception {
    assertTrue(text("doc", fixture("doc")).contains("simple file created with Word"));
  }
  @Test public void extractsRawExcelCells() throws Exception {
    String t = text("xls", fixture("xls"));
    assertTrue(t.contains("Hello XLS")); assertTrue(t.contains("R1C2\t42.25"));
    assertTrue(t.contains("[cached formula]"));
  }
  @Test public void extractsCurrentPresentationTextWithoutMasters() throws Exception {
    LegacyOfficeExtractor.Result result = LegacyOfficeExtractor.extract("ppt", fixture("ppt"));
    assertEquals("Hello PPT\n", result.sections.get(0).text);
    assertEquals("Slide 1", result.sections.get(0).title);
    assertTrue(result.warning.contains("master text"));
    assertTrue(result.warning.contains("omitted"));
  }
  @Test public void resolvesNewestSlidesInPresentationOrderAndLabelsHiddenSlides() throws Exception {
    byte[] source = namedFixture("legacy-current-slides.ppt"), original = source.clone();
    LegacyOfficeExtractor.Result result = LegacyOfficeExtractor.extract("ppt", source);
    assertArrayEquals(original, source);
    assertEquals(3, result.sections.size());
    assertEquals("Slide 1 (hidden)", result.sections.get(0).title);
    assertEquals("Hidden current slide\n", result.sections.get(0).text); // Outline ref must not duplicate text.
    assertEquals("Slide 2", result.sections.get(1).title);
    assertEquals("CURRENT REVISION: café, Ελληνικά, 日本語\nOrdinary body text\nwith two lines\n",
        result.sections.get(1).text);
    // A user-authored title that looks exactly like template boilerplate must be preserved.
    assertEquals("Click to edit Master title style\n", result.sections.get(2).text);
    String raw = new String(pptStream(source), StandardCharsets.ISO_8859_1);
    assertTrue(raw.contains("STALE REVISION:"));
    assertTrue(raw.contains("DELETED SLIDE:"));
    assertTrue(raw.contains("Click to edit Master text styles"));
  }

  @Test public void refusesInvalidEditAndDirectoryRelationships() throws Exception {
    rejectsMutation((data, current) -> current.setCurrentEditOffset(0), "current edit");
    rejectsMutation((data, current) -> current.setCurrentEditOffset(0xffffffffL), "current edit");
    rejectsMutation((data, current) -> current.setCurrentEditOffset(1), "record reference");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, (int) current.getCurrentEditOffset() + 24, -1),
        "document persist reference");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, (int) current.getCurrentEditOffset() + 16,
        (int) current.getCurrentEditOffset()), "previous edit");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, (int) current.getCurrentEditOffset() + 20,
        (int) current.getCurrentEditOffset()), "directory reference");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, directory(data, current) + 12, 1), "object reference");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, directory(data, current) + 8, 0xfff00001), "directory bounds");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, directory(data, current) + 8, 1), "directory bounds");
  }

  @Test public void refusesMissingDuplicateAndWrongTypeSlideReferences() throws Exception {
    rejectsMutation((data, current) -> LittleEndian.putInt(data, slideRefs(data, current).get(0) + 8, 0xfffff), "persist reference");
    rejectsMutation((data, current) -> {
      List<Integer> refs = slideRefs(data, current);
      LittleEndian.putInt(data, refs.get(1) + 8, LittleEndian.getInt(data, refs.get(0) + 8));
    }, "Duplicate PPT slide");
    rejectsMutation((data, current) -> LittleEndian.putInt(data, slideRefs(data, current).get(0) + 8,
        LittleEndian.getInt(data, (int) current.getCurrentEditOffset() + 24)), "record reference");
    rejectsMutation((data, current) -> {
      int outline = descendants(data, 0, data.length, RecordTypes.OutlineTextRefAtom.typeID).get(0);
      LittleEndian.putInt(data, outline + 8, Integer.MAX_VALUE);
    }, "outline text reference");
  }

  @Test public void refusesNestedBoundariesDepthRecordCountAndEmbeddedMacros() throws Exception {
    byte[] nested = record(0, 1, new byte[0]);
    for (int i = 0; i < 66; i++) nested = record(15, 2, nested);
    rejectsPpt(rawPpt(nested, 0), "nesting limit");
    ByteArrayOutputStream many = new ByteArrayOutputStream();
    for (int i = 0; i < 100_001; i++) many.write(record(0, 1, new byte[0]));
    rejectsPpt(rawPpt(many.toByteArray(), 0), "record boundary");
    byte[] container = record(15, 2, record(0, 1, new byte[0]));
    LittleEndian.putInt(container, 12, 1); // Child extends past its parent, inside the outer stream.
    rejectsPpt(rawPpt(concat(container, record(0, 1, new byte[8])), 0), "exceeds its container");
    byte[] macro = new byte[12]; LittleEndian.putInt(macro, 4, 1);
    rejectsPpt(rawPpt(record(2, RecordTypes.VBAInfoAtom.typeID, macro), 0), "Macro-enabled");
    rejectsPpt(rawPpt(record(0, RecordTypes.DocumentEncryptionAtom.typeID, new byte[0]), 0), "Encrypted PPT");
  }

  @Test public void boundsTextAcrossSlidesAndRestoresPoiAllocationGuard() throws Exception {
    int previous = IOUtils.getByteArrayMaxOverride();
    String large = String.join("", Collections.nCopies(600_000, "x"));
    byte[] source = minimalPpt(large, large);
    assertTrue(source.length < LegacyOfficeExtractor.MAX_INPUT_BYTES);
    rejectsPpt(source, "Legacy text limit");
    assertEquals(previous, IOUtils.getByteArrayMaxOverride());
    assertEquals(2, LegacyOfficeExtractor.extract("ppt", minimalPpt("same", "same")).sections.size());
  }

  @Test public void refusesOddUnicodeTextBeforePoiDecoding() throws Exception {
    byte[] source = minimalPpt("odd"), data = pptStream(source);
    int text = descendants(data, 0, data.length, RecordTypes.TextBytesAtom.typeID).get(0);
    LittleEndian.putUShort(data, text + 2, RecordTypes.TextCharsAtom.typeID);
    try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(source))) {
      int edit = (int) new CurrentUserAtom(fs.getRoot()).getCurrentEditOffset();
      rejectsPpt(rawPpt(data, edit), "Unicode text record");
    }
  }

  @Test public void refusesDuplicateIdsInsidePersistDirectory() throws Exception {
    byte[] doc = record(15, RecordTypes.Document.typeID, new byte[0]);
    byte[] pair = new byte[8]; LittleEndian.putInt(pair, 0, 0x100001);
    byte[] ptr = record(0, RecordTypes.PersistPtrIncrementalBlock.typeID, concat(pair, pair));
    byte[] edit = edit(doc.length);
    rejectsPpt(rawPpt(concat(doc, ptr, edit), doc.length + ptr.length), "object reference");
  }

  @Test public void rejectsMalformedAndOversize() throws Exception {
    rejects("doc", new byte[512]);
    rejects("xls", new byte[LegacyOfficeExtractor.MAX_INPUT_BYTES + 1]);
  }
  @Test public void sectionsAreImmutable() throws Exception {
    try { LegacyOfficeExtractor.extract("doc", fixture("doc")).sections.clear(); fail(); }
    catch (UnsupportedOperationException expected) { }
  }
  @Test public void refusesKnownMacroSignatureAndEncryptionStreams() throws Exception {
    for (String name : new String[] {"VBA", "_VBA_PROJECT_CUR", "Macros", "PROJECT", "\005DigitalSignature", "EncryptedPackage", "EncryptionInfo"}) {
      try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(fixture("xls")))) {
        fs.createDocument(new ByteArrayInputStream(new byte[] {1}), name);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); fs.writeFilesystem(out);
        rejects("xls", out.toByteArray());
      }
    }
  }
  @Test public void refusesActualEncryptedBiffEvenWithDefaultPassword() throws Exception {
    try (HSSFWorkbook wb = new HSSFWorkbook()) {
      wb.createSheet("Secret").createRow(0).createCell(0).setCellValue("Encrypted cells");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword("VelvetSweatshop");
      try { wb.write(out); }
      finally { org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(null); }
      rejects("xls", out.toByteArray());
    }
  }
  @Test public void refusesEncryptedPowerPointHeader() throws Exception {
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      CurrentUserAtom current = new CurrentUserAtom(); current.setEncrypted(true); current.writeToFS(fs);
      fs.createDocument(new ByteArrayInputStream(new byte[8]), "PowerPoint Document");
      ByteArrayOutputStream out = new ByteArrayOutputStream(); fs.writeFilesystem(out);
      rejects("ppt", out.toByteArray());
    }
  }
  @Test public void refusesOutOfBoundsPowerPointRecord() throws Exception {
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      new CurrentUserAtom().writeToFS(fs);
      byte[] record = new byte[] {0, 0, 1, 0, -1, -1, -1, 127};
      fs.createDocument(new ByteArrayInputStream(record), "PowerPoint Document");
      ByteArrayOutputStream out = new ByteArrayOutputStream(); fs.writeFilesystem(out);
      rejects("ppt", out.toByteArray());
    }
  }
  @Test public void refusesExcessiveExtractedTextBeforeReturningIt() throws Exception {
    try (HSSFWorkbook wb = new HSSFWorkbook()) {
      HSSFSheet s = wb.createSheet("Limits");
      String value = String.join("", Collections.nCopies(100, "x"));
      for (int i = 0; i < 11_000; i++) s.createRow(i).createCell(0).setCellValue(value);
      ByteArrayOutputStream out = new ByteArrayOutputStream(); wb.write(out);
      rejects("xls", out.toByteArray());
    }
  }
  @Test public void usesNoDesktopGraphicsEvenWithCustomNumberFormats() throws Exception {
    LinkedHashSet<URL> locations = new LinkedHashSet<>();
    for (String name : new String[] {
      LegacyOfficeExtractor.class.getName(), "org.apache.poi.poifs.filesystem.POIFSFileSystem",
      "org.apache.poi.hwpf.HWPFDocument", "org.apache.logging.log4j.LogManager",
      "org.apache.commons.io.IOUtils", "org.apache.commons.collections4.ListUtils",
      "org.apache.commons.codec.CodecPolicy", "org.apache.commons.math3.util.FastMath",
      "com.zaxxer.sparsebits.SparseBitSet"
    }) locations.add(Class.forName(name).getProtectionDomain().getCodeSource().getLocation());
    try (URLClassLoader loader = new URLClassLoader(locations.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent()) {
      @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("java.awt.") || name.startsWith("javax.imageio.") || name.startsWith("javax.swing.")
            || name.startsWith("org.apache.poi.hslf.usermodel.") || name.startsWith("org.apache.poi.hslf.extractor."))
          throw new ClassNotFoundException("Unavailable on Android: " + name);
        return super.loadClass(name, resolve);
      }
    }) {
      Method m = loader.loadClass(LegacyOfficeExtractor.class.getName()).getMethod("extract", String.class, byte[].class);
      for (String ext : new String[] {"doc", "xls", "ppt"}) assertNotNull(m.invoke(null, ext, fixture(ext)));
      assertNotNull(m.invoke(null, "ppt", namedFixture("legacy-current-slides.ppt")));
      try (HSSFWorkbook wb = new HSSFWorkbook()) {
        HSSFCell cell = wb.createSheet("Formats").createRow(0).createCell(0); cell.setCellValue(-42.25);
        HSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("[Green]0.00;[Red]-0.00;[Blue]0.00")); cell.setCellStyle(style);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); wb.write(out);
        assertNotNull(m.invoke(null, "xls", out.toByteArray()));
      }
    }
  }
  private interface PptMutation { void apply(byte[] data, CurrentUserAtom current) throws Exception; }
  private static void rejectsMutation(PptMutation mutation, String message) throws Exception {
    byte[] source = namedFixture("legacy-current-slides.ppt");
    try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(source))) {
      byte[] data = pptStream(source);
      CurrentUserAtom current = new CurrentUserAtom(fs.getRoot());
      mutation.apply(data, current); current.writeToFS(fs);
      fs.getRoot().createOrUpdateDocument("PowerPoint Document", new ByteArrayInputStream(data));
      ByteArrayOutputStream out = new ByteArrayOutputStream(); fs.writeFilesystem(out);
      rejectsPpt(out.toByteArray(), message);
    }
  }
  private static int directory(byte[] data, CurrentUserAtom current) {
    return LittleEndian.getInt(data, (int) current.getCurrentEditOffset() + 20);
  }
  private static List<Integer> slideRefs(byte[] data, CurrentUserAtom current) {
    PersistPtrHolder ptr = (PersistPtrHolder) org.apache.poi.hslf.record.Record.buildRecordAtOffset(data, directory(data, current));
    int docId = LittleEndian.getInt(data, (int) current.getCurrentEditOffset() + 24);
    int doc = ptr.getSlideLocationsLookup().get(docId);
    for (int list : descendants(data, doc + 8, doc + 8 + LittleEndian.getInt(data, doc + 4), RecordTypes.SlideListWithText.typeID)) {
      if ((LittleEndian.getUShort(data, list) >>> 4) == 0)
        return descendants(data, list + 8, list + 8 + LittleEndian.getInt(data, list + 4), RecordTypes.SlidePersistAtom.typeID);
    }
    throw new AssertionError("Fixture has no slide list");
  }
  private static List<Integer> descendants(byte[] data, int start, int end, int type) {
    List<Integer> matches = new ArrayList<>();
    for (int pos = start; pos < end;) {
      int next = pos + 8 + LittleEndian.getInt(data, pos + 4);
      int recordType = LittleEndian.getUShort(data, pos + 2);
      if (recordType == type) matches.add(pos);
      if ((data[pos] & 15) == 15 || recordType == 0xf00d)
        matches.addAll(descendants(data, pos + 8, next, type));
      pos = next;
    }
    return matches;
  }
  private static byte[] pptStream(byte[] source) throws IOException {
    try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(source));
         DocumentInputStream in = fs.createDocumentInputStream("PowerPoint Document")) {
      return IOUtils.toByteArray(in);
    }
  }
  private static byte[] record(int options, int type, byte[] payload) throws IOException {
    byte[] header = new byte[8];
    LittleEndian.putUShort(header, 0, options); LittleEndian.putUShort(header, 2, type);
    LittleEndian.putInt(header, 4, payload.length); return concat(header, payload);
  }
  private static byte[] concat(byte[]... parts) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part);
    return out.toByteArray();
  }
  private static byte[] edit(int directoryOffset) throws IOException {
    byte[] payload = new byte[28];
    LittleEndian.putInt(payload, 12, directoryOffset); LittleEndian.putInt(payload, 16, 1);
    return record(0, RecordTypes.UserEditAtom.typeID, payload);
  }
  private static byte[] rawPpt(byte[] data, int currentOffset) throws IOException {
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      CurrentUserAtom current = new CurrentUserAtom(); current.setCurrentEditOffset(currentOffset); current.writeToFS(fs);
      fs.createDocument(new ByteArrayInputStream(data), "PowerPoint Document");
      ByteArrayOutputStream out = new ByteArrayOutputStream(); fs.writeFilesystem(out); return out.toByteArray();
    }
  }
  /** Small record harness for allocation limits, deliberately avoids the HSLF/AWT object model. */
  private static byte[] minimalPpt(String... texts) throws IOException {
    ByteArrayOutputStream refs = new ByteArrayOutputStream();
    for (int i = 0; i < texts.length; i++) {
      byte[] spa = new byte[20]; LittleEndian.putInt(spa, 0, i + 2); LittleEndian.putInt(spa, 12, i + 256);
      refs.write(record(0, RecordTypes.SlidePersistAtom.typeID, spa));
    }
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    data.write(record(15, RecordTypes.Document.typeID, record(15, RecordTypes.SlideListWithText.typeID, refs.toByteArray())));
    byte[] ptr = new byte[4 + 4 * (texts.length + 1)];
    LittleEndian.putInt(ptr, 0, ((texts.length + 1) << 20) | 1);
    for (int i = 0; i < texts.length; i++) {
      LittleEndian.putInt(ptr, 8 + i * 4, data.size());
      byte[] text = concat(record(0, RecordTypes.TextHeaderAtom.typeID, new byte[4]),
          record(0, RecordTypes.TextBytesAtom.typeID, texts[i].getBytes(StandardCharsets.ISO_8859_1)));
      data.write(record(15, RecordTypes.Slide.typeID, record(15, RecordTypes.PPDrawing.typeID,
          record(15, 0xf002, record(15, 0xf003, record(15, 0xf004, record(0, 0xf00d, text)))))));
    }
    int directoryOffset = data.size();
    data.write(record(0, RecordTypes.PersistPtrIncrementalBlock.typeID, ptr));
    int currentOffset = data.size(); data.write(edit(directoryOffset));
    return rawPpt(data.toByteArray(), currentOffset);
  }
  private static void rejectsPpt(byte[] data, String message) throws Exception {
    try { LegacyOfficeExtractor.extract("ppt", data); fail("Expected bounded PPT refusal"); }
    catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
  }
  private static void rejects(String ext, byte[] data) throws Exception {
    try { LegacyOfficeExtractor.extract(ext, data); fail("Expected bounded refusal"); }
    catch (IOException expected) { }
  }
}
