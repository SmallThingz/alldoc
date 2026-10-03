/* Repository-authored fixture generator. Run with Apache POI 5.5.1 on a desktop JDK.
 * This generator may use HSLF/AWT; the Android extractor never uses the slide model.
 */
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.hslf.record.CurrentUserAtom;
import org.apache.poi.hslf.record.PersistPtrHolder;
import org.apache.poi.hslf.record.UserEditAtom;
import org.apache.poi.hslf.record.Record;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.poifs.filesystem.*;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.LittleEndian;
import org.apache.poi.hslf.record.Document;
import org.apache.poi.hslf.record.Slide;
import org.apache.poi.hslf.record.SlideListWithText;
import org.apache.poi.hslf.record.EscherTextboxWrapper;
import org.apache.poi.hslf.record.RecordTypes;

public final class GenerateLegacyPptFixture {
  public static void main(String[] args) throws Exception {
    byte[] oldFile, currentFile;
    try (HSLFSlideShow deck = new HSLFSlideShow()) {
      HSLFSlide first = deck.createSlide();
      HSLFTextBox title = first.addTitle(); title.setText("STALE REVISION: superseded title");
      HSLFTextBox body = new HSLFTextBox();
      body.setText("Ordinary body text\\nwith two lines".replace("\\n", "\r")); first.addShape(body);
      HSLFSlide hidden = deck.createSlide();
      hidden.addTitle().setText("Hidden current slide"); hidden.setHidden(true);
      deck.createSlide().addTitle().setText("DELETED SLIDE: no longer in current list");
      deck.createSlide().addTitle().setText("Click to edit Master title style");
      ByteArrayOutputStream out = new ByteArrayOutputStream(); deck.write(out); oldFile = out.toByteArray();
      title.setText("CURRENT REVISION: café, Ελληνικά, 日本語");
      deck.reorderSlide(2, 1); // Hidden slide first, different from old physical order.
      deck.removeSlide(2); // Delete third slide, leaving its old persisted bytes in oldFile.
      out.reset(); deck.write(out); currentFile = out.toByteArray();
    }
    try (POIFSFileSystem oldFs = new POIFSFileSystem(new ByteArrayInputStream(oldFile));
         POIFSFileSystem currentFs = new POIFSFileSystem(new ByteArrayInputStream(currentFile))) {
      byte[] oldStream = stream(oldFs), currentStream = stream(currentFs);
      CurrentUserAtom oldUser = new CurrentUserAtom(oldFs.getRoot());
      CurrentUserAtom current = new CurrentUserAtom(currentFs.getRoot());
      UserEditAtom edit = (UserEditAtom) Record.buildRecordAtOffset(currentStream, (int) current.getCurrentEditOffset());
      PersistPtrHolder pointers = (PersistPtrHolder) Record.buildRecordAtOffset(currentStream, edit.getPersistPointersOffset());
      Map<Integer, Integer> offsets = new TreeMap<>(pointers.getSlideLocationsLookup());
      ByteArrayOutputStream joined = new ByteArrayOutputStream(); joined.write(oldStream); joined.write(currentStream);
      // Move the hidden slide title to the document's shared outline-text list, and use
      // a real OutlineTextRefAtom in its shape. This exercises both supported storage forms.
      Document doc = (Document) Record.buildRecordAtOffset(currentStream, offsets.get(edit.getDocPersistRef()));
      SlideListWithText list = doc.getSlideSlideListWithText();
      int hiddenId = list.getSlideAtomsSets()[0].getSlidePersistAtom().getRefID();
      Slide hidden = (Slide) Record.buildRecordAtOffset(currentStream, offsets.get(hiddenId));
      EscherTextboxWrapper textbox = hidden.getPPDrawing().getTextboxWrappers()[0];
      List<Record> children = new ArrayList<>();
      Record[] original = list.getChildRecords();
      ByteArrayOutputStream spaBytes = new ByteArrayOutputStream(); original[0].writeOut(spaBytes);
      byte[] spa = spaBytes.toByteArray(); LittleEndian.putInt(spa, 16, 1);
      children.add(Record.buildRecordAtOffset(spa, 0));
      children.addAll(Arrays.asList(textbox.getChildRecords()));
      children.addAll(Arrays.asList(original).subList(1, original.length));
      list.setChildRecord(children.toArray(new Record[0]));
      byte[] outline = new byte[12];
      LittleEndian.putUShort(outline, 2, RecordTypes.OutlineTextRefAtom.typeID);
      LittleEndian.putInt(outline, 4, 4);
      textbox.setChildRecord(new Record[] {Record.buildRecordAtOffset(outline, 0)});
      // OfficeArtClientTextbox in MS-PPT commonly uses recVer 0 rather than POI's 0xF.
      textbox.getEscherRecord().setOptions((short) 0);
      int documentOffset = joined.size(); doc.writeOut(joined);
      int hiddenOffset = joined.size(); hidden.writeOut(joined);
      pointers.clear();
      for (Map.Entry<Integer, Integer> p : offsets.entrySet()) {
        int offset = p.getKey() == edit.getDocPersistRef() ? documentOffset
            : p.getKey() == hiddenId ? hiddenOffset : oldStream.length + p.getValue();
        pointers.addSlideLookup(p.getKey(), offset);
      }
      edit.setLastUserEditAtomOffset((int) oldUser.getCurrentEditOffset());
      edit.setPersistPointersOffset(joined.size()); pointers.writeOut(joined);
      current.setCurrentEditOffset(joined.size()); edit.writeOut(joined);
      current.writeToFS(currentFs);
      currentFs.getRoot().createOrUpdateDocument("PowerPoint Document", new ByteArrayInputStream(joined.toByteArray()));
      ByteArrayOutputStream out = new ByteArrayOutputStream(); currentFs.writeFilesystem(out);
      Files.write(Paths.get(args.length == 0 ? "fixtures/legacy-current-slides.ppt" : args[0]), out.toByteArray());
      // Independent full-model check used only when generating this fixture.
      try (HSLFSlideShow verify = new HSLFSlideShow(new ByteArrayInputStream(out.toByteArray()))) {
        if (verify.getSlides().size() != 3 || !verify.getSlides().get(0).isHidden()
            || !verify.getSlides().get(0).getTitle().equals("Hidden current slide")
            || !verify.getSlides().get(1).getTitle().startsWith("CURRENT REVISION:"))
          throw new AssertionError("Fixture's current presentation did not round-trip");
        System.out.println("Generated three current slides, with real master placeholders, a hidden slide, and retained edit history");
      }
    }
  }
  private static byte[] stream(POIFSFileSystem fs) throws IOException {
    try (DocumentInputStream in = fs.createDocumentInputStream("PowerPoint Document")) { return IOUtils.toByteArray(in); }
  }
}
