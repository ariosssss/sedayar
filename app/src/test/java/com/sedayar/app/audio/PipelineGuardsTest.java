package com.sedayar.app.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/** Export formats + the model-directory guard that prevents native crashes. */
public class PipelineGuardsTest {

    // ---------------------------------------------------------- exporters

    private Transcript sample() {
        Transcript t = new Transcript();
        t.language = "fa";
        t.add(0, 2500, "سلام دنیا");
        t.add(3000, 8000, "این یک آزمایش است");
        return t;
    }

    @Test
    public void txtKeepsTimeStamps() {
        String out = TranscriptExporter.TXT.build("عنوان", sample(), null);
        assertTrue(out.contains("[00:00]"));
        assertTrue(out.contains("[00:03]"));
        assertTrue(out.contains("سلام دنیا"));
    }

    @Test
    public void srtTimecodesAreWellFormed() {
        String out = TranscriptExporter.SRT.build("t", sample(), null);
        assertTrue(out.contains("00:00:00,000 --> 00:00:02,500"));
        assertTrue(out.contains("00:00:03,000 --> 00:00:08,000"));
        assertEquals(2, out.split("\n\n").length);
    }

    @Test
    public void markdownHasHeadings() {
        String out = TranscriptExporter.MD.build("t", sample(), "خلاصه");
        assertTrue(out.startsWith("# t"));
        assertTrue(out.contains("## خلاصه"));
        assertTrue(out.contains("**`00:00`**"));
    }

    @Test
    public void htmlEscapesAndIsRtl() {
        Transcript t = new Transcript();
        t.add(0, 100, "<b>و & \"ک\"</b>");
        String out = TranscriptExporter.HTML.build("t", t, null);
        assertTrue(out.contains("dir=\"rtl\""));
        assertFalse(out.contains("<b>و"));
        assertTrue(out.contains("&lt;b&gt;"));
    }

    // ------------------------------------------------- model dir validation

    private File modelLayout(boolean am, boolean graph, boolean conf) throws Exception {
        File dir = Files.createTempDirectory("model").toFile();
        dir.deleteOnExit();
        if (am) {
            new File(dir, "am").mkdirs();
            new File(dir, "am/final.mdl").createNewFile();
        }
        if (graph) {
            new File(dir, "graph").mkdirs();
            new File(dir, "graph/HCLr.fst").createNewFile();
        }
        if (conf) {
            new File(dir, "conf").mkdirs();
            new File(dir, "conf/model.conf").createNewFile();
        }
        return dir;
    }

    @Test
    public void completeModelLayoutPasses() throws Exception {
        assertTrue(VoskTranscriber.isModelDirValid(modelLayout(true, true, true)));
    }

    @Test
    public void partialModelsFailValidation() throws Exception {
        assertFalse(VoskTranscriber.isModelDirValid(modelLayout(false, true, true)));
        assertFalse(VoskTranscriber.isModelDirValid(modelLayout(true, false, true)));
        assertFalse(VoskTranscriber.isModelDirValid(modelLayout(true, true, false)));
        assertFalse(VoskTranscriber.isModelDirValid(null));
    }

    @Test
    public void transcriptHelpers() {
        assertEquals("00:01:05,120", Transcript.timecode(65120, true));
        assertEquals("00:01:05.120", Transcript.timecode(65120, false));
        assertEquals("01:05", Transcript.shortTime(65120));
        Transcript t = sample();
        assertEquals("سلام دنیا این یک آزمایش است", t.fullText());
        assertFalse(t.isEmpty());
        assertEquals(8000, t.endMs());
    }
}
