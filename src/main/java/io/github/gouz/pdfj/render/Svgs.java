package io.github.gouz.pdfj.render;

import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D;
import io.github.gouz.pdfj.layout.ImageKind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** SVG drawings as PDF vector forms (Batik paints on a PDFBox Graphics2D). */
final class Svgs {
    private Svgs() {}

    /** a form of the drawing's size (its user units), y up like any form; null if it cannot be drawn */
    static PDFormXObject form(PDDocument doc, ImageKind.Svg svg) {
        try {
            float w = (float) Math.max(svg.width(), 0.01), h = (float) Math.max(svg.height(), 0.01);
            PdfBoxGraphics2D g = new PdfBoxGraphics2D(doc, w, h);
            try {
                svg.node().paint(g);
            } finally {
                g.dispose();
            }
            return g.getXFormObject();
        } catch (Exception | LinkageError e) {
            System.err.println("pdfj: SVG not drawn: " + e);
            return null;
        }
    }
}
