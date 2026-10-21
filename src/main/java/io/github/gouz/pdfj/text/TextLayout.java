package io.github.gouz.pdfj.text;

import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.style.Style;
import io.github.gouz.pdfj.style.Style.TextAlign;
import io.github.gouz.pdfj.style.Style.VAlign;
import io.github.gouz.pdfj.style.Style.WhiteSpace;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Line breaking of a {@link Paragraph}: greedy, around floats, with CSS line box metrics, bidi and alignment. */
public final class TextLayout {
    private TextLayout() {}

    /** A float the lines wrap around, in the coordinates of the block's content box. */
    public record FloatRect(double x, double y, double w, double h, boolean left) {}

    public sealed interface Item {}

    /**
     * Glyphs of one run, one font and one direction, in visual order.
     *
     * @param x left edge (content box coordinates)
     * @param baseline baseline y (content box coordinates), shifted by vertical-align
     * @param codes the character each glyph stands for (for copy and paste)
     */
    public record GlyphRun(Paragraph.Run run, FontFace face, double x, double width, int[] glyphs, double[] advances, double baseline, int[] codes) implements Item {}

    /** An inline box (its margin box) in the line. */
    public record BoxItem(int index, Object node, double x, double y, double w, double h) implements Item {}

    /** A line: [top, bottom) and its baseline, its content from x, text range [start, end), the height it advances by. */
    public record Line(double top, double bottom, double baseline, double x, double width, List<Item> items, int start, int end, double height) {}

    public record Lines(List<Line> lines, double width, double height) {
        public static final Lines EMPTY = new Lines(List.of(), 0, 0);
    }

    /** rounded like Rust's f32::round (half away from zero): Parley quantizes vertical metrics to whole pixels */
    static double round(double v) {
        return Math.signum(v) * Math.floor(Math.abs(v) + 0.5);
    }

    /**
     * The line-height expanded box of a style drawn with a face (CSS 2 10.8.1, quantized like Parley):
     * {ascent, descent, over, under, x-height}, from the baseline.
     */
    static double[] metrics(Style s, FontFace f) {
        double size = s.fontSize;
        double a = f == null ? 0.8 * size : f.ascender * size / f.unitsPerEm;
        double d = f == null ? 0.2 * size : -f.descender * size / f.unitsPerEm;
        double gap = f == null ? 0 : f.lineGap * size / f.unitsPerEm;
        double lh = switch (s.lineHeight.kind()) {
            case NORMAL -> round(a) + round(d) + round(gap);
            case FACTOR -> size * s.lineHeight.value();
            case PX -> s.lineHeight.value();
        };
        a = round(a);
        d = round(d);
        double over = a + Math.floor((lh - (a + d)) / 2);
        double xh = f != null && f.xHeight > 0 ? f.xHeight * size / f.unitsPerEm : a * 0.56;
        return new double[] {a, d, over, lh - over, round(xh)};
    }

    /** how far vertical-align raises a text run's baseline (sub / super / a length), against its container */
    static double raise(Style s, Style container) {
        VAlign v = s.verticalAlign;
        return round(switch (v.kind()) {
            case SUB -> -(container.fontSize / 5);
            case SUPER -> container.fontSize / 3;
            case LENGTH -> v.length();
            default -> 0;
        });
    }

    private static boolean wraps(Paragraph p, int i) {
        WhiteSpace ws = p.runs.get(p.run[i]).style().whiteSpace;
        return ws == WhiteSpace.NORMAL || ws == WhiteSpace.PRE_WRAP || ws == WhiteSpace.PRE_LINE;
    }

    /** room for a line between y and y + h: {x, width, bottom of the floats it overlaps} */
    private static double[] room(List<FloatRect> floats, double width, double y, double h) {
        double l = 0, r = width, bottom = Double.POSITIVE_INFINITY;
        for (FloatRect f : floats) {
            if (f.y < y + h && f.y + f.h > y) {
                if (f.left) l = Math.max(l, f.x + f.w);
                else r = Math.min(r, f.x);
                bottom = Math.min(bottom, f.y + f.h);
            }
        }
        return new double[] {l, Math.max(r - l, 0), bottom};
    }

    /** The widest unbreakable piece of the paragraph (its min-content width), inline boxes at {@code boxW}. */
    public static double minContent(Paragraph p, double[] boxW) {
        double best = 0, cur = 0, spaces = 0;
        for (int i = 0; i < p.n; i++) {
            if (i > 0 && p.breakBefore[i] || p.kind[i] == Paragraph.BREAK) {
                best = Math.max(best, cur);
                cur = 0;
                spaces = 0;
                if (p.kind[i] == Paragraph.BREAK) continue;
            }
            byte k = p.kind[i];
            double a = k == Paragraph.BOX ? boxW[i] : p.adv[i];
            if (k == Paragraph.SPACE) {
                spaces += a;
            } else {
                if (cur > 0) cur += spaces;
                spaces = 0;
                cur += a;
            }
        }
        return Math.max(best, cur);
    }

    /**
     * Lines at {@code width} (infinite: max-content, one line per forced break) around {@code floats}.
     * {@code boxW} / {@code boxH} / {@code boxBase}: margin-box size and baseline (NaN: bottom edge) of the inline boxes.
     */
    public static Lines layout(Paragraph p, double width, List<FloatRect> floats, double[] boxW, double[] boxH, double[] boxBase, TextAlign align) {
        int n = p.n;
        List<Line> lines = new ArrayList<>();
        double[] strut = metrics(p.container, p.containerFace);
        double estH = Math.max(strut[2] + strut[3], 1);
        double y = 0;
        int i = 0;
        boolean infinite = Double.isInfinite(width);
        for (int guard = 0; guard < 1_000_000; guard++) {
            int s = i;
            while (s < n && (p.kind[s] == Paragraph.SPACE || p.kind[s] == Paragraph.ZW && p.cp[s] == 0x200B)) s++;
            if (s >= n) break;
            double x0 = 0, avail = width, floatBottom = Double.POSITIVE_INFINITY;
            if (!infinite && !floats.isEmpty()) {
                double[] r = room(floats, width, y, estH);
                x0 = r[0];
                avail = r[1];
                floatBottom = r[2];
            }
            double w = 0;
            int lastBreak = -1, end = -1;
            boolean retry = false;
            for (int k = s; k < n; k++) {
                byte kd = p.kind[k];
                if (kd == Paragraph.BREAK) {
                    end = k + 1;
                    break;
                }
                if (k > s && p.breakBefore[k]) lastBreak = k;
                double a = kd == Paragraph.BOX ? boxW[k] : p.adv[k];
                boolean hangs = kd == Paragraph.SPACE || kd == Paragraph.ZW;
                if (!hangs && w + a > avail + 1e-6) {
                    if (lastBreak > s) {
                        end = lastBreak;
                        break;
                    }
                    // nothing fits beside the floats: retry the line below them
                    if (avail < width && Double.isFinite(floatBottom) && floatBottom > y) {
                        y = floatBottom;
                        retry = true;
                        break;
                    }
                    if (k == s && wraps(p, k)) {
                        end = s + 1;
                        while (end < n && isMark(p, end)) end++;
                        break;
                    }
                    if (k > s && wraps(p, k)) {
                        // overflow-wrap: break-word, between characters
                        end = k;
                        while (end > s + 1 && isMark(p, end)) end--;
                        break;
                    }
                    // cannot wrap here: overflow up to the next opportunity
                    int m = k + 1;
                    while (m < n && !p.breakBefore[m] && p.kind[m] != Paragraph.BREAK) m++;
                    end = m;
                    break;
                }
                w += a;
            }
            if (retry) continue;
            if (end < 0) end = n;
            Line line = buildLine(p, s, end, y, x0, avail, infinite, boxW, boxH, boxBase, align, strut);
            lines.add(line);
            y += line.height();
            estH = Math.max(line.height(), 1);
            i = end;
        }
        double maxW = 0;
        for (Line l : lines) maxW = Math.max(maxW, l.width);
        return new Lines(lines, maxW, y);
    }

    private static boolean isMark(Paragraph p, int i) {
        int t = Character.getType(p.cp[i]);
        return p.kind[i] == Paragraph.TEXT && (t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK);
    }

    private static final Map<Integer, Integer> MIRROR = Map.ofEntries(
        Map.entry((int) '(', (int) ')'), Map.entry((int) ')', (int) '('), Map.entry((int) '[', (int) ']'), Map.entry((int) ']', (int) '['),
        Map.entry((int) '{', (int) '}'), Map.entry((int) '}', (int) '{'), Map.entry((int) '<', (int) '>'), Map.entry((int) '>', (int) '<'),
        Map.entry(0xAB, 0xBB), Map.entry(0xBB, 0xAB), Map.entry(0x2039, 0x203A), Map.entry(0x203A, 0x2039));

    private static Line buildLine(Paragraph p, int s, int end, double y, double x0, double avail, boolean infinite,
                                  double[] boxW, double[] boxH, double[] boxBase, TextAlign align, double[] strut) {
        int visEnd = end;
        while (visEnd > s && (p.kind[visEnd - 1] == Paragraph.SPACE || p.kind[visEnd - 1] == Paragraph.BREAK || p.kind[visEnd - 1] == Paragraph.ZW)) visEnd--;
        int count = visEnd - s;
        Integer[] order = new Integer[count];
        for (int k = 0; k < count; k++) order[k] = s + k;
        if (p.bidi && count > 1) {
            byte[] levels = new byte[count];
            for (int k = 0; k < count; k++) levels[k] = p.level[s + k];
            Bidi.reorderVisually(levels, 0, order, 0, count);
        }
        double natural = 0;
        int spaces = 0;
        for (int k = s; k < visEnd; k++) {
            natural += p.kind[k] == Paragraph.BOX ? boxW[k] : p.adv[k];
            if (p.kind[k] == Paragraph.SPACE || p.kind[k] == Paragraph.PSPACE) spaces++;
        }
        boolean rtl = p.container.rtl;
        double offset = 0, extra = 0;
        boolean lastLine = end >= p.n || p.kind[end - 1] == Paragraph.BREAK;
        if (!infinite) {
            double free = Math.max(0, avail - natural);
            switch (align) {
                case LEFT -> offset = 0;
                case RIGHT -> offset = free;
                case CENTER -> offset = free / 2;
                case START -> offset = rtl ? free : 0;
                case JUSTIFY -> {
                    if (!lastLine && spaces > 0) extra = free / spaces;
                    else offset = rtl ? free : 0;
                }
            }
        }
        // items, in visual order
        List<Item> items = new ArrayList<>();
        double x = x0 + offset;
        int curRun = -1, curOdd = -1;
        FontFace curFace = null;
        double runX = x;
        IntBuf glyphs = new IntBuf();
        DoubleBuf advances = new DoubleBuf();
        IntBuf codes = new IntBuf();
        List<Object[]> pending = new ArrayList<>(); // [run, face, x, width, glyphs, advances]
        for (int k = 0; k < count; k++) {
            int idx = order[k];
            byte kd = p.kind[idx];
            if (kd == Paragraph.BOX) {
                if (!glyphs.isEmpty()) pending.add(new Object[] {curRun, curFace, runX, x - runX, glyphs.take(), advances.take(), codes.take()});
                curRun = -1;
                items.add(new BoxItem(idx, p.boxes[idx], x, 0, boxW[idx], boxH[idx]));
                x += boxW[idx];
                continue;
            }
            double a = p.adv[idx] + (kd == Paragraph.SPACE || kd == Paragraph.PSPACE ? extra : 0);
            FontFace f = p.face[idx];
            int g = p.glyph[idx];
            int odd = p.level[idx] & 1;
            if (odd == 1 && f != null) {
                Integer m = MIRROR.get(p.cp[idx]);
                if (m != null && f.has(m)) g = f.glyph(m);
            }
            if (kd == Paragraph.ZW || f == null || (g == 0 && kd != Paragraph.TEXT)) {
                x += a;
                continue;
            }
            if (p.run[idx] != curRun || f != curFace || odd != curOdd) {
                if (!glyphs.isEmpty()) pending.add(new Object[] {curRun, curFace, runX, x - runX, glyphs.take(), advances.take(), codes.take()});
                curRun = p.run[idx];
                curFace = f;
                curOdd = odd;
                runX = x;
            }
            glyphs.add(g);
            advances.add(a);
            codes.add(p.cp[idx]);
            x += a;
        }
        if (!glyphs.isEmpty()) pending.add(new Object[] {curRun, curFace, runX, x - runX, glyphs.take(), advances.take(), codes.take()});
        // vertical metrics (CSS Inline 3, as Parley computes them): the root span box (the strut), the span
        // box of every style on the line, the run boxes of fallback fonts (line-height: normal), the inline
        // boxes; all as extents over / under the baseline, offsets positive upwards
        double over = strut[2], under = strut[3];
        double topH = 0, bottomH = 0;
        double[] runRaise = new double[pending.size()];
        for (int r = 0; r < pending.size(); r++) {
            Paragraph.Run run = p.runs.get((int) pending.get(r)[0]);
            Style st = run.style();
            double off = raise(st, p.container);
            runRaise[r] = off;
            double[] span = metrics(st, run.primary());
            over = Math.max(over, off + span[2]);
            under = Math.max(under, span[3] - off);
            FontFace face = (FontFace) pending.get(r)[1];
            if (st.lineHeight.kind() == Style.LineHeight.Kind.NORMAL && face != run.primary()) {
                double[] rb = metrics(st, face);
                over = Math.max(over, off + rb[2]);
                under = Math.max(under, rb[3] - off);
            }
        }
        // a forced break counts with its own style
        if (end > s && p.kind[end - 1] == Paragraph.BREAK) {
            Paragraph.Run run = p.runs.get(p.run[end - 1]);
            double off = raise(run.style(), p.container);
            double[] span = metrics(run.style(), run.primary());
            over = Math.max(over, off + span[2]);
            under = Math.max(under, span[3] - off);
        }
        double[] boxOff = new double[items.size()];
        int[] boxRel = new int[items.size()];
        for (int k = 0; k < items.size(); k++) {
            if (!(items.get(k) instanceof BoxItem b)) continue;
            Style bs = p.runs.get(p.run[b.index]).style();
            double h = b.h, asc = Double.isNaN(boxBase[b.index]) ? h : boxBase[b.index], desc = h - asc;
            VAlign va = bs.verticalAlign;
            if (va.kind() == VAlign.Kind.TOP || va.kind() == VAlign.Kind.BOTTOM) {
                double hh = round(h);
                if (va.kind() == VAlign.Kind.TOP) topH = Math.max(topH, hh);
                else bottomH = Math.max(bottomH, hh);
                boxRel[k] = va.kind() == VAlign.Kind.TOP ? 1 : 2;
                continue;
            }
            double alignment = switch (va.kind()) {
                case TEXT_TOP -> strut[0] - asc;
                case TEXT_BOTTOM -> desc - strut[1];
                case MIDDLE -> strut[4] / 2 - (asc - desc) / 2;
                default -> 0;
            };
            double shift = switch (va.kind()) {
                case SUB -> -(p.container.fontSize / 5);
                case SUPER -> p.container.fontSize / 3;
                case LENGTH -> va.length();
                default -> 0;
            };
            double off = round(alignment + shift);
            boxOff[k] = off;
            if (asc != 0 || desc != 0) {
                over = Math.max(over, off + asc);
                under = Math.max(under, desc - off);
            }
        }
        double rootH = over + under;
        if (topH > rootH) under += topH - rootH;
        rootH = over + under;
        if (bottomH > rootH) over += bottomH - rootH;
        double lineHeight = over + under;
        double top = round(y);
        double baseline = top + over;
        double bottom = round(baseline + under);

        List<Item> out = new ArrayList<>(items.size() + pending.size());
        int r = 0;
        // keep visual order: boxes and runs interleaved by x
        List<Item> runsOut = new ArrayList<>();
        for (Object[] pr : pending) {
            runsOut.add(new GlyphRun(p.runs.get((int) pr[0]), (FontFace) pr[1], (double) pr[2], (double) pr[3], (int[]) pr[4], (double[]) pr[5], baseline - runRaise[r++], (int[]) pr[6]));
        }
        for (int k = 0; k < items.size(); k++) {
            BoxItem b = (BoxItem) items.get(k);
            double asc = Double.isNaN(boxBase[b.index]) ? b.h : boxBase[b.index];
            double by = boxRel[k] == 1 ? top : boxRel[k] == 2 ? baseline + under - b.h : baseline - boxOff[k] - asc;
            out.add(new BoxItem(b.index, b.node, b.x, by, b.w, b.h));
        }
        out.addAll(runsOut);
        out.sort((a1, b1) -> Double.compare(itemX(a1), itemX(b1)));
        return new Line(top, bottom, baseline, x0 + offset, natural + extra * spaces, out, s, end, lineHeight);
    }

    private static double itemX(Item i) {
        return i instanceof GlyphRun g ? g.x : ((BoxItem) i).x;
    }

    static final class IntBuf {
        int[] a = new int[16];
        int size;

        void add(int v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        boolean isEmpty() {
            return size == 0;
        }

        int[] take() {
            int[] r = java.util.Arrays.copyOf(a, size);
            size = 0;
            return r;
        }
    }

    static final class DoubleBuf {
        double[] a = new double[16];
        int size;

        void add(double v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        double[] take() {
            double[] r = java.util.Arrays.copyOf(a, size);
            size = 0;
            return r;
        }
    }
}
