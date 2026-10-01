package com.foobnix.tts;

import org.junit.Test;
import static org.junit.Assert.*;

public class TtsPageLayoutTest {
    @Test public void fontFamilyChangeInvalidatesLayoutAtTheSameSize() {
        TtsPageLayout old = new TtsPageLayout(1080, 2364, 20, "body {font-family: serif;}");
        assertFalse(old.matches(new TtsPageLayout(1080, 2364, 20, "body {font-family: Libron;}")));
        assertTrue(old.matches(new TtsPageLayout(1080, 2364, 20, "body {font-family: serif;}")));
    }

    @Test public void fontSizeAndMarginsAlsoInvalidateLayout() {
        TtsPageLayout old = new TtsPageLayout(1080, 2364, 20, "@page {margin: 1em;}");
        assertFalse(old.matches(new TtsPageLayout(1080, 2364, 24, old.css)));
        assertFalse(old.matches(new TtsPageLayout(1080, 2364, 20, "@page {margin: 2em;}")));
    }

    @Test public void screenDimensionsMustMatchIndividually() {
        TtsPageLayout old = new TtsPageLayout(1080, 2073, 20, "css");
        assertFalse(old.matches(new TtsPageLayout(1080, 2364, 20, "css")));
        assertFalse(old.matches(new TtsPageLayout(2073, 1080, 20, "css")));
        assertFalse(old.matches(null));
    }
}
