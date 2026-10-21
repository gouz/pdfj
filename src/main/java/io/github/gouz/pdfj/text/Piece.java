package io.github.gouz.pdfj.text;

import io.github.gouz.pdfj.style.Style;

/** A piece of inline content of a block. */
public sealed interface Piece {
    record Text(String text, Style style, Brush brush) implements Piece {}

    record Break(Style style, Brush brush) implements Piece {}

    /** an inline-block or an inline image: a box laid out on its own, placed in the line */
    record Box(Object node, Style style) implements Piece {}

    /** a floated box the text of the block wraps around */
    record Float(Object node, boolean left) implements Piece {}
}
