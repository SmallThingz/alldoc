package org.smallthingz.alldoc.core;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.hslf.record.CurrentUserAtom;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.poifs.filesystem.*;
import org.junit.Test;

public class LegacyOfficeExtractorTest {
  private static byte[] fixture(String ext) throws IOException {
    Path root = Paths.get("fixtures");
    if (!Files.exists(root)) root = Paths.get("..", "fixtures");
    return Files.readAllBytes(root.resolve("legacy-basic." + ext));
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
  @Test public void extractsUnstructuredPresentationText() throws Exception {
    LegacyOfficeExtractor.Result result = LegacyOfficeExtractor.extract("ppt", fixture("ppt"));
    assertTrue(result.sections.get(0).text.contains("Hello PPT"));
    assertTrue(result.warning.contains("historical text"));
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
        if (name.startsWith("java.awt.") || name.startsWith("javax.imageio.") || name.startsWith("javax.swing."))
          throw new ClassNotFoundException("Unavailable on Android: " + name);
        return super.loadClass(name, resolve);
      }
    }) {
      Method m = loader.loadClass(LegacyOfficeExtractor.class.getName()).getMethod("extract", String.class, byte[].class);
      for (String ext : new String[] {"doc", "xls", "ppt"}) assertNotNull(m.invoke(null, ext, fixture(ext)));
      try (HSSFWorkbook wb = new HSSFWorkbook()) {
        HSSFCell cell = wb.createSheet("Formats").createRow(0).createCell(0); cell.setCellValue(-42.25);
        HSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("[Green]0.00;[Red]-0.00;[Blue]0.00")); cell.setCellStyle(style);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); wb.write(out);
        assertNotNull(m.invoke(null, "xls", out.toByteArray()));
      }
    }
  }
  private static void rejects(String ext, byte[] data) throws Exception {
    try { LegacyOfficeExtractor.extract(ext, data); fail("Expected bounded refusal"); }
    catch (IOException expected) { }
  }
}
