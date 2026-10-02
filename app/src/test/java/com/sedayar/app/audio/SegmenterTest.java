package com.sedayar.app.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** The file and live pipelines share this segmentation — it must be exact. */
public class SegmenterTest {

    private void addWords(Segmenter s, int count, long startMs, long gapMs) {
        long t = startMs;
        for (int i = 0; i < count; i++) {
            s.add(t, t + 300, "w" + i);
            t += 300 + gapMs;
        }
    }

    @Test
    public void emptyBufferFlushesNothing() {
        Segmenter s = new Segmenter();
        assertTrue(s.flush(1000).isEmpty());
        assertEquals(0, s.wordCount());
    }

    @Test
    public void wordsWithoutBigGapStayInOneSegment() {
        Segmenter s = new Segmenter();
        addWords(s, 5, 0, 100); // gaps of 100 ms < 1.2 s
        List<Transcript.Segment> out = s.flush(5000);
        assertEquals(1, out.size());
        assertEquals(5, out.get(0).text.split(" ").length);
        assertEquals(0, out.get(0).startMs);
    }

    @Test
    public void longPauseStartsNewSegment() {
        Segmenter s = new Segmenter();
        addWords(s, 2, 0, 100);
        addWords(s, 2, 6000, 100); // 5+ s of silence between the pairs
        List<Transcript.Segment> out = s.flush(20000);
        assertEquals(2, out.size());
        assertTrue(out.get(0).startMs < out.get(1).startMs);
    }

    @Test
    public void segmentNeverExceedsTwelveSeconds() {
        Segmenter s = new Segmenter();
        // 100 words every 400 ms = 40 s of continuous speech, no gaps
        addWords(s, 100, 0, 100);
        List<Transcript.Segment> out = s.flush(99999);
        assertTrue("expected several segments, got " + out.size(), out.size() >= 3);
        for (int i = 0; i < out.size() - 1; i++) {
            long span = out.get(i).endMs - out.get(i).startMs;
            assertTrue("segment too long: " + span, span <= 12_500);
        }
    }

    @Test
    public void blankWordsAreIgnored() {
        Segmenter s = new Segmenter();
        s.add(0, 100, "hello");
        s.add(200, 300, "   ");
        s.add(400, 500, null);
        s.add(600, 700, "");
        List<Transcript.Segment> out = s.flush(1000);
        assertEquals(1, out.size());
        assertEquals("hello", out.get(0).text);
    }

    @Test
    public void flushResetsTheBuffer() {
        Segmenter s = new Segmenter();
        addWords(s, 3, 0, 100);
        assertEquals(3, s.wordCount()); // three words buffered
        assertEquals(1, s.flush(1000).size()); // …in one segment (gaps < 1.2 s)
        assertEquals(0, s.wordCount());
        addWords(s, 2, 10000, 100);
        List<Transcript.Segment> second = s.flush(20000);
        assertEquals(1, second.size());
        assertEquals(10000, second.get(0).startMs);
    }
}
