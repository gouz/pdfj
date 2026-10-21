package io.github.gouz.pdfj.style;

import io.github.gouz.pdfj.css.Css;
import io.github.gouz.pdfj.css.Css.Decl;
import io.github.gouz.pdfj.font.Fonts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computed style: cascade result for one element, inheritance, units, shorthands.
 * Lengths are in pt: 1px = 0.75pt (96px per inch, like a browser); a unitless length is pt, like react-pdf.
 */
public final class Style implements Cloneable {
    /** pt per CSS px */
    public static final double PX = 0.75;

    public enum Display { INLINE, BLOCK, FLEX, GRID, LIST_ITEM, TABLE, TABLE_ROW_GROUP, TABLE_ROW, TABLE_CELL, TABLE_CAPTION, CONTENTS, NONE }

    public enum TextAlign { START, LEFT, RIGHT, CENTER, JUSTIFY }

    public enum WhiteSpace { NORMAL, NOWRAP, PRE, PRE_WRAP, PRE_LINE }

    public enum TextTransform { NONE, UPPER, LOWER, CAPITALIZE }

    public enum BorderStyle { NONE, SOLID, DASHED, DOTTED, DOUBLE }

    public enum ObjectFit { CONTAIN, COVER, FILL, NONE }

    public enum Float { NONE, LEFT, RIGHT }

    public enum Clear { NONE, LEFT, RIGHT, BOTH }

    public enum FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }

    public enum FlexWrap { NO_WRAP, WRAP, WRAP_REVERSE }

    /** {@code justify-content} / {@code align-*} / {@code justify-*} values; null = normal */
    public enum Align { START, END, FLEX_START, FLEX_END, CENTER, BASELINE, STRETCH, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }

    /** {@code background-origin} / {@code background-clip} box */
    public enum BoxArea { BORDER, PADDING, CONTENT, /** {@code background-clip: text}: the layer paints the text of the element instead of its box */ TEXT }

    public record LineHeight(Kind kind, double value) {
        public enum Kind { NORMAL, FACTOR, PX }

        public static final LineHeight NORMAL = new LineHeight(Kind.NORMAL, 0);
    }

    public record VAlign(Kind kind, double length) {
        public enum Kind { TOP, MIDDLE, BOTTOM, BASELINE, SUB, SUPER, TEXT_TOP, TEXT_BOTTOM, /** raise by this many pt (negative: lower) */ LENGTH }

        public static final VAlign BASELINE = new VAlign(Kind.BASELINE, 0);

        static VAlign of(Kind k) {
            return new VAlign(k, 0);
        }

        /** sub / super / a length: shifts text runs (top / middle / bottom are for boxes and table cells) */
        public boolean shifts() {
            return kind == Kind.SUB || kind == Kind.SUPER || kind == Kind.LENGTH;
        }
    }

    /** {@code linear-gradient()} / {@code radial-gradient()} (and {@code repeating-} variants) of a background */
    public record Gradient(GradientKind kind, List<Stop> stops, boolean repeating) {}

    /** color, position (null = spread evenly between neighbours) */
    public record Stop(Rgba color, Len position) {}

    public sealed interface GradientKind {
        /** angle in degrees, CSS convention: 0 = to top, clockwise */
        record Linear(double angle) implements GradientKind {}

        /** {@code to top right}...: x = -1 left / 1 right / 0, y = -1 top / 1 bottom / 0; the angle depends on the box */
        record LinearCorner(int x, int y) implements GradientKind {}

        /** circle (else ellipse), center */
        record Radial(boolean circle, Len atX, Len atY) implements GradientKind {}
    }

    public record BgSize(Kind kind, Len w, Len h) {
        public enum Kind { AUTO, COVER, CONTAIN, SIZE }

        public static final BgSize AUTO = new BgSize(Kind.AUTO, null, null);
        public static final BgSize COVER = new BgSize(Kind.COVER, null, null);
        public static final BgSize CONTAIN = new BgSize(Kind.CONTAIN, null, null);

        /** width, height (each may be auto: keeps the image's ratio) */
        public static BgSize size(Len w, Len h) {
            return new BgSize(Kind.SIZE, w, h);
        }
    }

    /** The image of a background layer: a url or a gradient */
    public record BgSource(String url, Gradient gradient) {}

    /** Background layers, first on top. Each list is cycled to the number of images, as in CSS. */
    public static final class Background {
        public List<BgSource> images = new ArrayList<>();
        public List<BgSize> sizes = new ArrayList<>();
        public List<Len[]> positions = new ArrayList<>();
        public List<boolean[]> repeats = new ArrayList<>();
        public List<BoxArea> origins = new ArrayList<>();
        public List<BoxArea> clips = new ArrayList<>();
        /** {@code background-attachment: fixed}: positioned against the page instead of the box */
        public List<Boolean> fixed = new ArrayList<>();

        Background copy() {
            Background b = new Background();
            b.images = new ArrayList<>(images);
            b.sizes = new ArrayList<>(sizes);
            b.positions = new ArrayList<>(positions);
            b.repeats = new ArrayList<>(repeats);
            b.origins = new ArrayList<>(origins);
            b.clips = new ArrayList<>(clips);
            b.fixed = new ArrayList<>(fixed);
            return b;
        }

        /** value of layer {@code i} of a list (cycled), or the initial value */
        public static <T> T nth(List<T> list, int i, T initial) {
            return list.isEmpty() ? initial : list.get(i % list.size());
        }
    }

    /** One {@code box-shadow} */
    public record Shadow(double dx, double dy, double blur, double spread, Rgba color, boolean inset) {}

    /** a counter name and its value in {@code counter-reset} / {@code -increment} / {@code -set} */
    public record Counter(String name, int value) {}

    // inherited
    /** the family as registered (first usable family of the stack) */
    public String fontFamily;
    public double fontSize;
    public double fontWeight;
    public boolean italic;
    public Rgba color;
    public TextAlign textAlign;
    public LineHeight lineHeight;
    public double letterSpacing;
    public double wordSpacing;
    public TextTransform transform;
    public WhiteSpace whiteSpace;
    public String listStyle;
    public double borderSpacing;
    // text-decoration is not inherited in CSS but propagates to descendants' text: same effect here
    public boolean underline;
    public boolean strike;
    public boolean rtl;
    /** custom properties ({@code --name}), inherited; shared, never mutated */
    public Map<String, String> vars;
    // not inherited
    public Display display;
    /**
     * {@code inline-block} / {@code inline-flex} / {@code inline-grid} / {@code inline-table}: {@code display} is
     * the inner layout, the box itself sits in a line of text
     */
    public boolean inlineBlock;
    public boolean positionAbsolute;
    public Float floating;
    public Clear clear;
    public boolean borderBox;
    public Len[] size;
    public Len[] minSize;
    public Len[] maxSize;
    /** top, right, bottom, left */
    public Len[] margin;
    public Len[] padding;
    public Len[] inset;
    public double[] borderWidth;
    public BorderStyle[] borderStyle;
    /** null = currentColor */
    public Rgba[] borderColor;
    /** top-left, top-right, bottom-right, bottom-left */
    public Len[] radius;
    public Rgba background;
    /** background image layers ({@code url()}, gradients) and their size / position / repeat / origin / clip */
    public Background bg;
    public List<Shadow> shadows;
    public List<Counter> counterReset;
    public List<Counter> counterIncrement;
    public List<Counter> counterSet;
    /** {@code page: name}: the named @page of a top-level element */
    public String page;
    public double opacity;
    /** {@code transform} / {@code transform-origin}, resolved at draw time (they depend on the box size) */
    public String transformFn;
    public String transformOrigin;
    /** {@code content} of a ::before / ::after */
    public String content;
    public boolean overflowHidden;
    public FlexDirection flexDirection;
    public FlexWrap flexWrap;
    public double flexGrow;
    public double flexShrink;
    public Len flexBasis;
    public Align justifyContent;
    public Align alignContent;
    public Align alignItems;
    public Align alignSelf;
    public Align justifyItems;
    public Align justifySelf;
    /** row, column */
    public Len[] gap;
    public int order;
    public List<GridTemplate.Item> gridTemplateColumns;
    public List<GridTemplate.Item> gridTemplateRows;
    public List<GridTemplate.Item> gridAutoRows;
    public List<GridTemplate.Item> gridAutoColumns;
    public String gridAutoFlow;
    public GridTemplate.Placement gridColumn;
    public GridTemplate.Placement gridRow;
    public Double aspectRatio;
    public VAlign verticalAlign;
    public ObjectFit objectFit;
    public boolean borderCollapse;
    public boolean breakBefore;
    public boolean breakAfter;
    public boolean breakInsideAvoid;

    private Style() {}

    /** Initial style of the root: {@code font} / {@code fontSize} are the document defaults. */
    public static Style root(String font, double fontSize) {
        Style s = new Style();
        String family = Fonts.resolveStack(font);
        if (family == null) family = Fonts.resolveStack("sans-serif");
        s.fontFamily = family == null ? "" : family;
        s.fontSize = fontSize;
        s.initial();
        return s;
    }

    private void initial() {
        fontWeight = 400;
        italic = false;
        color = Rgba.BLACK;
        textAlign = TextAlign.START;
        lineHeight = LineHeight.NORMAL;
        letterSpacing = 0;
        wordSpacing = 0;
        transform = TextTransform.NONE;
        whiteSpace = WhiteSpace.NORMAL;
        listStyle = "disc";
        borderSpacing = 2.0;
        underline = false;
        strike = false;
        rtl = false;
        vars = null;
        resetNonInherited();
    }

    private void resetNonInherited() {
        display = Display.BLOCK;
        inlineBlock = false;
        positionAbsolute = false;
        floating = Float.NONE;
        clear = Clear.NONE;
        borderBox = false;
        size = new Len[] {Len.AUTO, Len.AUTO};
        minSize = new Len[] {Len.AUTO, Len.AUTO};
        maxSize = new Len[] {Len.AUTO, Len.AUTO};
        margin = new Len[] {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
        padding = new Len[] {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
        inset = new Len[] {Len.AUTO, Len.AUTO, Len.AUTO, Len.AUTO};
        borderWidth = new double[4];
        borderStyle = new BorderStyle[] {BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE};
        borderColor = new Rgba[4];
        radius = new Len[] {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
        background = null;
        bg = null;
        shadows = null;
        counterReset = null;
        counterIncrement = null;
        counterSet = null;
        page = null;
        opacity = 1.0;
        transformFn = null;
        transformOrigin = null;
        content = null;
        overflowHidden = false;
        flexDirection = FlexDirection.ROW;
        flexWrap = FlexWrap.NO_WRAP;
        flexGrow = 0;
        flexShrink = 1;
        flexBasis = Len.AUTO;
        justifyContent = null;
        alignContent = null;
        alignItems = null;
        alignSelf = null;
        justifyItems = null;
        justifySelf = null;
        gap = new Len[] {Len.ZERO, Len.ZERO};
        order = 0;
        gridTemplateColumns = null;
        gridTemplateRows = null;
        gridAutoRows = null;
        gridAutoColumns = null;
        gridAutoFlow = null;
        gridColumn = null;
        gridRow = null;
        aspectRatio = null;
        verticalAlign = VAlign.BASELINE;
        objectFit = ObjectFit.FILL;
        borderCollapse = false;
        breakBefore = false;
        breakAfter = false;
        breakInsideAvoid = false;
    }

    /** A copy (arrays copied, shared values are immutable). */
    public Style copy() {
        try {
            Style s = (Style) super.clone();
            s.size = size.clone();
            s.minSize = minSize.clone();
            s.maxSize = maxSize.clone();
            s.margin = margin.clone();
            s.padding = padding.clone();
            s.inset = inset.clone();
            s.borderWidth = borderWidth.clone();
            s.borderStyle = borderStyle.clone();
            s.borderColor = borderColor.clone();
            s.radius = radius.clone();
            s.gap = gap.clone();
            return s;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** Child style: inherited properties from {@code this}, the rest initial. */
    public Style inherit() {
        Style s = copy();
        s.resetNonInherited();
        s.display = Display.INLINE;
        return s;
    }

    /** Apply the winning declarations (in cascade order) on top of {@code parent.inherit()}. */
    public static Style compute(Style parent, List<Decl> decls, double rootFontSize) {
        Style s = parent.inherit();
        // custom properties first: every other value may use them
        Map<String, String> vars = null;
        for (Decl d : decls) {
            if (!d.prop().startsWith("--")) continue;
            if (vars == null) vars = s.vars == null ? new HashMap<>() : new HashMap<>(s.vars);
            vars.put(d.prop(), d.value());
        }
        if (vars != null) s.vars = vars;
        // var() substituted; a reference to an undefined variable without fallback drops the declaration
        List<String[]> values = new ArrayList<>(decls.size());
        for (Decl d : decls) {
            if (d.prop().startsWith("--")) continue;
            String v = Values.substituteVars(d.value(), s.vars, 0);
            if (v != null) values.add(new String[] {d.prop(), v});
        }
        // font-size next: em units of every other property depend on it
        for (String[] pv : values) {
            if (pv[0].equals("font")) {
                applyFontShorthand(s, pv[1], parent.fontSize, rootFontSize);
            } else if (pv[0].equals("font-size")) {
                Double fs = fontSize(pv[1], parent.fontSize, rootFontSize);
                if (fs != null) s.fontSize = fs;
            }
        }
        double em = s.fontSize;
        for (String[] pv : values) apply(s, parent, pv[0], pv[1], em, rootFontSize);
        return s;
    }

    public double lineHeightPx() {
        return switch (lineHeight.kind()) {
            case NORMAL -> fontSize * 1.15;
            case FACTOR -> fontSize * lineHeight.value();
            case PX -> lineHeight.value();
        };
    }

    static Double fontSize(String value, double parent, double rem) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        Double kw = switch (v) {
            case "xx-small" -> 9.0 / 16.0;
            case "x-small" -> 10.0 / 16.0;
            case "small" -> 13.0 / 16.0;
            case "medium" -> 1.0;
            case "large" -> 18.0 / 16.0;
            case "x-large" -> 1.5;
            case "xx-large" -> 2.0;
            case "smaller" -> -1.0;
            case "larger" -> -2.0;
            default -> null;
        };
        if (kw != null) {
            if (kw == -1.0) return parent / 1.2;
            if (kw == -2.0) return parent * 1.2;
            return rem * kw;
        }
        Len l = Values.len(v, parent, rem);
        if (l == null) return null;
        return switch (l.kind()) {
            case PX -> l.px();
            case PCT -> parent * l.pct() / 100.0;
            case CALC -> l.px() + parent * l.pct() / 100.0;
            case AUTO -> null;
        };
    }

    /** 1 to 4 values, CSS order: top right bottom left */
    static <T> T[] four(List<T> vals, T[] out) {
        switch (vals.size()) {
            case 1 -> Arrays.fill(out, vals.get(0));
            case 2 -> {
                out[0] = out[2] = vals.get(0);
                out[1] = out[3] = vals.get(1);
            }
            case 3 -> {
                out[0] = vals.get(0);
                out[1] = out[3] = vals.get(1);
                out[2] = vals.get(2);
            }
            case 4 -> {
                for (int i = 0; i < 4; i++) out[i] = vals.get(i);
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    static List<Len> lens(String v, double em, double rem) {
        List<Len> out = new ArrayList<>();
        for (String p : Values.splitValues(v)) {
            Len l = Values.len(p, em, rem);
            if (l == null) return null;
            out.add(l);
        }
        return out;
    }

    private static Len[] fourLens(String v, double em, double rem) {
        List<Len> l = lens(v, em, rem);
        return l == null ? null : four(l, new Len[4]);
    }

    /** {@code 1px solid red}: any order, missing parts reset (width medium = 3, style none, color currentColor) */
    static Object[] borderShorthand(String v, double em, double rem) {
        double w = 3.0;
        BorderStyle st = BorderStyle.NONE;
        Rgba c = null;
        for (String part : Values.splitValues(v)) {
            BorderStyle s = Values.borderStyle(part);
            if (s != null) {
                st = s;
                continue;
            }
            Double x = Values.borderWidth(part, em, rem);
            if (x != null) {
                w = x;
            } else if (!part.equalsIgnoreCase("currentcolor")) {
                c = Colors.parse(part);
            }
        }
        return new Object[] {w, st, c};
    }

    /** {@code font: italic bold 12px/1.5 Georgia, serif} (size and family required) */
    static void applyFontShorthand(Style s, String v, double parentFs, double rem) {
        List<String> parts = Values.splitValues(v);
        int i = -1;
        for (int k = 0; k < parts.size(); k++) {
            String p = parts.get(k);
            String size = p.split("/", 2)[0];
            if (fontSize(size, parentFs, rem) != null && !p.isEmpty() && (Character.isDigit(p.charAt(0)) || p.charAt(0) == '.')) {
                i = k;
                break;
            }
        }
        if (i < 0) return;
        s.italic = false;
        s.fontWeight = 400;
        for (String p : parts.subList(0, i)) {
            String w = p.toLowerCase(Locale.ROOT);
            if (w.equals("italic") || w.equals("oblique")) s.italic = true;
            else {
                Double fw = Values.fontWeight(w, 400);
                if (fw != null) s.fontWeight = fw;
            }
        }
        String[] sl = parts.get(i).split("/", 2);
        Double fs = fontSize(sl[0], parentFs, rem);
        if (fs != null) s.fontSize = fs;
        if (sl.length > 1) {
            LineHeight lh = lineHeight(sl[1], s.fontSize, rem);
            if (lh != null) s.lineHeight = lh;
        }
        String family = Fonts.resolveStack(String.join(" ", parts.subList(i + 1, parts.size())));
        if (family != null) s.fontFamily = family;
    }

    static LineHeight lineHeight(String v, double em, double rem) {
        if (v.trim().equalsIgnoreCase("normal")) return LineHeight.NORMAL;
        Double f = Values.number(v);
        if (f != null) return new LineHeight(LineHeight.Kind.FACTOR, f);
        Len l = Values.len(v, em, rem);
        if (l == null) return null;
        return switch (l.kind()) {
            case PX -> new LineHeight(LineHeight.Kind.PX, l.px());
            case PCT -> new LineHeight(LineHeight.Kind.FACTOR, l.pct() / 100.0);
            case CALC -> new LineHeight(LineHeight.Kind.PX, l.px() + em * l.pct() / 100.0);
            case AUTO -> null;
        };
    }

    /** {@code left} / {@code right} are the physical aliases of start / end; null = normal or invalid */
    static Align align(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "start", "left", "self-start" -> Align.START;
            case "end", "right", "self-end" -> Align.END;
            case "flex-start" -> Align.FLEX_START;
            case "flex-end" -> Align.FLEX_END;
            case "center", "safe center", "unsafe center" -> Align.CENTER;
            case "baseline", "first baseline", "last baseline" -> Align.BASELINE;
            case "stretch" -> Align.STRETCH;
            case "space-between" -> Align.SPACE_BETWEEN;
            case "space-around" -> Align.SPACE_AROUND;
            case "space-evenly" -> Align.SPACE_EVENLY;
            default -> null;
        };
    }

    private static Background bgMut(Style s) {
        s.bg = s.bg == null ? new Background() : s.bg.copy();
        return s.bg;
    }

    private static BoxArea boxArea(String v) {
        return switch (v) {
            case "border-box" -> BoxArea.BORDER;
            case "padding-box" -> BoxArea.PADDING;
            case "content-box" -> BoxArea.CONTENT;
            default -> null;
        };
    }

    private static int side(String prop) {
        String[] sides = {"top", "right", "bottom", "left"};
        for (int i = 0; i < 4; i++) if (prop.contains(sides[i])) return i;
        return 0;
    }

    private static void set(Len[] slot, int i, Len v) {
        if (v != null) slot[i] = v;
    }

    private static FlexDirection flexDirection(String v) {
        return switch (v) {
            case "row" -> FlexDirection.ROW;
            case "row-reverse" -> FlexDirection.ROW_REVERSE;
            case "column" -> FlexDirection.COLUMN;
            case "column-reverse" -> FlexDirection.COLUMN_REVERSE;
            default -> null;
        };
    }

    private static FlexWrap flexWrap(String v) {
        return switch (v) {
            case "nowrap" -> FlexWrap.NO_WRAP;
            case "wrap" -> FlexWrap.WRAP;
            case "wrap-reverse" -> FlexWrap.WRAP_REVERSE;
            default -> null;
        };
    }

    private static Double parseF(String v) {
        return Values.number(v);
    }

    static void apply(Style s, Style parent, String prop, String v, double em, double rem) {
        String lv = v.trim().toLowerCase(Locale.ROOT);
        if (lv.equals("inherit")) {
            applyInherit(s, parent, prop);
            return;
        }
        switch (prop) {
            case "display" -> {
                s.inlineBlock = lv.equals("inline-block") || lv.equals("inline-flex") || lv.equals("inline-grid") || lv.equals("inline-table");
                s.display = switch (lv) {
                    case "block", "flow-root", "inline-block" -> Display.BLOCK;
                    case "inline" -> Display.INLINE;
                    case "flex", "inline-flex" -> Display.FLEX;
                    case "grid", "inline-grid" -> Display.GRID;
                    case "list-item" -> Display.LIST_ITEM;
                    case "table", "inline-table" -> Display.TABLE;
                    case "table-row-group", "table-header-group", "table-footer-group" -> Display.TABLE_ROW_GROUP;
                    case "table-row" -> Display.TABLE_ROW;
                    case "table-cell" -> Display.TABLE_CELL;
                    case "table-caption" -> Display.TABLE_CAPTION;
                    case "contents" -> Display.CONTENTS;
                    case "none" -> Display.NONE;
                    default -> s.display;
                };
            }
            case "position" -> s.positionAbsolute = lv.equals("absolute") || lv.equals("fixed");
            case "float" -> s.floating = switch (lv) {
                case "left" -> Float.LEFT;
                case "right" -> Float.RIGHT;
                default -> Float.NONE;
            };
            case "clear" -> s.clear = switch (lv) {
                case "left" -> Clear.LEFT;
                case "right" -> Clear.RIGHT;
                case "both" -> Clear.BOTH;
                default -> Clear.NONE;
            };
            case "box-sizing" -> s.borderBox = lv.equals("border-box");
            case "width" -> set(s.size, 0, Values.len(v, em, rem));
            case "height" -> set(s.size, 1, Values.len(v, em, rem));
            case "min-width" -> set(s.minSize, 0, Values.len(v, em, rem));
            case "min-height" -> set(s.minSize, 1, Values.len(v, em, rem));
            case "max-width" -> set(s.maxSize, 0, lv.equals("none") ? Len.AUTO : Values.len(v, em, rem));
            case "max-height" -> set(s.maxSize, 1, lv.equals("none") ? Len.AUTO : Values.len(v, em, rem));
            case "margin" -> {
                Len[] m = fourLens(v, em, rem);
                if (m != null) s.margin = m;
            }
            case "padding" -> {
                Len[] m = fourLens(v, em, rem);
                if (m != null) s.padding = m;
            }
            case "inset" -> {
                Len[] m = fourLens(v, em, rem);
                if (m != null) s.inset = m;
            }
            case "margin-top", "margin-right", "margin-bottom", "margin-left" -> set(s.margin, side(prop), Values.len(v, em, rem));
            case "padding-top", "padding-right", "padding-bottom", "padding-left" -> set(s.padding, side(prop), Values.len(v, em, rem));
            case "top", "right", "bottom", "left" -> set(s.inset, side(prop), Values.len(v, em, rem));
            case "border" -> {
                Object[] b = borderShorthand(v, em, rem);
                Arrays.fill(s.borderWidth, (double) b[0]);
                Arrays.fill(s.borderStyle, (BorderStyle) b[1]);
                Arrays.fill(s.borderColor, (Rgba) b[2]);
            }
            case "border-top", "border-right", "border-bottom", "border-left" -> {
                int i = side(prop);
                Object[] b = borderShorthand(v, em, rem);
                s.borderWidth[i] = (double) b[0];
                s.borderStyle[i] = (BorderStyle) b[1];
                s.borderColor[i] = (Rgba) b[2];
            }
            case "border-width" -> {
                List<Double> w = new ArrayList<>();
                for (String p : Values.splitValues(v)) {
                    Double x = Values.borderWidth(p, em, rem);
                    if (x == null) return;
                    w.add(x);
                }
                Double[] out = four(w, new Double[4]);
                if (out != null) for (int i = 0; i < 4; i++) s.borderWidth[i] = out[i];
            }
            case "border-style" -> {
                List<BorderStyle> st = new ArrayList<>();
                for (String p : Values.splitValues(v)) {
                    BorderStyle b = Values.borderStyle(p);
                    if (b == null) return;
                    st.add(b);
                }
                BorderStyle[] out = four(st, new BorderStyle[4]);
                if (out != null) s.borderStyle = out;
            }
            case "border-color" -> {
                List<Rgba> cs = new ArrayList<>();
                for (String p : Values.splitValues(v)) {
                    Rgba c = Values.cssColor(p, s.color);
                    if (c == null) return;
                    cs.add(c);
                }
                Rgba[] out = four(cs, new Rgba[4]);
                if (out != null) s.borderColor = out;
            }
            case "border-radius" -> {
                // `a b c d / e f g h`: elliptical radii unsupported, horizontal values used
                // split on `/` outside parentheses so calc(x / y) is not broken
                Len[] r = fourLens(Css.splitTopLevel(v, '/').get(0), em, rem);
                if (r != null) s.radius = r;
            }
            case "border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius" -> {
                int i = List.of("border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius").indexOf(prop);
                List<String> words = Values.splitValues(v);
                if (!words.isEmpty()) set(s.radius, i, Values.len(words.get(0), em, rem));
            }
            case "border-collapse" -> s.borderCollapse = lv.equals("collapse");
            case "border-spacing" -> {
                List<String> words = Values.splitValues(v);
                if (!words.isEmpty()) {
                    Len l = Values.len(words.get(0), em, rem);
                    if (l != null && l.isPx()) s.borderSpacing = l.px();
                }
            }
            case "background-color" -> {
                Rgba c = Values.cssColor(v, s.color);
                s.background = c != null && c.a() > 0 ? c : null;
            }
            case "background" -> applyBackground(s, v, em, rem);
            case "background-image" -> {
                List<BgSource> images = new ArrayList<>();
                for (String l : Css.splitTopLevel(v, ',')) {
                    String t = l.trim();
                    if (t.toLowerCase(Locale.ROOT).contains("gradient(")) {
                        Gradient g = Values.gradient(t, em, rem, s.color);
                        if (g != null) images.add(new BgSource(null, g));
                    } else {
                        String u = Values.url(t);
                        if (u != null) images.add(new BgSource(u, null));
                    }
                }
                bgMut(s).images = images;
            }
            case "background-size" -> {
                List<BgSize> sizes = new ArrayList<>();
                for (String l : Css.splitTopLevel(v, ',')) {
                    BgSize b = Values.bgSize(l, em, rem);
                    if (b != null) sizes.add(b);
                }
                bgMut(s).sizes = sizes;
            }
            case "background-position" -> {
                List<Len[]> pos = new ArrayList<>();
                for (String l : Css.splitTopLevel(v, ',')) pos.add(Values.position(l, em, rem));
                bgMut(s).positions = pos;
            }
            case "background-repeat" -> {
                List<boolean[]> reps = new ArrayList<>();
                for (String l : Css.splitTopLevel(lv, ',')) {
                    String[] w = l.trim().split("\\s+");
                    if (w.length == 1) {
                        boolean[] r = Values.repeat(w[0]);
                        if (r != null) reps.add(r);
                    } else if (w.length == 2) {
                        reps.add(new boolean[] {!w[0].equals("no-repeat"), !w[1].equals("no-repeat")});
                    }
                }
                bgMut(s).repeats = reps;
            }
            case "background-origin" -> {
                List<BoxArea> o = new ArrayList<>();
                for (String l : Css.splitTopLevel(lv, ',')) {
                    BoxArea b = boxArea(l.trim());
                    if (b != null) o.add(b);
                }
                bgMut(s).origins = o;
            }
            case "background-clip", "-webkit-background-clip" -> {
                List<BoxArea> o = new ArrayList<>();
                for (String l : Css.splitTopLevel(lv, ',')) {
                    BoxArea b = l.trim().equals("text") ? BoxArea.TEXT : boxArea(l.trim());
                    if (b != null) o.add(b);
                }
                bgMut(s).clips = o;
            }
            case "background-attachment" -> {
                List<Boolean> f = new ArrayList<>();
                for (String l : Css.splitTopLevel(lv, ',')) f.add(l.trim().equals("fixed"));
                bgMut(s).fixed = f;
            }
            // the text color, over `color` (gradient text: `-webkit-text-fill-color: transparent`)
            case "-webkit-text-fill-color" -> {
                Rgba c = Values.cssColor(v, s.color);
                if (c != null) s.color = c;
            }
            case "counter-reset" -> s.counterReset = Values.counterList(lv, 0);
            case "counter-increment" -> s.counterIncrement = Values.counterList(lv, 1);
            case "counter-set" -> s.counterSet = Values.counterList(lv, 0);
            case "page" -> s.page = lv.equals("auto") ? null : v.trim();
            case "box-shadow" -> s.shadows = lv.equals("none") ? null : Values.shadows(v, em, rem, s.color);
            case "direction" -> s.rtl = lv.equals("rtl");
            case "content" -> s.content = v.trim();
            case "transform" -> s.transformFn = lv.equals("none") ? null : v.trim();
            case "transform-origin" -> s.transformOrigin = v.trim();
            case "opacity" -> {
                boolean pct = lv.endsWith("%");
                Double o = parseF(pct ? lv.substring(0, lv.length() - 1) : lv);
                if (o != null) s.opacity = Math.max(0, Math.min(1, pct ? o / 100.0 : o));
            }
            case "overflow" -> s.overflowHidden = lv.equals("hidden") || lv.equals("clip") || lv.equals("auto") || lv.equals("scroll");
            case "color" -> {
                Rgba c = Colors.parse(v);
                if (c != null) s.color = c;
            }
            case "font-family" -> {
                // first usable family of the stack, like a browser; none -> inherited
                String family = Fonts.resolveStack(v);
                if (family != null) s.fontFamily = family;
            }
            case "font-weight" -> {
                Double w = Values.fontWeight(lv, parent.fontWeight);
                if (w != null) s.fontWeight = w;
            }
            case "font-style" -> s.italic = lv.equals("italic") || lv.startsWith("oblique");
            case "text-align" -> s.textAlign = switch (lv) {
                case "left" -> TextAlign.LEFT;
                case "right", "end" -> TextAlign.RIGHT;
                case "center" -> TextAlign.CENTER;
                case "justify" -> TextAlign.JUSTIFY;
                default -> TextAlign.START;
            };
            case "line-height" -> {
                LineHeight lh = lineHeight(v, em, rem);
                if (lh != null) s.lineHeight = lh;
            }
            case "letter-spacing", "word-spacing" -> {
                Double px;
                if (lv.equals("normal")) px = 0.0;
                else {
                    Len l = Values.len(v, em, rem);
                    px = l == null ? null : l.pxValue();
                }
                if (px != null) {
                    if (prop.equals("letter-spacing")) s.letterSpacing = px;
                    else s.wordSpacing = px;
                }
            }
            case "text-transform" -> s.transform = switch (lv) {
                case "uppercase" -> TextTransform.UPPER;
                case "lowercase" -> TextTransform.LOWER;
                case "capitalize" -> TextTransform.CAPITALIZE;
                default -> TextTransform.NONE;
            };
            case "white-space" -> s.whiteSpace = switch (lv) {
                case "nowrap" -> WhiteSpace.NOWRAP;
                case "pre" -> WhiteSpace.PRE;
                case "pre-wrap", "break-spaces" -> WhiteSpace.PRE_WRAP;
                case "pre-line" -> WhiteSpace.PRE_LINE;
                default -> WhiteSpace.NORMAL;
            };
            case "text-decoration", "text-decoration-line" -> {
                if (lv.contains("none")) {
                    s.underline = false;
                    s.strike = false;
                }
                if (lv.contains("underline")) s.underline = true;
                if (lv.contains("line-through")) s.strike = true;
            }
            case "list-style", "list-style-type" -> {
                // a string marker (`"→ "`) keeps its spaces and case
                int q = indexOfQuote(v);
                if (q >= 0) {
                    int end = v.indexOf(v.charAt(q), q + 1);
                    if (end >= 0) s.listStyle = v.substring(q, end + 1);
                } else {
                    for (String p : Values.splitValues(lv)) {
                        if (!p.equals("inside") && !p.equals("outside") && !p.startsWith("url(")) {
                            s.listStyle = p;
                            break;
                        }
                    }
                }
            }
            case "flex" -> {
                // none | auto | <grow> [<shrink>] [<basis>] | <basis>
                List<String> parts = Values.splitValues(lv);
                if (parts.size() == 1 && parts.get(0).equals("none")) {
                    s.flexGrow = 0;
                    s.flexShrink = 0;
                    s.flexBasis = Len.AUTO;
                } else if (parts.size() == 1 && parts.get(0).equals("auto")) {
                    s.flexGrow = 1;
                    s.flexShrink = 1;
                    s.flexBasis = Len.AUTO;
                } else if (parts.size() == 1 && parts.get(0).equals("initial")) {
                    s.flexGrow = 0;
                    s.flexShrink = 1;
                    s.flexBasis = Len.AUTO;
                } else {
                    List<Double> nums = new ArrayList<>();
                    for (String p : parts) {
                        Double n = parseF(p);
                        if (n == null) break;
                        nums.add(n);
                    }
                    Len basis = nums.size() < parts.size() ? Values.len(parts.get(nums.size()), em, rem) : null;
                    s.flexGrow = !nums.isEmpty() ? nums.get(0) : basis != null ? 1 : 0;
                    s.flexShrink = nums.size() > 1 ? nums.get(1) : 1;
                    // a bare number means basis 0 (flex: 1 -> 1 1 0%)
                    s.flexBasis = basis != null ? basis : nums.isEmpty() ? Len.AUTO : Len.ZERO;
                }
            }
            case "flex-grow" -> {
                Double n = parseF(lv);
                if (n != null) s.flexGrow = n;
            }
            case "flex-shrink" -> {
                Double n = parseF(lv);
                if (n != null) s.flexShrink = n;
            }
            case "flex-basis" -> {
                Len l = lv.equals("content") ? Len.AUTO : Values.len(v, em, rem);
                if (l != null) s.flexBasis = l;
            }
            case "flex-direction" -> {
                FlexDirection d = flexDirection(lv);
                if (d != null) s.flexDirection = d;
            }
            case "flex-wrap" -> {
                FlexWrap w = flexWrap(lv);
                if (w != null) s.flexWrap = w;
            }
            case "flex-flow" -> {
                for (String p : lv.split("\\s+")) {
                    FlexDirection d = flexDirection(p);
                    FlexWrap w = flexWrap(p);
                    if (d != null) s.flexDirection = d;
                    else if (w != null) s.flexWrap = w;
                }
            }
            case "justify-content" -> s.justifyContent = align(v);
            case "align-content" -> s.alignContent = align(v);
            case "align-items" -> s.alignItems = align(v);
            case "align-self" -> s.alignSelf = lv.equals("auto") ? null : align(v);
            case "justify-items" -> s.justifyItems = align(v);
            case "justify-self" -> s.justifySelf = lv.equals("auto") ? null : align(v);
            case "place-items" -> {
                List<String> p = Values.splitValues(v);
                s.alignItems = p.isEmpty() ? null : align(p.get(0));
                s.justifyItems = p.size() > 1 ? align(p.get(1)) : p.isEmpty() ? null : align(p.get(0));
            }
            case "place-content" -> {
                List<String> p = Values.splitValues(v);
                s.alignContent = p.isEmpty() ? null : align(p.get(0));
                s.justifyContent = p.size() > 1 ? align(p.get(1)) : p.isEmpty() ? null : align(p.get(0));
            }
            case "place-self" -> {
                List<String> p = Values.splitValues(v);
                s.alignSelf = p.isEmpty() ? null : align(p.get(0));
                s.justifySelf = p.size() > 1 ? align(p.get(1)) : p.isEmpty() ? null : align(p.get(0));
            }
            case "gap", "grid-gap" -> {
                if (lv.equals("normal")) {
                    s.gap = new Len[] {Len.ZERO, Len.ZERO};
                } else {
                    List<Len> g = lens(v, em, rem);
                    if (g != null && g.size() == 1) s.gap = new Len[] {g.get(0), g.get(0)};
                    else if (g != null && g.size() == 2) s.gap = new Len[] {g.get(0), g.get(1)};
                }
            }
            case "row-gap", "grid-row-gap" -> set(s.gap, 0, lv.equals("normal") ? Len.ZERO : Values.len(v, em, rem));
            case "column-gap", "grid-column-gap" -> set(s.gap, 1, lv.equals("normal") ? Len.ZERO : Values.len(v, em, rem));
            case "order" -> {
                try {
                    s.order = Integer.parseInt(lv);
                } catch (NumberFormatException e) {
                    s.order = 0;
                }
            }
            case "grid-template-columns" -> s.gridTemplateColumns = GridTemplate.parse(v, em, rem);
            case "grid-template-rows" -> s.gridTemplateRows = GridTemplate.parse(v, em, rem);
            case "grid-auto-rows" -> s.gridAutoRows = GridTemplate.parse(v, em, rem);
            case "grid-auto-columns" -> s.gridAutoColumns = GridTemplate.parse(v, em, rem);
            case "grid-auto-flow" -> s.gridAutoFlow = lv;
            case "grid-column" -> s.gridColumn = GridTemplate.placement(lv);
            case "grid-row" -> s.gridRow = GridTemplate.placement(lv);
            case "grid-column-start", "grid-column-end", "grid-row-start", "grid-row-end" -> {
                boolean col = prop.startsWith("grid-column");
                GridTemplate.Placement cur = col ? s.gridColumn : s.gridRow;
                GridTemplate.Placement one = GridTemplate.placement(lv);
                if (one == null) return;
                if (cur == null) cur = GridTemplate.Placement.AUTO;
                GridTemplate.Placement p = prop.endsWith("start") ? new GridTemplate.Placement(one.start(), cur.end()) : new GridTemplate.Placement(cur.start(), one.start());
                if (col) s.gridColumn = p;
                else s.gridRow = p;
            }
            case "aspect-ratio" -> {
                String[] r = lv.split("/");
                Double a = parseF(r[0].trim());
                Double b = r.length > 1 ? parseF(r[1].trim()) : Double.valueOf(1.0);
                s.aspectRatio = r.length <= 2 && a != null && b != null && b != 0 ? a / b : null;
            }
            case "vertical-align" -> s.verticalAlign = switch (lv) {
                case "top" -> VAlign.of(VAlign.Kind.TOP);
                case "middle" -> VAlign.of(VAlign.Kind.MIDDLE);
                case "bottom" -> VAlign.of(VAlign.Kind.BOTTOM);
                case "sub" -> VAlign.of(VAlign.Kind.SUB);
                case "super" -> VAlign.of(VAlign.Kind.SUPER);
                case "text-top" -> VAlign.of(VAlign.Kind.TEXT_TOP);
                case "text-bottom" -> VAlign.of(VAlign.Kind.TEXT_BOTTOM);
                default -> {
                    Len l = Values.len(lv, em, rem);
                    if (l != null && l.isPx()) yield new VAlign(VAlign.Kind.LENGTH, l.px());
                    if (l != null && l.isPct()) yield new VAlign(VAlign.Kind.LENGTH, s.lineHeightPx() * l.pct() / 100.0);
                    yield VAlign.BASELINE;
                }
            };
            case "object-fit" -> s.objectFit = switch (lv) {
                case "contain", "scale-down" -> ObjectFit.CONTAIN;
                case "cover" -> ObjectFit.COVER;
                case "none" -> ObjectFit.NONE;
                default -> ObjectFit.FILL;
            };
            case "page-break-before", "break-before" -> s.breakBefore = List.of("always", "page", "left", "right").contains(lv);
            case "page-break-after", "break-after" -> s.breakAfter = List.of("always", "page", "left", "right").contains(lv);
            case "page-break-inside", "break-inside" -> s.breakInsideAvoid = lv.startsWith("avoid");
            default -> {}
        }
    }

    private static int indexOfQuote(String v) {
        for (int i = 0; i < v.length(); i++) if (v.charAt(i) == '"' || v.charAt(i) == '\'') return i;
        return -1;
    }

    /** layers: image (url / gradient), position / size, repeat, origin / clip; the color in the last one */
    private static void applyBackground(Style s, String v, double em, double rem) {
        s.background = null;
        Background bg = new Background();
        for (String layer : Css.splitTopLevel(v, ',')) {
            String[] ps = Values.splitSlash(layer);
            // after the `/`: one or two size words, then the layer goes on (`/ cover no-repeat`)
            List<String> sizeWords = new ArrayList<>(), rest = new ArrayList<>();
            if (ps[1] != null) {
                for (String w : Values.splitValues(ps[1])) {
                    String lw = w.toLowerCase(Locale.ROOT);
                    boolean isSize = lw.equals("cover") || lw.equals("contain") || lw.equals("auto") || Values.len(lw, em, rem) != null;
                    boolean firstFinal = !sizeWords.isEmpty() && (sizeWords.get(0).equals("cover") || sizeWords.get(0).equals("contain"));
                    if (isSize && rest.isEmpty() && sizeWords.size() < 2 && !firstFinal) sizeWords.add(lw);
                    else rest.add(w);
                }
            }
            List<String> posWords = new ArrayList<>();
            BgSource image = null;
            boolean[] rep = {true, true};
            List<BoxArea> boxes = new ArrayList<>();
            boolean fixed = false;
            List<String> words = new ArrayList<>(Values.splitValues(ps[0]));
            words.addAll(rest);
            for (String word : words) {
                String lw = word.toLowerCase(Locale.ROOT);
                boolean[] r;
                BoxArea b;
                if (lw.contains("gradient(")) {
                    Gradient g = Values.gradient(word, em, rem, s.color);
                    image = g == null ? null : new BgSource(null, g);
                } else if (lw.startsWith("url(")) {
                    String u = Values.url(word);
                    image = u == null ? null : new BgSource(u, null);
                } else if ((r = Values.repeat(lw)) != null) {
                    rep = r;
                } else if ((b = boxArea(lw)) != null) {
                    boxes.add(b);
                } else if (lw.equals("fixed")) {
                    fixed = true;
                } else if (lw.equals("text")) {
                    boxes.add(BoxArea.TEXT);
                } else if (lw.equals("scroll") || lw.equals("local") || lw.equals("none")) {
                    // defaults
                } else {
                    Rgba c = Values.cssColor(word, s.color);
                    if (c != null) s.background = c.a() > 0 ? c : null;
                    else posWords.add(word);
                }
            }
            if (image != null) {
                bg.images.add(image);
                bg.positions.add(posWords.isEmpty() ? new Len[] {Len.pct(0), Len.pct(0)} : Values.position(String.join(" ", posWords), em, rem));
                BgSize size = sizeWords.isEmpty() ? null : Values.bgSize(String.join(" ", sizeWords), em, rem);
                bg.sizes.add(size == null ? BgSize.AUTO : size);
                bg.repeats.add(rep);
                bg.origins.add(boxes.isEmpty() ? BoxArea.PADDING : boxes.get(0));
                bg.clips.add(boxes.size() > 1 ? boxes.get(1) : boxes.isEmpty() ? BoxArea.BORDER : boxes.get(0));
                bg.fixed.add(fixed);
            }
        }
        s.bg = bg.images.isEmpty() ? null : bg;
    }

    private static void applyInherit(Style s, Style p, String prop) {
        switch (prop) {
            case "background-color", "background" -> s.background = p.background;
            case "border" -> {
                s.borderWidth = p.borderWidth.clone();
                s.borderStyle = p.borderStyle.clone();
                s.borderColor = p.borderColor.clone();
            }
            case "width" -> s.size[0] = p.size[0];
            case "height" -> s.size[1] = p.size[1];
            case "margin" -> s.margin = p.margin.clone();
            case "padding" -> s.padding = p.padding.clone();
            case "display" -> s.display = p.display;
            default -> {} // inherited properties already are
        }
    }
}
