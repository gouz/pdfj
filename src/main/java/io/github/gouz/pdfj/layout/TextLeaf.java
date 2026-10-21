package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.css.Css.Decl;
import io.github.gouz.pdfj.style.Style;
import io.github.gouz.pdfj.style.Style.TextAlign;
import io.github.gouz.pdfj.text.Brush;
import io.github.gouz.pdfj.text.Paragraph;
import io.github.gouz.pdfj.text.Piece;
import io.github.gouz.pdfj.text.TextLayout;

import java.util.ArrayList;
import java.util.List;

/** An anonymous block of inline content: its text, inline boxes and the floats it wraps around. */
public final class TextLeaf {
    /** A float in a block of text: its box (a detached subtree), side, margin-box size and position. */
    public static final class FloatBox {
        public final Box node;
        public final boolean left;
        public double w, h, x, y;

        FloatBox(Box node, boolean left) {
            this.node = node;
            this.left = left;
        }
    }

    /** {@code ::first-line} of the block: the pieces and container to rebuild the text from, the declarations */
    record FirstLine(List<Piece> pieces, Style container, List<Decl> decls, double rem) {}

    private final Paragraph base;
    /** the paragraph drawn: the base one, or its first line restyled */
    public Paragraph para;
    public final List<FloatBox> floats;
    public final TextAlign align;
    final FirstLine firstLine;
    /** the final lines (set by the last layout pass) */
    public TextLayout.Lines lines = TextLayout.Lines.EMPTY;

    TextLeaf(Paragraph para, List<FloatBox> floats, TextAlign align, FirstLine firstLine) {
        this.base = para;
        this.para = para;
        this.floats = floats;
        this.align = align;
        this.firstLine = firstLine;
    }

    /** the inline boxes of the text, in logical order */
    public List<Box> inlineBoxes() {
        List<Box> out = new ArrayList<>();
        for (Object o : base.boxes) if (o instanceof Box b) out.add(b);
        return out;
    }

    record Result(double width, double height, double baseline) {}

    /**
     * Break the lines at {@code width} (inner width; infinite: max-content; negative: min-content).
     * {@code perform}: final layout, the inline boxes and floats laid out and placed ({@code cx}, {@code cy}:
     * the content box in the leaf's border box).
     */
    Result layout(double width, boolean perform, double cx, double cy) {
        double W = width;
        if (W < 0) {
            double[][] sizes = boxSizes(base, Layout.MIN, false);
            double mc = TextLayout.minContent(base, sizes[0]);
            for (FloatBox f : floats) mc = Math.max(mc, Layout.inlineSize(f.node, Layout.MIN, false)[0]);
            W = mc;
        }
        boolean infinite = Double.isInfinite(W);
        double[][] sizes = boxSizes(base, W, perform);
        List<TextLayout.FloatRect> rects = new ArrayList<>();
        double floatsW = 0, floatsH = 0;
        double lx = 0, rx = W;
        for (FloatBox f : floats) {
            double[] s = Layout.inlineSize(f.node, W, perform);
            f.w = s[0];
            f.h = s[1];
            if (infinite) {
                f.x = floatsW;
            } else if (f.left) {
                f.x = lx;
                lx += f.w;
            } else {
                rx -= f.w;
                f.x = rx;
            }
            f.y = 0;
            floatsW += f.w;
            floatsH = Math.max(floatsH, f.h);
            rects.add(new TextLayout.FloatRect(f.x, f.y, f.w, f.h, f.left));
        }
        double lineW = infinite ? W : W + 0.01;
        Paragraph p = base;
        TextLayout.Lines lines = TextLayout.layout(p, lineW, infinite ? List.of() : rects, sizes[0], sizes[1], sizes[2], align);
        if (firstLine != null && !infinite && !lines.lines().isEmpty()) {
            int styled = firstCount(p, lines);
            int tooMany = Integer.MAX_VALUE;
            boolean forcedBreak = false;
            for (int i = 0; i < 10; i++) {
                p = Paragraph.build(firstLinePieces(styled, false), firstLine.container());
                lines = TextLayout.layout(p, lineW, rects, sizes[0], sizes[1], sizes[2], align);
                int n = firstCount(p, lines);
                if (n < styled) {
                    tooMany = Math.min(tooMany, styled);
                    styled = n;
                } else if (n > styled && n < tooMany) {
                    styled = n;
                } else {
                    // fits; unstyled text left on the first line would not fit styled: end the line there
                    forcedBreak = n > styled;
                    break;
                }
            }
            if (forcedBreak) {
                p = Paragraph.build(firstLinePieces(styled, true), firstLine.container());
                lines = TextLayout.layout(p, lineW, rects, sizes[0], sizes[1], sizes[2], align);
            }
        }
        // fit-content: as wide as the room, unless the text is narrower on one line (its max-content width)
        double w = infinite ? lines.width() + floatsW : floats.isEmpty() ? Math.min(maxContent(), Math.max(W, lines.width())) : W;
        double h = Math.max(lines.height(), floatsH);
        if (perform) {
            this.para = p;
            this.lines = lines;
            for (FloatBox f : floats) {
                f.node.x = cx + f.x + f.node.lmar[3];
                f.node.y = cy + f.y + f.node.lmar[0];
            }
            for (TextLayout.Line line : lines.lines()) {
                for (TextLayout.Item item : line.items()) {
                    if (item instanceof TextLayout.BoxItem b && b.node() instanceof Box box) {
                        box.x = cx + b.x() + box.lmar[3];
                        box.y = cy + b.y() + box.lmar[0];
                    }
                }
            }
        }
        double baseline = lines.lines().isEmpty() ? Double.NaN : lines.lines().get(lines.lines().size() - 1).baseline();
        return new Result(w, h, baseline);
    }

    private double maxContent = Double.NaN;

    /** the width of the text on one line (forced breaks apart) */
    private double maxContent() {
        if (Double.isNaN(maxContent)) maxContent = layout(Double.POSITIVE_INFINITY, false, 0, 0).width();
        return maxContent;
    }

    /** sizes of the inline boxes: [0] margin-box width, [1] height, [2] baseline (NaN: bottom edge) */
    private static double[][] boxSizes(Paragraph p, double width, boolean perform) {
        double[][] out = new double[3][p.n];
        for (int i = 0; i < p.n; i++) {
            if (p.kind[i] != Paragraph.BOX) continue;
            double[] s = Layout.inlineSize((Box) p.boxes[i], width, perform);
            out[0][i] = s[0];
            out[1][i] = s[1];
            out[2][i] = s[2];
        }
        return out;
    }

    private static int firstCount(Paragraph p, TextLayout.Lines lines) {
        if (lines.lines().isEmpty()) return 0;
        TextLayout.Line l = lines.lines().get(0);
        return p.visibleChars(l.start(), l.end());
    }

    /** The pieces with their first {@code n} non-whitespace characters in the ::first-line style. */
    private List<Piece> firstLinePieces(int n, boolean brk) {
        int left = n;
        List<Piece> out = new ArrayList<>(firstLine.pieces().size() + 1);
        for (Piece piece : firstLine.pieces()) {
            if (piece instanceof Piece.Text t && left > 0) {
                String text = t.text();
                int cut = text.length(), count = 0;
                for (int i = 0; i < text.length(); ) {
                    int c = text.codePointAt(i);
                    if (count == left && !Character.isWhitespace(c)) {
                        cut = i;
                        break;
                    }
                    if (!Character.isWhitespace(c)) count++;
                    i += Character.charCount(c);
                }
                left -= Math.min(count, left);
                Style fs = Style.compute(t.style(), firstLine.decls(), firstLine.rem());
                Brush fb = restyle(fs, t.brush());
                out.add(new Piece.Text(Builder.applyTransform(text.substring(0, cut), fs.transform), fs, fb));
                if (brk && left == 0) out.add(new Piece.Break(fs, fb));
                if (cut < text.length()) out.add(new Piece.Text(text.substring(cut), t.style(), t.brush()));
            } else if (piece instanceof Piece.Break b && left > 0) {
                Style fs = Style.compute(b.style(), firstLine.decls(), firstLine.rem());
                out.add(new Piece.Break(fs, restyle(fs, b.brush())));
            } else {
                out.add(piece);
            }
        }
        return out;
    }

    private static Brush restyle(Style fs, Brush b) {
        return new Brush(fs.color, fs.background != null ? fs.background : b.background(), b.link(), b.textFill());
    }
}
