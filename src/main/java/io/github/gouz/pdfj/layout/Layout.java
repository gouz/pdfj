package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.style.Len;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout engine: block layout (with margin collapsing), flexbox ({@link Flex}), grid ({@link Grid}),
 * absolute positioning, text and image leaves.
 *
 * <p>{@link #compute} sizes a box: with {@code perform}, it also lays its subtree out (positions of the
 * children, line breaks); without, it only measures, and the result is cached. Sizes are border-box
 * sizes, NaN when unknown. Available space is a size, or {@link #MIN} / {@link #MAX} for min-content /
 * max-content sizing.
 */
public final class Layout {
    private Layout() {}

    /** available space: size under a min-content constraint */
    public static final double MIN = -1;
    /** available space: size under a max-content constraint */
    public static final double MAX = Double.POSITIVE_INFINITY;

    record Key(double kw, double kh, double aw, double ah, double cbw, double cbh, boolean blockChild) {}

    /** Collapsible margins: the largest positive and the most negative. */
    static final class Margins {
        double pos, neg;

        Margins() {}

        Margins(double m) {
            add(m);
        }

        void add(double m) {
            if (m > 0) pos = Math.max(pos, m);
            else neg = Math.min(neg, m);
        }

        void add(Margins m) {
            if (m == null) return;
            pos = Math.max(pos, m.pos);
            neg = Math.min(neg, m.neg);
        }

        double value() {
            return pos + neg;
        }

        Margins copy() {
            Margins m = new Margins();
            m.pos = pos;
            m.neg = neg;
            return m;
        }
    }

    /** The size of a box, the margins its content lets escape (block children), its last baseline. */
    static final class Out {
        final double w, h;
        Margins top, bottom;
        boolean through;
        /** from the top of the border box, NaN if none */
        double baseline = Double.NaN;

        Out(double w, double h) {
            this.w = w;
            this.h = h;
        }
    }

    static boolean definite(double v) {
        return !Double.isNaN(v) && v >= 0 && !Double.isInfinite(v);
    }

    static double clamp(double v, double min, double max) {
        return Math.max(Math.min(v, max), min);
    }

    /** a length against a basis that may be unknown: percentages of an unknown basis are 0 */
    static double resolve(Len l, double basis) {
        if (l.isAuto()) return 0;
        double v = l.resolveOrNaN(definite(basis) ? basis : Double.NaN);
        return Double.isNaN(v) ? (l.isPct() ? 0 : l.px()) : v;
    }

    /** a {@code width} / {@code height} as a border-box size, NaN when auto or relative to an unknown basis */
    static double styleSize(Len l, double basis, double pb, boolean borderBox) {
        if (l.isAuto()) return Double.NaN;
        double v = l.resolveOrNaN(definite(basis) ? basis : Double.NaN);
        if (Double.isNaN(v)) return Double.NaN;
        return borderBox ? Math.max(v, pb) : Math.max(v, 0) + pb;
    }

    static double[] padding(Box b, double cbw) {
        double[] p = new double[4];
        for (int i = 0; i < 4; i++) p[i] = Math.max(0, resolve(b.padding[i], cbw));
        return p;
    }

    /** margins against the containing block's width; auto is 0 */
    static double[] margins(Box b, double cbw) {
        double[] m = new double[4];
        for (int i = 0; i < 4; i++) m[i] = resolve(b.margin[i], cbw);
        return m;
    }

    /** {min, max} border-box sizes of a dimension (0 = width, 1 = height) */
    static double[] minMax(Box b, int dim, double basis, double pb) {
        double min = styleSize(b.minSize[dim], basis, pb, b.borderBox);
        double max = styleSize(b.maxSize[dim], basis, pb, b.borderBox);
        return new double[] {Double.isNaN(min) ? 0 : min, Double.isNaN(max) ? Double.POSITIVE_INFINITY : max};
    }

    /** Size (and with {@code perform}, lay out) a box: see the class comment. */
    static Out compute(Box b, double kw, double kh, double aw, double ah, double cbw, double cbh, boolean perform, boolean blockChild) {
        if (b.display == Box.Display.NONE) {
            if (perform) {
                b.w = 0;
                b.h = 0;
            }
            return new Out(0, 0);
        }
        Key key = null;
        if (!perform) {
            key = new Key(kw, kh, aw, ah, cbw, cbh, blockChild);
            Out hit = b.cache == null ? null : b.cache.get(key);
            if (hit != null) return hit;
        }
        double[] pad = padding(b, cbw);
        double pbH = pad[1] + pad[3] + b.border[1] + b.border[3];
        double pbV = pad[0] + pad[2] + b.border[0] + b.border[2];
        double[] mmW = minMax(b, 0, cbw, pbH), mmH = minMax(b, 1, cbh, pbV);
        double w = kw, h = kh;
        if (Double.isNaN(w)) w = styleSize(b.size[0], cbw, pbH, b.borderBox);
        if (Double.isNaN(h)) h = styleSize(b.size[1], cbh, pbV, b.borderBox);
        if (b.aspectRatio != null && b.aspectRatio > 0) {
            double r = b.aspectRatio;
            if (!Double.isNaN(w) && Double.isNaN(h)) h = b.borderBox ? w / r : Math.max(0, w - pbH) / r + pbV;
            else if (Double.isNaN(w) && !Double.isNaN(h)) w = b.borderBox ? h * r : Math.max(0, h - pbV) * r + pbH;
        }
        if (Double.isNaN(kw) && !Double.isNaN(w)) w = Math.max(clamp(w, mmW[0], mmW[1]), pbH);
        if (Double.isNaN(kh) && !Double.isNaN(h)) h = Math.max(clamp(h, mmH[0], mmH[1]), pbV);
        Out o;
        if (b.text != null) o = text(b, w, h, aw, pad, pbH, pbV, perform);
        else if (b.image != null) o = image(b, w, h, aw, pbH, pbV, mmW, mmH);
        else {
            o = switch (b.display) {
                case FLEX -> Flex.layout(b, w, h, aw, ah, pad, mmW, mmH, cbw, perform);
                case GRID -> Grid.layout(b, w, h, aw, ah, pad, mmW, mmH, perform);
                default -> block(b, w, h, aw, ah, pad, mmW, mmH, perform, blockChild);
            };
        }
        double ow = Double.isNaN(kw) ? Math.max(clamp(o.w, mmW[0], mmW[1]), pbH) : kw;
        double oh = Double.isNaN(kh) ? Math.max(clamp(o.h, mmH[0], mmH[1]), pbV) : kh;
        if (ow != o.w || oh != o.h) {
            Out c = new Out(ow, oh);
            c.top = o.top;
            c.bottom = o.bottom;
            c.through = o.through && oh == 0;
            c.baseline = o.baseline;
            o = c;
        }
        if (perform) {
            b.w = o.w;
            b.h = o.h;
            System.arraycopy(pad, 0, b.lpad, 0, 4);
            System.arraycopy(b.border, 0, b.lbor, 0, 4);
        } else {
            b.cache().put(key, o);
        }
        return o;
    }

    /** Lay a root out at {@code width}. */
    public static void layoutRoot(Box root, double width) {
        compute(root, width, Double.NaN, width, MAX, width, Double.NaN, true, false);
        root.x = 0;
        root.y = 0;
    }

    /** Lay a box out on its own (a header band): as wide as given. */
    public static void layoutAt(Box root, double width) {
        layoutRoot(root, width);
    }

    // ---- leaves

    private static Out text(Box b, double w, double h, double aw, double[] pad, double pbH, double pbV, boolean perform) {
        double inner;
        if (!Double.isNaN(w)) inner = Math.max(0, w - pbH);
        else if (aw == MIN) inner = -1;
        else if (Double.isInfinite(aw) || Double.isNaN(aw)) inner = Double.POSITIVE_INFINITY;
        else inner = Math.max(0, aw - pbH);
        double cx = pad[3] + b.border[3], cy = pad[0] + b.border[0];
        TextLeaf.Result r = b.text.layout(inner, perform, cx, cy);
        Out o = new Out(Double.isNaN(w) ? r.width() + pbH : w, Double.isNaN(h) ? r.height() + pbV : h);
        o.baseline = Double.isNaN(r.baseline()) ? Double.NaN : cy + r.baseline();
        return o;
    }

    /** Images keep their aspect ratio; without a size, their natural size within the available width. */
    private static Out image(Box b, double w, double h, double aw, double pbH, double pbV, double[] mmW, double[] mmH) {
        double nw = b.image.naturalWidth(), nh = b.image.naturalHeight();
        double ratio = nw > 0 && nh > 0 ? nw / nh : 1;
        if (!Double.isNaN(w) && !Double.isNaN(h)) return new Out(w, h);
        if (!Double.isNaN(w)) return new Out(w, Math.max(0, w - pbH) / ratio + pbV);
        if (!Double.isNaN(h)) return new Out(Math.max(0, h - pbV) * ratio + pbH, h);
        double cw;
        if (aw == MIN) cw = 0;
        else if (definite(aw)) cw = Math.min(nw, Math.max(0, aw - pbH));
        else cw = nw;
        double ow = Math.max(clamp(cw + pbH, mmW[0], mmW[1]), pbH);
        double oh = Math.max(clamp(Math.max(0, ow - pbH) / ratio + pbV, mmH[0], mmH[1]), pbV);
        return new Out(ow, oh);
    }

    /**
     * An inline box or a float laid out on its own at the width of its line: its margin-box size and the
     * baseline of its last line of text (NaN for an image, or no text). {@code width}: infinite for
     * max-content, MIN for min-content.
     */
    static double[] inlineSize(Box node, double width, boolean perform) {
        double cb = definite(width) ? width : Double.NaN;
        double[] m = margins(node, cb);
        double avail = width == MIN ? MIN : Double.isInfinite(width) ? MAX : Math.max(0, width - m[1] - m[3]);
        Out o = compute(node, Double.NaN, Double.NaN, avail, MAX, cb, Double.NaN, perform, false);
        if (perform) System.arraycopy(m, 0, node.lmar, 0, 4);
        double base = node.image != null || Double.isNaN(o.baseline) ? Double.NaN : o.baseline + m[0];
        return new double[] {o.w + m[1] + m[3], o.h + m[0] + m[2], base};
    }

    // ---- block layout

    /** a block-level child's width: its own, else the container's (stretch), or shrink-to-fit for tables, images and floats */
    private static double blockChildWidth(Box c, double cw, double[] m) {
        if (!c.size[0].isAuto() || c.replaced || c.shrinkToFit || c.floating || c.aspectRatio != null) return Double.NaN;
        double pbH = padding(c, cw)[1] + padding(c, cw)[3] + c.border[1] + c.border[3];
        double[] mm = minMax(c, 0, cw, pbH);
        return Math.max(clamp(cw - m[1] - m[3], mm[0], mm[1]), pbH);
    }

    private static boolean collapsesThroughTop(Box b, double[] pad, boolean blockChild) {
        return blockChild && b.display == Box.Display.BLOCK && !b.overflowHidden && !b.cell && !b.floating && !b.absolute
            && pad[0] == 0 && b.border[0] == 0;
    }

    private static Out block(Box b, double w, double h, double aw, double ah, double[] pad, double[] mmW, double[] mmH, boolean perform, boolean blockChild) {
        double pl = pad[3] + b.border[3], pt = pad[0] + b.border[0], pb = pad[2] + b.border[2];
        double pbH = pl + pad[1] + b.border[1];
        double pbV = pt + pb;
        if (Double.isNaN(w)) {
            // content-based width (shrink-to-fit): the widest child at the same available space
            double inner = definite(aw) ? Math.max(0, aw - pbH) : aw;
            double cwMax = 0;
            for (Box c : b.children) {
                if (c.absolute) continue;
                double[] m = margins(c, definite(inner) ? inner : Double.NaN);
                double avail = definite(inner) ? Math.max(0, inner - m[1] - m[3]) : inner;
                Out co = compute(c, Double.NaN, Double.NaN, avail, MAX, definite(inner) ? inner : Double.NaN, Double.NaN, false, true);
                cwMax = Math.max(cwMax, co.w + m[1] + m[3]);
            }
            w = Math.max(clamp(cwMax + pbH, mmW[0], mmW[1]), pbH);
        }
        double cw = Math.max(0, w - pbH);
        double ch = Double.isNaN(h) ? Double.NaN : Math.max(0, h - pbV);
        boolean collapseTop = collapsesThroughTop(b, pad, blockChild);
        boolean collapseBottom = blockChild && b.display == Box.Display.BLOCK && !b.overflowHidden && !b.cell && !b.floating && !b.absolute
            && Double.isNaN(h) && pb == 0 && mmH[0] == 0;
        double y = pt;
        Margins pending = new Margins();
        Margins escTop = new Margins();
        boolean atStart = true;
        double lastBaseline = Double.NaN;
        List<Box> absolutes = null;
        List<Double> staticY = null;
        for (Box c : b.children) {
            if (c.absolute) {
                if (absolutes == null) {
                    absolutes = new ArrayList<>();
                    staticY = new ArrayList<>();
                }
                absolutes.add(c);
                staticY.add(y + pending.value());
                continue;
            }
            double[] m = margins(c, cw);
            boolean autoL = c.margin[3].isAuto(), autoR = c.margin[1].isAuto();
            double kwc = blockChildWidth(c, cw, m);
            Out co = compute(c, kwc, Double.NaN, Math.max(0, cw - m[1] - m[3]), definite(ch) ? ch : MAX, cw, ch, perform, true);
            Margins top = new Margins(m[0]);
            top.add(co.top);
            Margins bottom = new Margins(m[2]);
            bottom.add(co.bottom);
            double cy;
            if (co.through) {
                pending.add(top);
                pending.add(bottom);
                cy = y + pending.value();
            } else if (atStart && collapseTop) {
                escTop.add(pending);
                escTop.add(top);
                pending = new Margins();
                cy = y;
            } else {
                pending.add(top);
                y += pending.value();
                pending = new Margins();
                cy = y;
            }
            double free = cw - co.w - m[1] - m[3];
            double cx;
            if (autoL && autoR) cx = m[3] + Math.max(0, free) / 2;
            else if (autoL) cx = m[3] + Math.max(0, free);
            else if (b.rtl && !autoR) cx = m[3] + free;
            else cx = m[3];
            if (perform) {
                c.x = pl + cx;
                c.y = cy;
                System.arraycopy(m, 0, c.lmar, 0, 4);
            }
            if (!Double.isNaN(co.baseline)) lastBaseline = cy + co.baseline;
            if (co.through) continue;
            y = cy + co.h;
            pending = bottom;
            atStart = false;
        }
        Out out;
        Margins escBottom = null;
        if (collapseBottom && !atStart) {
            escBottom = pending;
        } else if (!(atStart && collapseTop && collapseBottom)) {
            y += pending.value();
        }
        double contentEnd = y;
        double hh = Double.isNaN(h) ? contentEnd + pb : h;
        out = new Out(w, hh);
        if (atStart && collapseTop && collapseBottom && hh <= pbV + 1e-9) {
            // nothing inside: the box's margins collapse through it
            Margins all = escTop.copy();
            all.add(pending);
            out.top = all;
            out.through = true;
        } else {
            out.top = escTop;
            out.bottom = escBottom;
        }
        out.baseline = lastBaseline;
        if (perform && absolutes != null) {
            for (int i = 0; i < absolutes.size(); i++) absolute(b, absolutes.get(i), w, hh, pl, staticY.get(i));
        }
        return out;
    }

    /** An absolutely positioned child, against the padding box of its parent (static position when no inset). */
    static void absolute(Box parent, Box c, double pw, double ph, double staticX, double staticY) {
        double bl = parent.border[3], bt = parent.border[0], br = parent.border[1], bb = parent.border[2];
        double paw = Math.max(0, pw - bl - br), pah = Math.max(0, ph - bt - bb);
        double[] m = margins(c, paw);
        double l = c.inset[3].isAuto() ? Double.NaN : c.inset[3].resolve(paw);
        double r = c.inset[1].isAuto() ? Double.NaN : c.inset[1].resolve(paw);
        double t = c.inset[0].isAuto() ? Double.NaN : c.inset[0].resolve(pah);
        double btm = c.inset[2].isAuto() ? Double.NaN : c.inset[2].resolve(pah);
        double kw = Double.NaN, kh = Double.NaN;
        if (c.size[0].isAuto() && !Double.isNaN(l) && !Double.isNaN(r)) kw = Math.max(0, paw - l - r - m[1] - m[3]);
        if (c.size[1].isAuto() && !Double.isNaN(t) && !Double.isNaN(btm)) kh = Math.max(0, pah - t - btm - m[0] - m[2]);
        double avail = Math.max(0, paw - (Double.isNaN(l) ? 0 : l) - (Double.isNaN(r) ? 0 : r) - m[1] - m[3]);
        Out o = compute(c, kw, kh, avail, MAX, paw, pah, true, false);
        double x, y;
        if (!Double.isNaN(l)) x = bl + l + m[3];
        else if (!Double.isNaN(r)) x = pw - br - r - m[1] - o.w;
        else x = (parent.rtl ? pw - staticX - o.w - m[1] : staticX + m[3]);
        if (!Double.isNaN(t)) y = bt + t + m[0];
        else if (!Double.isNaN(btm)) y = ph - bb - btm - m[2] - o.h;
        else y = staticY + m[0];
        c.x = x;
        c.y = y;
        System.arraycopy(m, 0, c.lmar, 0, 4);
    }

    /** Baseline of the last line of text in a laid out subtree, from its border-box top; NaN if none. */
    public static double lastBaseline(Box b) {
        if (b.text != null) {
            var lines = b.text.lines.lines();
            return lines.isEmpty() ? Double.NaN : b.lpad[0] + b.lbor[0] + lines.get(lines.size() - 1).baseline();
        }
        for (int i = b.children.size() - 1; i >= 0; i--) {
            Box c = b.children.get(i);
            if (c.absolute) continue;
            double v = lastBaseline(c);
            if (!Double.isNaN(v)) return c.y + v;
        }
        return Double.NaN;
    }
}
