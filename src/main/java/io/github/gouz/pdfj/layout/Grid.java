package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.layout.Layout.Out;
import io.github.gouz.pdfj.style.GridTemplate;
import io.github.gouz.pdfj.style.GridTemplate.Line;
import io.github.gouz.pdfj.style.GridTemplate.MaxKind;
import io.github.gouz.pdfj.style.GridTemplate.MinKind;
import io.github.gouz.pdfj.style.GridTemplate.Track;
import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Style.Align;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

import static io.github.gouz.pdfj.layout.Layout.MAX;
import static io.github.gouz.pdfj.layout.Layout.MIN;
import static io.github.gouz.pdfj.layout.Layout.clamp;
import static io.github.gouz.pdfj.layout.Layout.definite;

/** CSS Grid: placement (explicit lines, spans, auto-placement) and track sizing (CSS Grid 1, section 11). */
final class Grid {
    private Grid() {}

    private static final class Item {
        Box box;
        int rs, rspan, cs, cspan;
        double[] m;
        double minW = Double.NaN, maxW = Double.NaN, height = Double.NaN;
        double heightFor = Double.NaN;
    }

    static Out layout(Box b, double w, double h, double aw, double ah, double[] pad, double[] mmW, double[] mmH, boolean perform) {
        double pl = pad[3] + b.border[3], pr = pad[1] + b.border[1], pt = pad[0] + b.border[0], pbo = pad[2] + b.border[2];
        double pbH = pl + pr, pbV = pt + pbo;
        double innerW = Double.isNaN(w) ? Double.NaN : Math.max(0, w - pbH);
        double innerH = Double.isNaN(h) ? Double.NaN : Math.max(0, h - pbV);
        double availW = definite(innerW) ? innerW : definite(aw) ? Math.max(0, aw - pbH) : aw;
        double cbW = definite(innerW) ? innerW : Double.NaN, cbH = definite(innerH) ? innerH : Double.NaN;
        double gapC = Math.max(0, Layout.resolve(b.gapColumn, cbW)), gapR = Math.max(0, Layout.resolve(b.gapRow, cbH));
        if (b.tableAutoColumns != null) fitTableColumns(b);

        List<Track> cols = expand(b.columns, definite(availW) ? availW : Double.NaN, gapC);
        List<Track> rows = expand(b.rows, cbH, gapR);
        List<Box> kids = new ArrayList<>();
        for (Box c : b.children) if (!c.absolute) kids.add(c);
        kids.sort(Comparator.comparingInt(c -> c.order));
        List<Item> items = place(b, kids, cols.size(), rows.size());
        int ncols = cols.size(), nrows = rows.size();
        for (Item it : items) {
            ncols = Math.max(ncols, it.cs + it.cspan);
            nrows = Math.max(nrows, it.rs + it.rspan);
        }
        cols = implicit(cols, ncols, b.autoColumns);
        rows = implicit(rows, nrows, b.autoRows);
        for (Item it : items) it.m = Layout.margins(it.box, cbW);

        // columns
        boolean stretchCols = b.justifyContent == null || b.justifyContent == Align.STRETCH;
        double[] colSizes;
        if (definite(innerW)) {
            colSizes = sizeTracks(cols, items, true, innerW, innerW, gapC, stretchCols, null, cbH);
        } else if (availW == MIN) {
            colSizes = sizeTracks(cols, items, true, MIN, Double.NaN, gapC, false, null, cbH);
        } else {
            colSizes = sizeTracks(cols, items, true, MAX, Double.NaN, gapC, false, null, cbH);
            if (definite(availW) && sum(colSizes, gapC) > availW) colSizes = sizeTracks(cols, items, true, availW, availW, gapC, false, null, cbH);
        }
        if (Double.isNaN(w)) {
            w = Math.max(clamp(sum(colSizes, gapC) + pbH, mmW[0], mmW[1]), pbH);
            innerW = w - pbH;
        }
        double[] colX = positions(colSizes, gapC, innerW, b.justifyContent);
        if (b.rtl) {
            for (int i = 0; i < colX.length; i++) colX[i] = innerW - colX[i] - colSizes[i];
        }
        // rows: the items at the width of their columns
        double[] cw = colSizes;
        double[] rowSizes = sizeTracks(rows, items, false, definite(innerH) ? innerH : MAX, cbH, gapR,
            b.alignContent == null || b.alignContent == Align.STRETCH, it -> spanSize(cw, it.cs, it.cspan, gapC), cbH);
        if (Double.isNaN(h)) {
            h = Math.max(clamp(sum(rowSizes, gapR) + pbV, mmH[0], mmH[1]), pbV);
            innerH = h - pbV;
        }
        double[] rowY = positions(rowSizes, gapR, innerH, b.alignContent);
        double finalInnerW = innerW, finalInnerH = innerH;
        double baseline = Double.NaN;
        for (Item it : items) {
            Box c = it.box;
            double ax = b.rtl ? colX[it.cs + it.cspan - 1] : colX[it.cs];
            double areaW = spanSize(colSizes, it.cs, it.cspan, gapC);
            double ay = rowY[it.rs];
            double areaH = spanSize(rowSizes, it.rs, it.rspan, gapR);
            double mH = it.m[1] + it.m[3], mV = it.m[0] + it.m[2];
            Align js = c.justifySelf != null ? c.justifySelf : b.justifyItems;
            Align as = c.alignSelf != null ? c.alignSelf : b.alignItems;
            boolean ratio = c.replaced || c.aspectRatio != null;
            boolean autoL = c.margin[3].isAuto(), autoR = c.margin[1].isAuto(), autoT = c.margin[0].isAuto(), autoB = c.margin[2].isAuto();
            double iw;
            if ((js == null && !ratio || js == Align.STRETCH) && c.size[0].isAuto() && !autoL && !autoR) {
                iw = clampSize(c, 0, Math.max(0, areaW - mH), finalInnerW);
            } else {
                iw = Layout.compute(c, Double.NaN, Double.NaN, Math.max(0, areaW - mH), MAX, finalInnerW, finalInnerH, false, false).w;
            }
            double ih;
            if ((as == null && !ratio || as == Align.STRETCH) && c.size[1].isAuto() && !autoT && !autoB) {
                ih = clampSize(c, 1, Math.max(0, areaH - mV), finalInnerH);
            } else {
                ih = Layout.compute(c, iw, Double.NaN, iw, MAX, finalInnerW, finalInnerH, false, false).h;
            }
            double fx = areaW - iw - mH, fy = areaH - ih - mV;
            double ox = autoL && autoR ? Math.max(0, fx) / 2 + it.m[3] : autoL ? Math.max(0, fx) + it.m[3] : align(js, fx, b.rtl) + it.m[3];
            double oy = autoT && autoB ? Math.max(0, fy) / 2 + it.m[0] : autoT ? Math.max(0, fy) + it.m[0] : align(as, fy, false) + it.m[0];
            Out o = Layout.compute(c, iw, ih, iw, ih, finalInnerW, finalInnerH, perform, false);
            double ix = pl + ax + ox, iy = pt + ay + oy;
            if (perform) {
                c.x = ix;
                c.y = iy;
                System.arraycopy(it.m, 0, c.lmar, 0, 4);
            }
            if (!Double.isNaN(o.baseline)) baseline = iy + o.baseline;
        }
        if (perform) {
            for (Box c : b.children) if (c.absolute) Layout.absolute(b, c, w, h, pl, pt);
        }
        Out out = new Out(w, h);
        out.baseline = baseline;
        return out;
    }

    private static double align(Align a, double free, boolean rtl) {
        if (a == null || a == Align.STRETCH) return rtl ? free : 0;
        return switch (a) {
            case END, FLEX_END -> rtl ? 0 : free;
            case CENTER -> free / 2;
            default -> rtl ? free : 0;
        };
    }

    private static double clampSize(Box c, int dim, double v, double basis) {
        double[] p = Layout.padding(c, basis);
        double pb = dim == 0 ? p[1] + p[3] + c.border[1] + c.border[3] : p[0] + p[2] + c.border[0] + c.border[2];
        double[] mm = Layout.minMax(c, dim, basis, pb);
        return Math.max(clamp(v, mm[0], mm[1]), pb);
    }

    private static double sum(double[] sizes, double gap) {
        double s = gap * Math.max(0, sizes.length - 1);
        for (double v : sizes) s += v;
        return s;
    }

    private static double spanSize(double[] sizes, int start, int span, double gap) {
        double s = gap * (span - 1);
        for (int i = start; i < start + span && i < sizes.length; i++) s += sizes[i];
        return s;
    }

    /** start of each track, after distributing the free space by the content alignment */
    private static double[] positions(double[] sizes, double gap, double inner, Align a) {
        int n = sizes.length;
        double free = definite(inner) ? inner - sum(sizes, gap) : 0;
        double start = 0, between = 0;
        if (a != null && free > 0) {
            switch (a) {
                case END, FLEX_END -> start = free;
                case CENTER -> start = free / 2;
                case SPACE_BETWEEN -> between = n > 1 ? free / (n - 1) : 0;
                case SPACE_AROUND -> {
                    between = free / Math.max(n, 1);
                    start = between / 2;
                }
                case SPACE_EVENLY -> {
                    between = free / (n + 1);
                    start = between;
                }
                default -> {}
            }
        }
        double[] out = new double[n];
        double p = start;
        for (int i = 0; i < n; i++) {
            out[i] = p;
            p += sizes[i] + gap + between;
        }
        return out;
    }

    /** the track list, repeat() expanded ({@code auto-fill} / {@code auto-fit}: as many as fit in {@code space}) */
    static List<Track> expand(List<GridTemplate.Item> template, double space, double gap) {
        List<Track> out = new ArrayList<>();
        if (template == null) return out;
        double fixedOthers = 0;
        int others = 0;
        for (GridTemplate.Item item : template) {
            if (item.repeat() > 0) {
                for (Track t : item.tracks()) {
                    fixedOthers += fixedSize(t, space) * item.repeat();
                    others += item.repeat();
                }
            }
        }
        for (GridTemplate.Item item : template) {
            int count = item.repeat();
            if (count < 0) {
                double one = 0;
                for (Track t : item.tracks()) one += fixedSize(t, space);
                count = 1;
                if (definite(space) && one > 0) {
                    double room = space - fixedOthers - gap * others;
                    count = Math.max(1, (int) Math.floor((room + gap) / (one + gap * item.tracks().size())));
                }
                count = Math.min(count, 1000);
            }
            for (int r = 0; r < count; r++) out.addAll(item.tracks());
        }
        return out;
    }

    private static double fixedSize(Track t, double basis) {
        if (t.max() == MaxKind.FIXED && !(t.maxLen().isPct() && !definite(basis))) return t.maxLen().resolve(definite(basis) ? basis : 0);
        if (t.min() == MinKind.FIXED && !(t.minLen().isPct() && !definite(basis))) return t.minLen().resolve(definite(basis) ? basis : 0);
        return 0;
    }

    /** the tracks up to {@code n}, the implicit ones sized by grid-auto-rows / -columns (auto by default) */
    private static List<Track> implicit(List<Track> explicit, int n, List<GridTemplate.Item> auto) {
        if (explicit.size() >= n) return explicit;
        List<Track> out = new ArrayList<>(explicit);
        List<Track> pattern = expand(auto, Double.NaN, 0);
        int k = 0;
        while (out.size() < n) out.add(pattern.isEmpty() ? Track.AUTO : pattern.get(k++ % pattern.size()));
        return out;
    }

    private record P(int start, int span) {}

    private static P resolve(GridTemplate.Placement p, int explicit) {
        if (p == null) return new P(-1, 1);
        Line s = p.start(), e = p.end();
        P r;
        if (s.kind() == Line.Kind.LINE && e.kind() == Line.Kind.LINE) {
            int a = index(s, explicit), c = index(e, explicit);
            if (a > c) {
                int t = a;
                a = c;
                c = t;
            }
            if (a == c) c = a + 1;
            r = new P(a, c - a);
        } else if (s.kind() == Line.Kind.LINE) {
            r = new P(index(s, explicit), e.kind() == Line.Kind.SPAN ? e.n() : 1);
        } else if (e.kind() == Line.Kind.LINE) {
            int c = index(e, explicit);
            int span = s.kind() == Line.Kind.SPAN ? s.n() : 1;
            r = new P(Math.max(0, c - span), Math.min(span, Math.max(1, c)));
        } else {
            r = new P(-1, s.kind() == Line.Kind.SPAN ? s.n() : e.kind() == Line.Kind.SPAN ? e.n() : 1);
        }
        return new P(r.start < 0 && r.start != -1 ? 0 : r.start, Math.max(1, Math.min(1000, r.span)));
    }

    private static int index(Line l, int explicit) {
        int n = l.n();
        return Math.max(0, n > 0 ? n - 1 : explicit + 1 + n);
    }

    /** CSS Grid 8.5: explicit placements first, then auto-placement (row or column flow, sparse or dense). */
    private static List<Item> place(Box b, List<Box> kids, int explicitCols, int explicitRows) {
        boolean colFlow = b.autoFlow != null && b.autoFlow.contains("column");
        boolean dense = b.autoFlow != null && b.autoFlow.contains("dense");
        List<Item> items = new ArrayList<>(kids.size());
        int[][] spec = new int[kids.size()][4]; // major start, major span, minor start, minor span
        int minorCount = colFlow ? explicitRows : explicitCols;
        for (int i = 0; i < kids.size(); i++) {
            Box c = kids.get(i);
            P row = resolve(c.gridRow, explicitRows), col = resolve(c.gridColumn, explicitCols);
            P major = colFlow ? col : row, minor = colFlow ? row : col;
            spec[i] = new int[] {major.start, major.span, minor.start, minor.span};
            minorCount = Math.max(minorCount, minor.start >= 0 ? minor.start + minor.span : minor.span);
        }
        minorCount = Math.max(1, minorCount);
        List<BitSet> occ = new ArrayList<>();
        int[][] placed = new int[kids.size()][];
        // definite major and minor, then definite major only
        for (int pass = 0; pass < 2; pass++) {
            int[] cursor = new int[0];
            for (int i = 0; i < kids.size(); i++) {
                int[] s = spec[i];
                if (s[0] < 0) continue;
                if (pass == 0 && s[2] >= 0) {
                    placed[i] = new int[] {s[0], s[1], s[2], s[3]};
                    mark(occ, s[0], s[1], s[2], s[3]);
                } else if (pass == 1 && s[2] < 0) {
                    if (cursor.length <= s[0]) cursor = java.util.Arrays.copyOf(cursor, s[0] + 1);
                    int span = Math.min(s[3], minorCount);
                    int start = dense ? 0 : cursor[s[0]];
                    int m = start;
                    while (m + span <= minorCount && !free(occ, s[0], s[1], m, span)) m++;
                    if (m + span > minorCount) {
                        // no room: past the end (an implicit track)
                        m = Math.max(minorCount, start);
                    }
                    placed[i] = new int[] {s[0], s[1], m, span};
                    mark(occ, s[0], s[1], m, span);
                    cursor[s[0]] = m + span;
                }
            }
        }
        // auto major
        int cMaj = 0, cMin = 0;
        for (int i = 0; i < kids.size(); i++) {
            int[] s = spec[i];
            if (s[0] >= 0) continue;
            if (dense) {
                cMaj = 0;
                cMin = 0;
            }
            int span = Math.min(s[3], minorCount);
            if (s[2] >= 0) {
                if (s[2] < cMin) cMaj++;
                cMin = s[2];
                while (!free(occ, cMaj, s[1], s[2], s[3])) cMaj++;
                placed[i] = new int[] {cMaj, s[1], s[2], s[3]};
                mark(occ, cMaj, s[1], s[2], s[3]);
            } else {
                found:
                for (int maj = cMaj; ; maj++) {
                    for (int min = maj == cMaj ? cMin : 0; min + span <= minorCount; min++) {
                        if (free(occ, maj, s[1], min, span)) {
                            placed[i] = new int[] {maj, s[1], min, span};
                            mark(occ, maj, s[1], min, span);
                            cMaj = maj;
                            cMin = min + span;
                            break found;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < kids.size(); i++) {
            Item it = new Item();
            it.box = kids.get(i);
            int[] p = placed[i];
            if (colFlow) {
                it.cs = p[0];
                it.cspan = p[1];
                it.rs = p[2];
                it.rspan = p[3];
            } else {
                it.rs = p[0];
                it.rspan = p[1];
                it.cs = p[2];
                it.cspan = p[3];
            }
            items.add(it);
        }
        return items;
    }

    private static boolean free(List<BitSet> occ, int maj, int majSpan, int min, int minSpan) {
        for (int r = maj; r < maj + majSpan; r++) {
            if (r >= occ.size()) return true;
            BitSet row = occ.get(r);
            int next = row.nextSetBit(min);
            if (next >= 0 && next < min + minSpan) return false;
        }
        return true;
    }

    private static void mark(List<BitSet> occ, int maj, int majSpan, int min, int minSpan) {
        while (occ.size() < maj + majSpan) occ.add(new BitSet());
        for (int r = maj; r < maj + majSpan; r++) occ.get(r).set(min, min + minSpan);
    }

    /** the size an item takes in the axis: min-content / max-content widths, or the height at its columns' width */
    private interface SpanWidth {
        double of(Item it);
    }

    private static double minContribution(Item it, boolean col, SpanWidth width, double cbH) {
        if (col) {
            if (Double.isNaN(it.minW)) contributionsW(it, cbH);
            return it.minW;
        }
        return heightContribution(it, width, cbH);
    }

    private static double maxContribution(Item it, boolean col, SpanWidth width, double cbH) {
        if (col) {
            if (Double.isNaN(it.maxW)) contributionsW(it, cbH);
            return it.maxW;
        }
        return heightContribution(it, width, cbH);
    }

    private static void contributionsW(Item it, double cbH) {
        Box c = it.box;
        double mH = it.m[1] + it.m[3];
        it.minW = Layout.compute(c, Double.NaN, Double.NaN, MIN, MAX, Double.NaN, cbH, false, false).w + mH;
        it.maxW = Layout.compute(c, Double.NaN, Double.NaN, MAX, MAX, Double.NaN, cbH, false, false).w + mH;
    }

    private static double heightContribution(Item it, SpanWidth width, double cbH) {
        double areaW = width.of(it);
        if (it.heightFor == areaW && !Double.isNaN(it.height)) return it.height;
        Box c = it.box;
        double mH = it.m[1] + it.m[3], mV = it.m[0] + it.m[2];
        double kw = c.size[0].isAuto() && !c.replaced && c.aspectRatio == null && !c.margin[1].isAuto() && !c.margin[3].isAuto()
            ? clampSize(c, 0, Math.max(0, areaW - mH), areaW) : Double.NaN;
        Out o = Layout.compute(c, kw, Double.NaN, Math.max(0, areaW - mH), MAX, areaW, cbH, false, false);
        it.heightFor = areaW;
        it.height = o.h + mV;
        return it.height;
    }

    /**
     * Track sizes (CSS Grid 11.4 - 11.8). {@code space}: the room to fill (definite), or MIN / MAX for
     * min-content / max-content sizing; {@code basis}: what percentages resolve against.
     */
    private static double[] sizeTracks(List<Track> tracks, List<Item> items, boolean col, double space, double basis, double gap,
                                       boolean stretchAuto, SpanWidth width, double cbH) {
        int n = tracks.size();
        double[] base = new double[n], growth = new double[n];
        MinKind[] mk = new MinKind[n];
        MaxKind[] xk = new MaxKind[n];
        double[] fr = new double[n];
        boolean def = definite(space), maxMode = space == MAX, minMode = space == MIN;
        for (int i = 0; i < n; i++) {
            Track t = tracks.get(i);
            mk[i] = t.min();
            xk[i] = t.max();
            if (mk[i] == MinKind.FIXED && t.minLen().kind() != Len.Kind.PX && !definite(basis)) mk[i] = MinKind.AUTO;
            if ((xk[i] == MaxKind.FIXED || xk[i] == MaxKind.FIT_CONTENT) && t.maxLen().kind() != Len.Kind.PX && !definite(basis)) xk[i] = MaxKind.AUTO;
            base[i] = mk[i] == MinKind.FIXED ? Math.max(0, t.minLen().resolve(definite(basis) ? basis : 0)) : 0;
            growth[i] = xk[i] == MaxKind.FIXED ? Math.max(0, t.maxLen().resolve(definite(basis) ? basis : 0)) : Double.POSITIVE_INFINITY;
            fr[i] = xk[i] == MaxKind.FR ? t.fr() : 0;
            if (growth[i] < base[i]) growth[i] = base[i];
        }
        // intrinsic sizes: items spanning one track, then more, then items crossing flexible tracks
        List<Item> spanning = new ArrayList<>(items);
        spanning.sort(Comparator.comparingInt(it -> col ? it.cspan : it.rspan));
        for (Item it : spanning) {
            int start = col ? it.cs : it.rs, span = col ? it.cspan : it.rspan;
            if (start + span > n) continue;
            boolean flexible = false;
            for (int i = start; i < start + span; i++) flexible |= xk[i] == MaxKind.FR;
            if (flexible) continue;
            double minC = minContribution(it, col, width, cbH), maxC = maxContribution(it, col, width, cbH);
            if (span == 1) {
                int i = start;
                double c = switch (mk[i]) {
                    case AUTO -> maxMode ? maxC : minC;
                    case MIN_CONTENT -> minC;
                    case MAX_CONTENT -> maxC;
                    case FIXED -> 0;
                };
                base[i] = Math.max(base[i], c);
                double g = switch (xk[i]) {
                    case AUTO, MAX_CONTENT -> maxC;
                    case MIN_CONTENT -> minC;
                    case FIT_CONTENT -> Math.min(maxC, Math.max(minC, tracks.get(i).maxLen().resolve(definite(basis) ? basis : 0)));
                    default -> Double.NaN;
                };
                if (!Double.isNaN(g)) growth[i] = Double.isInfinite(growth[i]) ? g : Math.max(growth[i], g);
            } else {
                double sumBase = gap * (span - 1), sumGrowth = gap * (span - 1);
                int intrinsicMin = 0, intrinsicMax = 0;
                for (int i = start; i < start + span; i++) {
                    sumBase += base[i];
                    sumGrowth += Double.isInfinite(growth[i]) ? base[i] : growth[i];
                    if (mk[i] != MinKind.FIXED) intrinsicMin++;
                    if (xk[i] != MaxKind.FIXED) intrinsicMax++;
                }
                double need = (maxMode ? maxC : minC) - sumBase;
                if (need > 0 && intrinsicMin > 0) {
                    for (int i = start; i < start + span; i++) if (mk[i] != MinKind.FIXED) base[i] += need / intrinsicMin;
                }
                double needG = maxC - sumGrowth;
                if (needG > 0 && intrinsicMax > 0) {
                    for (int i = start; i < start + span; i++) {
                        if (xk[i] == MaxKind.FIXED) continue;
                        growth[i] = (Double.isInfinite(growth[i]) ? base[i] : growth[i]) + needG / intrinsicMax;
                    }
                }
            }
        }
        // items crossing flexible tracks: their minimum contribution to the flexible tracks' base sizes
        for (Item it : spanning) {
            int start = col ? it.cs : it.rs, span = col ? it.cspan : it.rspan;
            if (start + span > n) continue;
            double frSum = 0, sumBase = gap * (span - 1);
            int flexCount = 0;
            for (int i = start; i < start + span; i++) {
                if (xk[i] == MaxKind.FR && mk[i] != MinKind.FIXED) {
                    frSum += fr[i];
                    flexCount++;
                }
                sumBase += base[i];
            }
            if (flexCount == 0) continue;
            double need = minContribution(it, col, width, cbH) - sumBase;
            if (need <= 0) continue;
            for (int i = start; i < start + span; i++) {
                if (xk[i] != MaxKind.FR || mk[i] == MinKind.FIXED) continue;
                base[i] += frSum > 0 ? need * fr[i] / frSum : need / flexCount;
            }
        }
        for (int i = 0; i < n; i++) {
            if (Double.isInfinite(growth[i]) || xk[i] == MaxKind.FR) growth[i] = base[i];
            if (growth[i] < base[i]) growth[i] = base[i];
        }
        // maximize tracks
        if (maxMode) {
            for (int i = 0; i < n; i++) if (xk[i] != MaxKind.FR) base[i] = growth[i];
        } else if (def) {
            double free = space - sumAll(base, gap);
            for (int iter = 0; iter < n && free > 1e-9; iter++) {
                int open = 0;
                for (int i = 0; i < n; i++) if (xk[i] != MaxKind.FR && base[i] < growth[i] - 1e-9) open++;
                if (open == 0) break;
                double share = free / open;
                for (int i = 0; i < n; i++) {
                    if (xk[i] == MaxKind.FR || base[i] >= growth[i] - 1e-9) continue;
                    double add = Math.min(share, growth[i] - base[i]);
                    base[i] += add;
                    free -= add;
                }
            }
        }
        // flexible tracks
        boolean anyFlex = false;
        for (int i = 0; i < n; i++) anyFlex |= xk[i] == MaxKind.FR;
        if (anyFlex && !minMode) {
            double frSize;
            if (def) {
                frSize = findFr(base, xk, fr, 0, n, space - gap * Math.max(0, n - 1));
            } else {
                frSize = 0;
                for (int i = 0; i < n; i++) if (xk[i] == MaxKind.FR) frSize = Math.max(frSize, fr[i] >= 1 ? base[i] / fr[i] : base[i]);
                for (Item it : items) {
                    int start = col ? it.cs : it.rs, span = col ? it.cspan : it.rspan;
                    if (start + span > n) continue;
                    boolean crosses = false;
                    for (int i = start; i < start + span; i++) crosses |= xk[i] == MaxKind.FR;
                    if (!crosses) continue;
                    frSize = Math.max(frSize, findFr(base, xk, fr, start, start + span, maxContribution(it, col, width, cbH) - gap * (span - 1)));
                }
            }
            for (int i = 0; i < n; i++) if (xk[i] == MaxKind.FR) base[i] = Math.max(base[i], fr[i] * frSize);
        }
        // stretch auto tracks
        if (def && stretchAuto) {
            double free = space - sumAll(base, gap);
            int autos = 0;
            for (int i = 0; i < n; i++) if (xk[i] == MaxKind.AUTO) autos++;
            if (free > 1e-9 && autos > 0) for (int i = 0; i < n; i++) if (xk[i] == MaxKind.AUTO) base[i] += free / autos;
        }
        return base;
    }

    private static double sumAll(double[] v, double gap) {
        double s = gap * Math.max(0, v.length - 1);
        for (double x : v) s += x;
        return s;
    }

    /** CSS Grid 11.7.1: the size of an fr for tracks [from, to) filling {@code space} */
    private static double findFr(double[] base, MaxKind[] xk, double[] fr, int from, int to, double space) {
        boolean[] inflexible = new boolean[base.length];
        for (int iter = 0; iter <= to - from; iter++) {
            double leftover = space, flexSum = 0;
            for (int i = from; i < to; i++) {
                if (xk[i] == MaxKind.FR && !inflexible[i]) flexSum += fr[i];
                else leftover -= base[i];
            }
            double hyp = leftover / Math.max(flexSum, 1);
            boolean again = false;
            for (int i = from; i < to; i++) {
                if (xk[i] == MaxKind.FR && !inflexible[i] && fr[i] * hyp < base[i]) {
                    inflexible[i] = true;
                    again = true;
                }
            }
            if (!again) return Math.max(0, hyp);
        }
        return 0;
    }

    /** Auto columns of a table with a width: in proportion to their content (fr of their max-content width). */
    private static void fitTableColumns(Box table) {
        List<Integer> auto = table.tableAutoColumns;
        table.tableAutoColumns = null;
        List<Track> tracks = expand(table.columns, Double.NaN, 0);
        double[] widths = new double[tracks.size()];
        for (Box cell : table.children) {
            GridTemplate.Placement p = cell.gridColumn;
            if (p == null || p.start().kind() != Line.Kind.LINE || p.end().kind() != Line.Kind.SPAN || p.end().n() != 1) continue;
            int c = p.start().n() - 1;
            if (c < 0 || c >= widths.length) continue;
            widths[c] = Math.max(widths[c], Layout.compute(cell, Double.NaN, Double.NaN, MAX, MAX, Double.NaN, Double.NaN, false, false).w);
        }
        List<GridTemplate.Item> out = new ArrayList<>();
        for (int c = 0; c < tracks.size(); c++) {
            Track t = auto.contains(c) ? Track.autoTo(MaxKind.FR, null, Math.max(widths[c], 0.01)) : tracks.get(c);
            out.add(new GridTemplate.Item(List.of(t), 1));
        }
        table.columns = out;
        table.forget();
    }
}
