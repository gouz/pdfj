package io.github.gouz.pdfj.text;

import io.github.gouz.pdfj.style.Rgba;

/**
 * Text paint and the inline box it belongs to.
 *
 * @param background background of the inline element ({@code <mark>}), null if none
 * @param link 1-based index in the tree's links, 0 = not a link
 * @param textFill 1-based index in the tree's text fills: the element whose {@code background-clip: text} paints this text
 */
public record Brush(Rgba color, Rgba background, int link, int textFill) {
    public Brush withColor(Rgba c) {
        return new Brush(c, background, link, textFill);
    }

    public Brush withBackground(Rgba b) {
        return new Brush(color, b, link, textFill);
    }

    public Brush withLink(int l) {
        return new Brush(color, background, l, textFill);
    }

    public Brush withTextFill(int f) {
        return new Brush(color, background, link, f);
    }
}
