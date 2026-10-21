package io.github.gouz.pdfj.style;

/** An sRGB color, 0..255 per channel. */
public record Rgba(int r, int g, int b, int a) {
    public static final Rgba BLACK = new Rgba(0, 0, 0, 255);

    public boolean visible() {
        return a > 0;
    }
}
