package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.layout.Layout.Out;
import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Style.Align;
import io.github.gouz.pdfj.style.Style.FlexDirection;
import io.github.gouz.pdfj.style.Style.FlexWrap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static io.github.gouz.pdfj.layout.Layout.MAX;
import static io.github.gouz.pdfj.layout.Layout.MIN;
import static io.github.gouz.pdfj.layout.Layout.clamp;
import static io.github.gouz.pdfj.layout.Layout.definite;

/** Flexbox (CSS Flexible Box Layout, section 9). */
final class Flex {
    private Flex() {}

    private static final class Item {
        Box box;
        double[] m;
        double mainMargin, crossMargin;
        boolean autoMainStart, autoMainEnd, autoCrossStart, autoCrossEnd;
        double pbMain;
        double base, hypo, minMain, maxMain, minCross, maxCross, styleCross;
        double target, cross;
        boolean frozen, stretched;
        double violation;
        double mainPos, crossPos;
        double crossKnownForColumn, crossAvail;
        Align align;

        double outerTarget() {
            return target + mainMargin;
        }

        double outerHypo() {
            return hypo + mainMargin;
        }
    }

    private static final class Line {
        final List<Item> items = new ArrayList<>();
        double cross, crossPos;
    }

    static Out layout(Box b, double w, double h, double aw, double ah, double[] pad, double[] mmW, double[] mmH, double cbw, boolean perform) {
        boolean row = b.flexDirection == FlexDirection.ROW || b.flexDirection == FlexDirection.ROW_REVERSE;
        boolean reverse = b.flexDirection == FlexDirection.ROW_REVERSE || b.flexDirection == FlexDirection.COLUMN_REVERSE;
        boolean wrap = b.flexWrap != FlexWrap.NO_WRAP;
        boolean wrapReverse = b.flexWrap == FlexWrap.WRAP_REVERSE;
        double pl = pad[3] + b.border[3], pr = pad[1] + b.border[1], pt = pad[0] + b.border[0], pbo = pad[2] + b.border[2];
        double pbH = pl + pr, pbV = pt + pbo;
        double innerW = Double.isNaN(w) ? Double.NaN : Math.max(0, w - pbH);
        double innerH = Double.isNaN(h) ? Double.NaN : Math.max(0, h - pbV);
        double availW = definite(innerW) ? innerW : definite(aw) ? Math.max(0, aw - pbH) : aw;
        double availH = definite(innerH) ? innerH : definite(ah) ? Math.max(0, ah - pbV) : MAX;
        double mainSize = row ? innerW : innerH, crossSize = row ? innerH : innerW;
        double availMain = row ? availW : availH, availCross = row ? availH : availW;
        double cbW = definite(innerW) ? innerW : Double.NaN, cbH = definite(innerH) ? innerH : Double.NaN;
        double gapMain = Math.max(0, Layout.resolve(row ? b.gapColumn : b.gapRow, row ? cbW : cbH));
        double gapCross = Math.max(0, Layout.resolve(row ? b.gapRow : b.gapColumn, row ? cbH : cbW));
        int mainDim = row ? 0 : 1, crossDim = row ? 1 : 0;

        List<Box> kids = new ArrayList<>();
        for (Box c : b.children) if (!c.absolute) kids.add(c);
        kids.sort(Comparator.comparingInt(c -> c.order));
        List<Item> items = new ArrayList<>(kids.size());
        boolean minMode = availMain == MIN && !definite(mainSize);
        for (Box c : kids) {
            Item it = new Item();
            it.box = c;
            it.m = Layout.margins(c, cbW);
            double[] p = Layout.padding(c, cbW);
            double pbHi = p[1] + p[3] + c.border[1] + c.border[3], pbVi = p[0] + p[2] + c.border[0] + c.border[2];
            it.pbMain = row ? pbHi : pbVi;
            double pbCross = row ? pbVi : pbHi;
            it.mainMargin = row ? it.m[1] + it.m[3] : it.m[0] + it.m[2];
            it.crossMargin = row ? it.m[0] + it.m[2] : it.m[1] + it.m[3];
            Len[] mg = c.margin;
            it.autoMainStart = row ? mg[3].isAuto() : mg[0].isAuto();
            it.autoMainEnd = row ? mg[1].isAuto() : mg[2].isAuto();
            it.autoCrossStart = row ? mg[0].isAuto() : mg[3].isAuto();
            it.autoCrossEnd = row ? mg[2].isAuto() : mg[1].isAuto();
            double cbMain = row ? cbW : cbH, cbCross = row ? cbH : cbW;
            double[] mmMain = Layout.minMax(c, mainDim, cbMain, it.pbMain);
            double[] mmCross = Layout.minMax(c, crossDim, cbCross, pbCross);
            it.maxMain = mmMain[1];
            it.minCross = mmCross[0];
            it.maxCross = mmCross[1];
            double styleMain = Layout.styleSize(c.size[mainDim], cbMain, it.pbMain, c.borderBox);
            it.styleCross = Layout.styleSize(c.size[crossDim], cbCross, pbCross, c.borderBox);
            if (c.aspectRatio != null && Double.isNaN(styleMain) && !Double.isNaN(it.styleCross)) {
                styleMain = row ? it.styleCross * c.aspectRatio : it.styleCross / c.aspectRatio;
            }
            it.align = c.alignSelf != null ? c.alignSelf : b.alignItems;
            boolean stretch = (it.align == null || it.align == Align.STRETCH) && !it.autoCrossStart && !it.autoCrossEnd;
            // the width a column item is measured at: its own, the line's (stretch), or shrink-to-fit
            double itemCrossKnown = !Double.isNaN(it.styleCross) ? clamp(it.styleCross, it.minCross, it.maxCross)
                : stretch && definite(crossSize) && !row && !c.replaced ? clamp(crossSize - it.crossMargin, it.minCross, it.maxCross) : Double.NaN;
            double crossAvail = definite(availCross) ? Math.max(0, availCross - it.crossMargin) : availCross;
            // flex base size
            Len basis = c.basis;
            double base;
            boolean basisDefinite = !basis.isAuto() && !(basis.kind() != Len.Kind.PX && !definite(cbMain));
            if (basisDefinite) base = Layout.styleSize(basis, cbMain, it.pbMain, c.borderBox);
            else if (!Double.isNaN(styleMain)) base = styleMain;
            else base = contentMain(c, row, itemCrossKnown, crossAvail, cbW, cbH, MAX);
            if (Double.isNaN(base)) base = it.pbMain;
            it.base = base;
            // automatic minimum size: the content's min-content size
            if (c.minSize[mainDim].isAuto() && !c.overflowHidden) {
                double contentMin = row ? Layout.compute(c, Double.NaN, Double.NaN, MIN, MAX, cbW, cbH, false, false).w
                    : contentMain(c, false, itemCrossKnown, crossAvail, cbW, cbH, MIN);
                if (!Double.isNaN(styleMain)) contentMin = Math.min(contentMin, styleMain);
                it.minMain = Math.min(contentMin, it.maxMain);
            } else {
                it.minMain = mmMain[0];
            }
            it.hypo = clamp(base, it.minMain, it.maxMain);
            if (minMode) it.hypo = row ? Math.max(it.minMain, Math.min(it.hypo, Layout.compute(c, Double.NaN, Double.NaN, MIN, MAX, cbW, cbH, false, false).w)) : it.hypo;
            it.crossKnownForColumn = itemCrossKnown;
            it.crossAvail = crossAvail;
            items.add(it);
        }
        // lines
        List<Line> lines = new ArrayList<>();
        double limit = definite(mainSize) ? mainSize : definite(availMain) ? availMain : availMain == MIN ? 0 : Double.POSITIVE_INFINITY;
        Line cur = new Line();
        double used = 0;
        for (Item it : items) {
            double add = it.outerHypo() + (cur.items.isEmpty() ? 0 : gapMain);
            if (wrap && !cur.items.isEmpty() && used + add > limit + 1e-6) {
                lines.add(cur);
                cur = new Line();
                used = 0;
                add = it.outerHypo();
            }
            cur.items.add(it);
            used += add;
        }
        lines.add(cur);
        // the container's main size
        double containerMain;
        if (definite(mainSize)) {
            containerMain = mainSize;
        } else {
            double longest = 0;
            for (Line l : lines) {
                double s = 0;
                for (Item it : l.items) s += it.outerHypo();
                s += gapMain * Math.max(0, l.items.size() - 1);
                longest = Math.max(longest, s);
            }
            containerMain = definite(availMain) ? Math.min(longest, availMain) : longest;
            double[] mm = row ? mmW : mmH;
            double pbm = row ? pbH : pbV;
            containerMain = Math.max(0, clamp(containerMain + pbm, mm[0], mm[1]) - pbm);
        }
        for (Line l : lines) resolveFlexible(l.items, containerMain, gapMain);
        // cross sizes
        for (Item it : items) {
            Box c = it.box;
            double cross;
            if (!Double.isNaN(it.styleCross)) {
                cross = it.styleCross;
            } else if (row) {
                cross = Layout.compute(c, it.target, Double.NaN, it.target, MAX, cbW, cbH, false, false).h;
            } else if (!Double.isNaN(it.crossKnownForColumn)) {
                cross = it.crossKnownForColumn;
            } else {
                cross = Layout.compute(c, Double.NaN, it.target, it.crossAvail, it.target, cbW, cbH, false, false).w;
            }
            it.cross = clamp(cross, it.minCross, it.maxCross);
        }
        for (Line l : lines) {
            double max = 0;
            for (Item it : l.items) max = Math.max(max, it.cross + it.crossMargin);
            l.cross = max;
        }
        if (!wrap && definite(crossSize)) lines.get(0).cross = crossSize;
        double sumCross = gapCross * Math.max(0, lines.size() - 1);
        for (Line l : lines) sumCross += l.cross;
        double containerCross;
        if (definite(crossSize)) {
            containerCross = crossSize;
        } else {
            double[] mm = row ? mmH : mmW;
            double pbc = row ? pbV : pbH;
            containerCross = Math.max(0, clamp(sumCross + pbc, mm[0], mm[1]) - pbc);
            if (!wrap) lines.get(0).cross = Math.max(lines.get(0).cross, containerCross);
        }
        // align-content
        double freeCross = containerCross - sumCross;
        double crossStart = 0, crossBetween = 0;
        if (wrap) {
            int nl = lines.size();
            Align ac = b.alignContent;
            if (ac == null || ac == Align.STRETCH) {
                if (freeCross > 0) for (Line l : lines) l.cross += freeCross / nl;
            } else {
                switch (ac) {
                    case END, FLEX_END -> crossStart = freeCross;
                    case CENTER -> crossStart = freeCross / 2;
                    case SPACE_BETWEEN -> crossBetween = nl > 1 && freeCross > 0 ? freeCross / (nl - 1) : 0;
                    case SPACE_AROUND -> {
                        if (freeCross > 0) {
                            crossBetween = freeCross / nl;
                            crossStart = crossBetween / 2;
                        }
                    }
                    case SPACE_EVENLY -> {
                        if (freeCross > 0) {
                            crossBetween = freeCross / (nl + 1);
                            crossStart = crossBetween;
                        }
                    }
                    default -> {}
                }
            }
        }
        double pos = crossStart;
        for (Line l : lines) {
            l.crossPos = pos;
            pos += l.cross + gapCross + crossBetween;
        }
        if (wrapReverse) for (Line l : lines) l.crossPos = containerCross - l.crossPos - l.cross;
        // stretch, cross positions
        for (Line l : lines) {
            for (Item it : l.items) {
                boolean stretch = (it.align == null || it.align == Align.STRETCH) && Double.isNaN(it.styleCross) && !it.autoCrossStart && !it.autoCrossEnd;
                if (stretch && !it.box.replaced) {
                    it.cross = Math.max(clamp(l.cross - it.crossMargin, it.minCross, it.maxCross), 0);
                    it.stretched = true;
                }
                double mStart = row ? it.m[0] : it.m[3];
                double free = l.cross - it.cross - it.crossMargin;
                double off;
                if (it.autoCrossStart && it.autoCrossEnd) off = Math.max(0, free) / 2;
                else if (it.autoCrossStart) off = Math.max(0, free);
                else if (it.autoCrossEnd) off = 0;
                else {
                    Align a = it.align == null ? Align.STRETCH : it.align;
                    boolean flip = wrapReverse;
                    off = switch (a) {
                        case END, FLEX_END -> flip ? 0 : free;
                        case CENTER -> free / 2;
                        default -> flip ? free : 0;
                    };
                }
                it.crossPos = l.crossPos + off + mStart;
            }
        }
        // main positions
        boolean startAtEnd = row ? (b.rtl != reverse) : reverse;
        for (Line l : lines) {
            double sum = gapMain * Math.max(0, l.items.size() - 1);
            int autos = 0;
            for (Item it : l.items) {
                sum += it.outerTarget();
                if (it.autoMainStart) autos++;
                if (it.autoMainEnd) autos++;
            }
            double free = containerMain - sum;
            double start = 0, between = 0, autoSize = 0;
            int n = l.items.size();
            if (autos > 0 && free > 0) {
                autoSize = free / autos;
            } else {
                Align jc = b.justifyContent == null ? Align.FLEX_START : b.justifyContent;
                switch (jc) {
                    case END, FLEX_END -> start = free;
                    case CENTER -> start = free / 2;
                    case SPACE_BETWEEN -> between = n > 1 && free > 0 ? free / (n - 1) : 0;
                    case SPACE_AROUND -> {
                        if (free > 0) {
                            between = free / n;
                            start = between / 2;
                        }
                    }
                    case SPACE_EVENLY -> {
                        if (free > 0) {
                            between = free / (n + 1);
                            start = between;
                        }
                    }
                    default -> {}
                }
            }
            double p0 = start;
            for (Item it : l.items) {
                double ms = row ? (startAtEnd ? it.m[1] : it.m[3]) : (startAtEnd ? it.m[2] : it.m[0]);
                double me = row ? (startAtEnd ? it.m[3] : it.m[1]) : (startAtEnd ? it.m[0] : it.m[2]);
                boolean aStart = startAtEnd ? it.autoMainEnd : it.autoMainStart;
                boolean aEnd = startAtEnd ? it.autoMainStart : it.autoMainEnd;
                p0 += aStart ? autoSize : 0;
                double offset = p0 + ms;
                it.mainPos = startAtEnd ? containerMain - offset - it.target : offset;
                p0 = offset + it.target + me + (aEnd ? autoSize : 0) + gapMain + between;
            }
        }
        double outW = Double.isNaN(w) ? (row ? containerMain : containerCross) + pbH : w;
        double outH = Double.isNaN(h) ? (row ? containerCross : containerMain) + pbV : h;
        double baseline = Double.NaN;
        for (Item it : items) {
            Box c = it.box;
            double kw = row ? it.target : it.cross, kh = row ? it.cross : it.target;
            double ix = pl + (row ? it.mainPos : it.crossPos), iy = pt + (row ? it.crossPos : it.mainPos);
            Out o = Layout.compute(c, kw, kh, kw, kh, cbW, cbH, perform, false);
            if (perform) {
                c.x = ix;
                c.y = iy;
                System.arraycopy(it.m, 0, c.lmar, 0, 4);
            }
            if (!Double.isNaN(o.baseline)) baseline = iy + o.baseline;
        }
        if (perform) {
            for (Box c : b.children) if (c.absolute) Layout.absolute(b, c, outW, outH, pl, pt);
        }
        Out out = new Out(outW, outH);
        out.baseline = baseline;
        return out;
    }

    /** the content size of an item in the main axis: max-content (or min-content) width, or height at its width */
    private static double contentMain(Box c, boolean row, double crossKnown, double crossAvail, double cbW, double cbH, double mode) {
        if (row) return Layout.compute(c, Double.NaN, Double.NaN, mode, MAX, cbW, cbH, false, false).w;
        double aw = !Double.isNaN(crossKnown) ? crossKnown : crossAvail;
        return Layout.compute(c, crossKnown, Double.NaN, aw, MAX, cbW, cbH, false, false).h;
    }

    /** CSS Flexbox 9.7: grow or shrink the items of a line to fill {@code main}. */
    private static void resolveFlexible(List<Item> items, double main, double gap) {
        double usedHypo = gap * Math.max(0, items.size() - 1);
        for (Item it : items) usedHypo += it.outerHypo();
        boolean grow = usedHypo < main;
        for (Item it : items) {
            it.target = it.hypo;
            double factor = grow ? it.box.grow : it.box.shrink;
            it.frozen = factor == 0 || (grow && it.base > it.hypo) || (!grow && it.base < it.hypo);
        }
        double initialFree = main - gap * Math.max(0, items.size() - 1);
        for (Item it : items) initialFree -= it.frozen ? it.outerTarget() : it.base + it.mainMargin;
        for (int iter = 0; iter <= items.size(); iter++) {
            double free = main - gap * Math.max(0, items.size() - 1);
            double sumFactors = 0, sumScaled = 0;
            boolean any = false;
            for (Item it : items) {
                if (it.frozen) {
                    free -= it.outerTarget();
                } else {
                    any = true;
                    free -= it.base + it.mainMargin;
                    sumFactors += grow ? it.box.grow : it.box.shrink;
                    sumScaled += it.box.shrink * Math.max(0, it.base - it.pbMain);
                }
            }
            if (!any) break;
            if (sumFactors < 1) {
                double f = initialFree * sumFactors;
                if (Math.abs(f) < Math.abs(free)) free = f;
            }
            for (Item it : items) {
                if (it.frozen) continue;
                if (grow) {
                    it.target = it.base + (sumFactors > 0 ? free * it.box.grow / sumFactors : 0);
                } else if (sumScaled > 0) {
                    double scaled = it.box.shrink * Math.max(0, it.base - it.pbMain);
                    it.target = it.base + free * scaled / sumScaled;
                } else {
                    it.target = it.base;
                }
            }
            double total = 0;
            for (Item it : items) {
                if (it.frozen) continue;
                double c = Math.max(clamp(it.target, it.minMain, it.maxMain), it.pbMain);
                it.violation = c - it.target;
                total += it.violation;
                it.target = c;
            }
            if (Math.abs(total) < 1e-9) {
                for (Item it : items) it.frozen = true;
                break;
            }
            for (Item it : items) {
                if (it.frozen) continue;
                if (total > 0 && it.violation > 0 || total < 0 && it.violation < 0) it.frozen = true;
            }
        }
    }
}
