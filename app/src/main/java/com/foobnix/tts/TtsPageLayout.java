package com.foobnix.tts;

/** The inputs that determine which text belongs to an EPUB page. */
final class TtsPageLayout {
    final int width;
    final int height;
    final int fontSize;
    final String css;

    TtsPageLayout(int width, int height, int fontSize, String css) {
        this.width = width;
        this.height = height;
        this.fontSize = fontSize;
        this.css = css;
    }

    boolean matches(TtsPageLayout other) {
        return other != null && width == other.width && height == other.height
                && fontSize == other.fontSize && css.equals(other.css);
    }
}
