package io.github.gouz.pdfj.layout;

import org.apache.batik.gvt.GraphicsNode;

import java.awt.image.BufferedImage;

/** A decoded image: a bitmap (JPEG data kept as is), or an SVG drawing (vector). */
public sealed interface ImageKind {
    /**
     * {@code file} is the original JPEG or PNG file (embedded without re-encoding; {@code image} then
     * null), else the decoded pixels (GIF, WebP)
     */
    record Raster(BufferedImage image, byte[] file, int width, int height) implements ImageKind {}

    /** a Batik drawing of {@code width} x {@code height} user units */
    record Svg(GraphicsNode node, double width, double height) implements ImageKind {}
}
