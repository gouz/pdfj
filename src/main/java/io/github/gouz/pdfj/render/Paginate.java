package io.github.gouz.pdfj.render;

import io.github.gouz.pdfj.layout.Box;
import io.github.gouz.pdfj.layout.Deco;
import io.github.gouz.pdfj.layout.TextLeaf;
import io.github.gouz.pdfj.text.TextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * Pagination: the document is laid out once as one tall column of the content width, then cut into
 * pages. Cuts never split a text line, an image or a {@code break-inside: avoid} box shorter than a page
 * (they are moved to the next page); boxes that span a cut are drawn on both pages, clipped.
 */
public final class Paginate {
    private Paginate() {}

    /**
     * A table with a {@code <thead>} and / or a {@code <tfoot>}, repeated on the pages it spans.
     *
     * @param ox absolute origin of the table's parent (what draw() takes)
     * @param head header rows, absolute y range (null if none)
     * @param foot footer rows, absolute y range (null if none)
     */
    public record TableHead(Box table, double ox, double oy, double[] head, double[] foot, double top, double bottom, double bodyBottom) {}

    /** A page: the slice [start, end) of the column, between a repeated table header and footer if any. */
    public record Page(double start, double end, TableHead header, TableHead footer) {
        public double headerHeight() {
            return header == null || header.head == null ? 0 : header.head[1] - header.head[0];
        }

        public double footerHeight() {
            return footer == null || footer.foot == null ? 0 : footer.foot[1] - footer.foot[0];
        }
    }

    /** Vertical ranges that must not be cut, and forced page breaks. */
    public static final class Breaks {
        final List<double[]> atoms = new ArrayList<>();
        final List<Double> forced = new ArrayList<>();
        /** bottom of the last visible thing (text, image, background, border) */
        double bottom;
        final List<TableHead> tables = new ArrayList<>();
    }

    public static Breaks collect(Box root) {
        Breaks b = new Breaks();
        walk(root, 0, 0, b);
        for (double[] a : b.atoms) b.bottom = Math.max(b.bottom, a[1]);
        b.atoms.sort((p, q) -> Double.compare(p[0], q[0]));
        b.forced.sort(null);
        return b;
    }

    private static double[] range(List<Box> cells, double y) {
        if (cells == null) return null;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Box c : cells) {
            lo = Math.min(lo, y + c.y);
            hi = Math.max(hi, y + c.y + c.h);
        }
        return Double.isFinite(lo) ? new double[] {lo, hi} : null;
    }

    private static void walk(Box n, double ox, double oy, Breaks b) {
        double x = ox + n.x, y = oy + n.y;
        double[] head = range(n.headCells, y), foot = range(n.footCells, y);
        if (head != null || foot != null) {
            // where the rows end, their bottom borders aside: a cut in the last border is not inside the table
            double body = y;
            for (Box c : n.children) if (c.cell) body = Math.max(body, y + c.y + c.h - c.lbor[2]);
            b.tables.add(new TableHead(n, ox, oy, head, foot, y, y + n.h, body));
        }
        Deco d = n.deco;
        if (d != null) {
            if (d.breakBefore) b.forced.add(y);
            if (d.breakAfter) b.forced.add(y + n.h);
            if (d.avoidBreakInside) b.atoms.add(new double[] {y, y + n.h});
            boolean border = false;
            for (double w : d.borderWidth) border |= w > 0;
            if (d.background != null || !d.layers.isEmpty() || border) b.bottom = Math.max(b.bottom, y + n.h);
        }
        if (n.text != null) {
            double top = y + n.lpad[0] + n.lbor[0];
            for (TextLeaf.FloatBox f : n.text.floats) b.atoms.add(new double[] {top + f.y, top + f.y + f.h});
            for (TextLayout.Line line : n.text.lines.lines()) b.atoms.add(new double[] {top + line.top(), top + line.bottom()});
        }
        if (n.image != null) b.atoms.add(new double[] {y, y + n.h});
        if (n.cell) {
            // the padding box (excluding borders): adjacent cells of a border-collapse table overlap by the
            // border width, their full border boxes would make every pair of neighbours overlap
            double top = y + n.lbor[0], bottom = y + n.h - n.lbor[2];
            if (bottom > top + 0.01) b.atoms.add(new double[] {top, bottom});
        }
        for (Box c : n.children) walk(c, x, y, b);
    }

    private static final double EPS = 0.01;

    /**
     * Pages of {@code heightOf(page index)}, cut so no unbreakable range is split. Always at least one page.
     * A page that starts inside the body of a table with a {@code <thead>} repeats the header on top
     * (and has that much less room).
     */
    public static List<Page> paginate(IntToDoubleFunction heightOf, Breaks breaks) {
        List<Page> pages = new ArrayList<>();
        double start = 0;
        while (true) {
            double pageHeight = heightOf.applyAsDouble(pages.size());
            // innermost table whose body continues here: its header on top
            TableHead header = null;
            for (TableHead t : breaks.tables) {
                if (t.head != null && start > 0 && t.head[1] <= start + EPS && start < t.bodyBottom - EPS && t.head[1] - t.head[0] <= pageHeight / 2) {
                    if (header == null || t.top > header.top) header = t;
                }
            }
            double page = pageHeight - (header == null ? 0 : header.head[1] - header.head[0]);
            double cut = cutAt(start, page, breaks);
            // innermost table broken by the cut before its footer: room for the footer at the bottom
            TableHead footer = null;
            for (TableHead t : breaks.tables) {
                if (t.foot != null && t.top < cut - EPS && cut < t.foot[0] - EPS && t.foot[1] - t.foot[0] <= page / 2) {
                    if (footer == null || t.top > footer.top) footer = t;
                }
            }
            if (footer != null) cut = cutAt(start, page - (footer.foot[1] - footer.foot[0]), breaks);
            pages.add(new Page(start, cut, header, footer));
            start = cut;
            // stop when nothing visible remains below the cut (forced breaks at the very end included)
            if (breaks.bottom <= start + EPS || pages.size() >= 10_000) break;
        }
        return pages;
    }

    /**
     * Where a page starting at {@code start} with {@code page} of room ends: at a forced break, else as low
     * as possible without splitting an unbreakable range shorter than a page.
     */
    static double cutAt(double start, double page, Breaks breaks) {
        double limit = start + page;
        double cut = limit;
        for (double f : breaks.forced) {
            if (f > start + EPS && f < limit) {
                cut = f;
                break;
            }
        }
        // move the cut above any unbreakable range it crosses, until it crosses none
        while (true) {
            double crossing = Double.POSITIVE_INFINITY;
            for (double[] a : breaks.atoms) {
                if (a[0] < cut - EPS && a[1] > cut + EPS && a[1] - a[0] <= page && a[0] > start + EPS) crossing = Math.min(crossing, a[0]);
            }
            if (Double.isFinite(crossing) && crossing < cut) cut = crossing;
            else break;
        }
        return cut <= start + EPS ? limit : cut;
    }
}
