package io.github.gouz.pdfj.style;

import io.github.gouz.pdfj.css.Css;
import io.github.gouz.pdfj.style.Style.BgSize;
import io.github.gouz.pdfj.style.Style.BorderStyle;
import io.github.gouz.pdfj.style.Style.Gradient;
import io.github.gouz.pdfj.style.Style.GradientKind;
import io.github.gouz.pdfj.style.Style.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** CSS value parsing: lengths, calc(), var(), gradients, positions, transforms, generated content. */
public final class Values {
    private Values() {}

    /** The page area (pt) that {@code vw} / {@code vh} / {@code vmin} / {@code vmax} refer to. */
    private static final ThreadLocal<double[]> VIEWPORT = ThreadLocal.withInitial(() -> new double[] {515.28, 761.89});

    /** Set the page area of the document being laid out (for viewport units). */
    public static void setViewport(double width, double height) {
        VIEWPORT.set(new double[] {width, height});
    }

    private static Double parseNumber(String s) {
        if (s.isEmpty()) return null;
        // Rust's f32 parser: digits, sign, dot, exponent; no hex, no "Infinity"
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E')) return null;
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A plain number ({@code 1.5}), or null. */
    public static Double number(String s) {
        return parseNumber(s.trim());
    }

    /** {@code 12px}, {@code 1.5em}, {@code 50%}, {@code auto}, {@code 0} -> Len (em relative to {@code em}, rem to {@code rem}) */
    public static Len len(String value, double em, double rem) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("auto")) return Len.AUTO;
        for (String p : new String[] {"calc(", "-webkit-calc("}) {
            if (v.startsWith(p) && v.endsWith(")")) {
                double[] r = Calc.eval(v.substring(p.length(), v.length() - 1), em, rem);
                if (r == null) return null;
                if (r[1] == 0) return Len.px(r[0]);
                if (r[0] == 0) return Len.pct(r[1]);
                return Len.calc(r[0], r[1]);
            }
        }
        for (String func : new String[] {"min(", "max(", "clamp("}) {
            if (!v.startsWith(func)) continue;
            // px-only arguments are evaluated; anything relative falls back to the first argument
            if (!v.endsWith(")")) return null;
            String inner = v.substring(func.length(), v.length() - 1);
            List<double[]> args = new ArrayList<>();
            for (String a : Css.splitTopLevel(inner, ',')) {
                double[] r = Calc.eval(a, em, rem);
                if (r == null) return null;
                args.add(r);
            }
            if (args.stream().anyMatch(a -> a[1] != 0)) {
                double[] a = args.get(0);
                return a[1] == 0 ? Len.px(a[0]) : a[0] == 0 ? Len.pct(a[1]) : Len.calc(a[0], a[1]);
            }
            switch (func) {
                case "min(" -> {
                    double m = Double.POSITIVE_INFINITY;
                    for (double[] a : args) m = Math.min(m, a[0]);
                    return Len.px(m);
                }
                case "max(" -> {
                    double m = Double.NEGATIVE_INFINITY;
                    for (double[] a : args) m = Math.max(m, a[0]);
                    return Len.px(m);
                }
                default -> {
                    if (args.size() != 3) return null;
                    double lo = args.get(0)[0], val = args.get(1)[0], hi = Math.max(args.get(2)[0], lo);
                    return Len.px(Math.max(lo, Math.min(hi, val)));
                }
            }
        }
        int split = 0;
        while (split < v.length()) {
            char c = v.charAt(split);
            if (!(Character.isDigit(c) || c == '.' || c == '-' || c == '+')) break;
            split++;
        }
        // an exponent (1e30px) belongs to the number
        if (split < v.length() && (v.charAt(split) == 'e' || v.charAt(split) == 'E') && split + 1 < v.length()
                && (Character.isDigit(v.charAt(split + 1)) || v.charAt(split + 1) == '-' || v.charAt(split + 1) == '+') && !v.startsWith("em", split) && !v.startsWith("ex", split)) {
            split++;
            while (split < v.length() && (Character.isDigit(v.charAt(split)) || v.charAt(split) == '-' || v.charAt(split) == '+')) split++;
        }
        Double n = parseNumber(v.substring(0, split));
        if (n == null) return null;
        String unit = v.substring(split).trim();
        double px;
        switch (unit) {
            case "" -> px = n; // unitless: lenient, like react-pdf (0 included)
            case "px" -> px = n * Style.PX;
            case "pt" -> px = n;
            case "%" -> {
                return Len.pct(n);
            }
            case "em" -> px = n * em;
            case "rem" -> px = n * rem;
            case "pc" -> px = n * 12.0;
            case "in" -> px = n * 72.0;
            case "cm" -> px = n * 72.0 / 2.54;
            case "mm" -> px = n * 72.0 / 25.4;
            case "q" -> px = n * 72.0 / 101.6;
            case "ex", "ch" -> px = n * em * 0.5;
            case "vw", "vh", "vmin", "vmax" -> {
                double[] vp = VIEWPORT.get();
                double basis = switch (unit) {
                    case "vw" -> vp[0];
                    case "vh" -> vp[1];
                    case "vmin" -> Math.min(vp[0], vp[1]);
                    default -> Math.max(vp[0], vp[1]);
                };
                px = n / 100.0 * basis;
            }
            default -> {
                return null;
            }
        }
        return Len.px(px);
    }

    /** {@code calc()} arithmetic on lengths: the result is {@code px + pct%}. */
    static final class Calc {
        private record Tok(int kind, String val, char op) {
            static final int VAL = 0, OP = 1, OPEN = 2, CLOSE = 3;
        }

        static double[] eval(String expr, double em, double rem) {
            List<Tok> tokens = tokenize(expr);
            if (tokens == null) return null;
            int[] pos = {0};
            double[] v = sum(tokens, pos, em, rem);
            return v != null && pos[0] == tokens.size() ? v : null;
        }

        private static List<Tok> tokenize(String s) {
            List<Tok> out = new ArrayList<>();
            int i = 0, n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(') {
                    out.add(new Tok(Tok.OPEN, null, ' '));
                    i++;
                } else if (c == ')') {
                    out.add(new Tok(Tok.CLOSE, null, ' '));
                    i++;
                } else if (c == '*' || c == '/' || ((c == '+' || c == '-') && i + 1 < n && Character.isWhitespace(s.charAt(i + 1)))) {
                    out.add(new Tok(Tok.OP, null, c));
                    i++;
                } else if (s.startsWith("calc(", i)) {
                    out.add(new Tok(Tok.OPEN, null, ' '));
                    i += 5;
                } else {
                    int start = i;
                    while (i < n && !Character.isWhitespace(s.charAt(i)) && "()*/".indexOf(s.charAt(i)) < 0) i++;
                    out.add(new Tok(Tok.VAL, s.substring(start, i), ' '));
                }
            }
            return out;
        }

        private static double[] sum(List<Tok> t, int[] pos, double em, double rem) {
            double[] acc = product(t, pos, em, rem);
            if (acc == null) return null;
            while (pos[0] < t.size() && t.get(pos[0]).kind == Tok.OP && (t.get(pos[0]).op == '+' || t.get(pos[0]).op == '-')) {
                char op = t.get(pos[0]).op;
                pos[0]++;
                double[] r = product(t, pos, em, rem);
                if (r == null) return null;
                acc = op == '+' ? new double[] {acc[0] + r[0], acc[1] + r[1]} : new double[] {acc[0] - r[0], acc[1] - r[1]};
            }
            return acc;
        }

        /** (px, pct, plain number ? 1 : 0) */
        private static double[] product(List<Tok> t, int[] pos, double em, double rem) {
            double[] a = atom(t, pos, em, rem);
            if (a == null) return null;
            double[] acc = {a[0], a[1]};
            boolean plain = a[2] == 1;
            while (pos[0] < t.size() && t.get(pos[0]).kind == Tok.OP && (t.get(pos[0]).op == '*' || t.get(pos[0]).op == '/')) {
                char op = t.get(pos[0]).op;
                pos[0]++;
                double[] r = atom(t, pos, em, rem);
                if (r == null) return null;
                boolean rPlain = r[2] == 1;
                if (op == '*') {
                    // one side must be a plain number
                    if (plain) {
                        acc = new double[] {r[0] * acc[0], r[1] * acc[0]};
                        plain = rPlain;
                    } else if (rPlain) {
                        acc = new double[] {acc[0] * r[0], acc[1] * r[0]};
                    } else {
                        return null;
                    }
                } else {
                    if (!rPlain || r[0] == 0) return null;
                    acc = new double[] {acc[0] / r[0], acc[1] / r[0]};
                }
            }
            return new double[] {acc[0], acc[1]};
        }

        private static double[] atom(List<Tok> t, int[] pos, double em, double rem) {
            if (pos[0] >= t.size()) return null;
            Tok tok = t.get(pos[0]);
            if (tok.kind == Tok.OPEN) {
                pos[0]++;
                double[] v = sum(t, pos, em, rem);
                if (v == null || pos[0] >= t.size() || t.get(pos[0]).kind != Tok.CLOSE) return null;
                pos[0]++;
                return new double[] {v[0], v[1], 0};
            }
            if (tok.kind == Tok.VAL) {
                pos[0]++;
                Double n = parseNumber(tok.val);
                if (n != null) return new double[] {n, 0, 1};
                Len l = len(tok.val, em, rem);
                if (l == null) return null;
                return switch (l.kind()) {
                    case PX -> new double[] {l.px(), 0, 0};
                    case PCT -> new double[] {0, l.pct(), 0};
                    default -> null;
                };
            }
            return null;
        }
    }

    /**
     * {@code var(--name)} / {@code var(--name, fallback)}, recursively (custom properties may use var() too).
     * null when a variable is undefined and has no fallback.
     */
    public static String substituteVars(String v, Map<String, String> vars, int depth) {
        int start = v.indexOf("var(");
        if (start < 0) return v;
        if (depth > 16) return null; // cycle
        // matching parenthesis
        int d = 0, end = -1;
        for (int i = start + 3; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) return null;
        String inner = v.substring(start + 4, end);
        int comma = inner.indexOf(',');
        String name = (comma < 0 ? inner : inner.substring(0, comma)).trim();
        String fallback = comma < 0 ? null : inner.substring(comma + 1).trim();
        String value;
        String val = vars == null ? null : vars.get(name);
        if (val != null) {
            value = substituteVars(val, vars, depth + 1);
        } else {
            if (fallback == null) return null;
            value = substituteVars(fallback, vars, depth + 1);
        }
        if (value == null) return null;
        String rest = substituteVars(v.substring(end + 1), vars, depth + 1);
        if (rest == null) return null;
        return v.substring(0, start) + value + rest;
    }

    static Double angle(String value) {
        String v = value.trim();
        int unit = 0;
        while (unit < v.length() && !Character.isLetter(v.charAt(unit))) unit++;
        Double n = parseNumber(v.substring(0, unit));
        if (n == null) return null;
        return switch (v.substring(unit)) {
            case "deg" -> n;
            case "rad" -> Math.toDegrees(n);
            case "turn" -> n * 360.0;
            case "grad" -> n * 0.9;
            case "" -> n == 0 ? 0.0 : null;
            default -> null;
        };
    }

    private static final java.util.Set<String> SHAPE_WORDS = java.util.Set.of(
        "circle", "ellipse", "at", "closest-side", "closest-corner", "farthest-side", "farthest-corner", "center", "left", "right", "top", "bottom");

    /** {@code linear-gradient(...)}, {@code radial-gradient(...)}, {@code repeating-*} */
    public static Gradient gradient(String value, double em, double rem, Rgba current) {
        String lv = value.trim().toLowerCase(Locale.ROOT);
        boolean repeating = lv.startsWith("repeating-");
        String rest = repeating ? lv.substring("repeating-".length()) : lv;
        boolean radial;
        String args;
        if (rest.startsWith("linear-gradient(")) {
            radial = false;
            args = rest.substring("linear-gradient(".length());
        } else if (rest.startsWith("radial-gradient(")) {
            radial = true;
            args = rest.substring("radial-gradient(".length());
        } else {
            return null;
        }
        if (!args.endsWith(")")) return null;
        args = args.substring(0, args.length() - 1);
        List<String> parts = new ArrayList<>();
        for (String p : Css.splitTopLevel(args, ',')) parts.add(p.trim());
        if (parts.isEmpty()) return null;
        String first = parts.get(0);
        GradientKind kind;
        if (radial) {
            boolean shapeLike = Colors.parse(first) == null;
            for (String w : splitValues(first)) shapeLike &= SHAPE_WORDS.contains(w) || len(w, em, rem) != null;
            Len[] at = {Len.pct(50), Len.pct(50)};
            boolean circle = false;
            if (shapeLike) {
                circle = first.contains("circle");
                int i = first.indexOf("at ");
                if (i >= 0) at = position(first.substring(i + 3), em, rem);
                parts.remove(0);
            }
            kind = new GradientKind.Radial(circle, at[0], at[1]);
        } else if (first.startsWith("to ")) {
            parts.remove(0);
            int x = 0, y = 0;
            for (String w : first.substring(3).trim().split("\\s+")) {
                switch (w) {
                    case "left" -> x = -1;
                    case "right" -> x = 1;
                    case "top" -> y = -1;
                    case "bottom" -> y = 1;
                    default -> {
                        return null;
                    }
                }
            }
            if (x == 0 && y == -1) kind = new GradientKind.Linear(0);
            else if (x == 1 && y == 0) kind = new GradientKind.Linear(90);
            else if (x == 0 && y == 1) kind = new GradientKind.Linear(180);
            else if (x == -1 && y == 0) kind = new GradientKind.Linear(270);
            else if (x == 0) return null;
            else kind = new GradientKind.LinearCorner(x, y);
        } else {
            Double a = angle(first);
            if (a != null) {
                parts.remove(0);
                kind = new GradientKind.Linear(a);
            } else {
                kind = new GradientKind.Linear(180);
            }
        }
        List<Style.Stop> stops = new ArrayList<>();
        for (String p : parts) {
            List<String> words = splitValues(p);
            if (words.isEmpty()) return null;
            Rgba c = cssColor(words.get(0), current);
            if (c == null) return null;
            List<Len> positions = new ArrayList<>();
            for (String w : words.subList(1, words.size())) {
                Len l = len(w, em, rem);
                if (l != null) positions.add(l);
            }
            if (positions.isEmpty()) stops.add(new Style.Stop(c, null));
            for (int i = 0; i < Math.min(2, positions.size()); i++) stops.add(new Style.Stop(c, positions.get(i)));
        }
        return stops.size() >= 2 ? new Gradient(kind, List.copyOf(stops), repeating) : null;
    }

    private static Len kw(String w) {
        return switch (w) {
            case "left", "top" -> Len.pct(0);
            case "center" -> Len.pct(50);
            case "right", "bottom" -> Len.pct(100);
            default -> null;
        };
    }

    /** {@code center}, {@code left top}, {@code 30% 10px}... -> (x, y) */
    public static Len[] position(String v, double em, double rem) {
        Len x = Len.pct(50), y = Len.pct(50);
        List<String> words = splitValues(v);
        if (words.size() >= 3) {
            // `right 10px top`, `left 5% bottom 2px`: edge keywords with offsets
            int i = 0;
            while (i < words.size()) {
                String w = words.get(i);
                Len off = null;
                if (i + 1 < words.size()) {
                    String next = words.get(i + 1);
                    if (!List.of("left", "right", "top", "bottom", "center").contains(next)) off = len(next, em, rem);
                }
                Len val;
                if ((w.equals("left") || w.equals("top")) && off != null) val = off;
                else if ((w.equals("right") || w.equals("bottom")) && off != null && off.isPx()) val = Len.calc(-off.px(), 100);
                else if ((w.equals("right") || w.equals("bottom")) && off != null && off.isPct()) val = Len.pct(100 - off.pct());
                else {
                    Len k = kw(w);
                    val = k != null ? k : Len.pct(50);
                }
                if (w.equals("top") || w.equals("bottom")) y = val;
                else if (!w.equals("center") || i == 0) x = val;
                i += off != null ? 2 : 1;
            }
            return new Len[] {x, y};
        }
        if (words.size() == 1) {
            String a = words.get(0);
            if (a.equals("top") || a.equals("bottom")) {
                y = kw(a);
            } else {
                Len k = kw(a);
                if (k == null) k = len(a, em, rem);
                if (k != null) x = k;
            }
        } else if (words.size() == 2) {
            String a = words.get(0), b = words.get(1);
            // `top left` is valid too: vertical keyword first
            if (a.equals("top") || a.equals("bottom") || b.equals("left") || b.equals("right")) {
                String t = a;
                a = b;
                b = t;
            }
            Len ka = kw(a);
            if (ka == null) ka = len(a, em, rem);
            if (ka != null) x = ka;
            Len kb = kw(b);
            if (kb == null) kb = len(b, em, rem);
            if (kb != null) y = kb;
        }
        return new Len[] {x, y};
    }

    /** {@code name [n] name2 [n]...} / {@code none} */
    static List<Style.Counter> counterList(String v, int dflt) {
        if (v.trim().equals("none")) return List.of();
        List<Style.Counter> out = new ArrayList<>();
        for (String w : v.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            Integer n = null;
            try {
                n = Integer.parseInt(w);
            } catch (NumberFormatException ignored) {
                // a name
            }
            if (n != null) {
                if (out.isEmpty()) return null;
                Style.Counter last = out.remove(out.size() - 1);
                out.add(new Style.Counter(last.name(), n));
            } else {
                out.add(new Style.Counter(w, dflt));
            }
        }
        return List.copyOf(out);
    }

    private static String alpha(int n, char base) {
        if (n < 1) return Integer.toString(n);
        StringBuilder s = new StringBuilder();
        long m = n;
        while (m > 0) {
            m -= 1;
            s.append((char) (base + (m % 26)));
            m /= 26;
        }
        return s.reverse().toString();
    }

    private static String roman(int n) {
        if (n < 1 || n >= 4000) return Integer.toString(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] r = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                s.append(r[i]);
                n -= v[i];
            }
        }
        return s.toString();
    }

    /** A counter value in a list style: {@code decimal}, {@code lower-alpha}, {@code upper-roman}, {@code disc}... */
    public static String formatCounter(int n, String style) {
        return switch (style) {
            case "none" -> "";
            case "disc" -> "•";
            case "circle" -> "◦";
            case "square" -> "▪";
            case "decimal-leading-zero" -> (n >= 0 && n < 10 ? "0" : "") + n;
            case "lower-alpha", "lower-latin" -> alpha(n, 'a');
            case "upper-alpha", "upper-latin" -> alpha(n, 'A');
            case "lower-greek" -> n >= 1 && n <= 24 ? new String(Character.toChars(0x3B1 + n - 1 + (n > 17 ? 1 : 0))) : Integer.toString(n);
            case "lower-roman" -> roman(n);
            case "upper-roman" -> roman(n).toUpperCase(Locale.ROOT);
            default -> Integer.toString(n);
        };
    }

    /** {@code url(x)}, {@code url("x")} -> x */
    public static String url(String v) {
        String t = v.trim();
        if (!(t.startsWith("url(") || t.startsWith("URL(")) || !t.endsWith(")")) return null;
        String inner = t.substring(4, t.length() - 1).trim();
        inner = trimQuotes(inner);
        return inner.isEmpty() ? null : inner;
    }

    public static String trimQuotes(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '"' || s.charAt(a) == '\'')) a++;
        while (b > a && (s.charAt(b - 1) == '"' || s.charAt(b - 1) == '\'')) b--;
        return s.substring(a, b);
    }

    /** {@code center / cover} -> ("center", "cover"), outside parentheses; [1] null without a slash */
    static String[] splitSlash(String layer) {
        int depth = 0;
        for (int i = 0; i < layer.length(); i++) {
            char c = layer.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '/' && depth == 0) return new String[] {layer.substring(0, i), layer.substring(i + 1)};
        }
        return new String[] {layer, null};
    }

    static boolean[] repeat(String v) {
        return switch (v) {
            case "repeat", "space", "round" -> new boolean[] {true, true};
            case "no-repeat" -> new boolean[] {false, false};
            case "repeat-x" -> new boolean[] {true, false};
            case "repeat-y" -> new boolean[] {false, true};
            default -> null;
        };
    }

    static BgSize bgSize(String v, double em, double rem) {
        List<String> words = splitValues(v.trim().toLowerCase(Locale.ROOT));
        if (words.size() == 1) {
            String w = words.get(0);
            switch (w) {
                case "cover" -> {
                    return BgSize.COVER;
                }
                case "contain" -> {
                    return BgSize.CONTAIN;
                }
                case "auto" -> {
                    return BgSize.AUTO;
                }
                default -> {
                    Len l = len(w, em, rem);
                    return l == null ? null : BgSize.size(l, Len.AUTO);
                }
            }
        }
        if (words.size() == 2) {
            Len a = len(words.get(0), em, rem), b = len(words.get(1), em, rem);
            return a == null || b == null ? null : BgSize.size(a, b);
        }
        return null;
    }

    /** {@code box-shadow}: comma separated {@code [inset] dx dy [blur [spread]] [color]} */
    static List<Shadow> shadows(String v, double em, double rem, Rgba current) {
        List<Shadow> out = new ArrayList<>();
        for (String layer : Css.splitTopLevel(v, ',')) {
            List<Double> lens = new ArrayList<>();
            Rgba color = current;
            boolean inset = false;
            for (String w : splitValues(layer)) {
                if (w.equalsIgnoreCase("inset")) {
                    inset = true;
                    continue;
                }
                Len l = len(w, em, rem);
                if (l != null && l.isPx()) {
                    lens.add(l.px());
                } else {
                    color = cssColor(w, current);
                    if (color == null) return null;
                }
            }
            if (lens.size() < 2) return null;
            out.add(new Shadow(lens.get(0), lens.get(1), lens.size() > 2 ? Math.max(0, lens.get(2)) : 0, lens.size() > 3 ? lens.get(3) : 0, color, inset));
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /**
     * Resolve border-radius: percentages against the box. Only circular (not elliptical) radii are
     * supported, so a percentage is of the smaller side.
     */
    public static double[] resolveRadius(Len[] r, double w, double h) {
        double basis = Math.min(w, h);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = r[i].resolve(basis);
        return out;
    }

    private static double[] mul(double[] m, double[] n) {
        return new double[] {
            m[0] * n[0] + m[2] * n[1],
            m[1] * n[0] + m[3] * n[1],
            m[0] * n[2] + m[2] * n[3],
            m[1] * n[2] + m[3] * n[3],
            m[0] * n[4] + m[2] * n[5] + m[4],
            m[1] * n[4] + m[3] * n[5] + m[5],
        };
    }

    /** {@code transform} functions -> affine matrix (a, b, c, d, e, f) for a box of w x h. null = identity / invalid. */
    public static double[] transformMatrix(String v, double w, double h, double em) {
        double[] m = {1, 0, 0, 1, 0, 0};
        String rest = v.toLowerCase(Locale.ROOT).trim();
        if (rest.equals("none")) return null;
        while (true) {
            int open = rest.indexOf('(');
            if (open < 0) break;
            String name = rest.substring(0, open).trim();
            int close = rest.indexOf(')', open);
            if (close < 0) return null;
            List<String> args = new ArrayList<>();
            for (String a : rest.substring(open + 1, close).split("[,\\s]")) if (!a.isEmpty()) args.add(a);
            rest = rest.substring(close + 1);
            double[] t;
            switch (name) {
                case "translate" -> {
                    Double x = lenArg(args, 0, w, em), y = lenArg(args, 1, h, em);
                    if (x == null) return null;
                    t = new double[] {1, 0, 0, 1, x, y == null ? 0 : y};
                }
                case "translatex" -> {
                    Double x = lenArg(args, 0, w, em);
                    if (x == null) return null;
                    t = new double[] {1, 0, 0, 1, x, 0};
                }
                case "translatey" -> {
                    Double y = lenArg(args, 0, h, em);
                    if (y == null) return null;
                    t = new double[] {1, 0, 0, 1, 0, y};
                }
                case "scale" -> {
                    Double sx = numArg(args, 0), sy = numArg(args, 1);
                    if (sx == null) return null;
                    t = new double[] {sx, 0, 0, sy == null ? sx : sy, 0, 0};
                }
                case "scalex" -> {
                    Double sx = numArg(args, 0);
                    if (sx == null) return null;
                    t = new double[] {sx, 0, 0, 1, 0, 0};
                }
                case "scaley" -> {
                    Double sy = numArg(args, 0);
                    if (sy == null) return null;
                    t = new double[] {1, 0, 0, sy, 0, 0};
                }
                case "rotate" -> {
                    Double r = angleArg(args, 0);
                    if (r == null) return null;
                    t = new double[] {Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0};
                }
                case "skew" -> {
                    Double ax = angleArg(args, 0), ay = angleArg(args, 1);
                    if (ax == null) return null;
                    t = new double[] {1, Math.tan(ay == null ? 0 : ay), Math.tan(ax), 1, 0, 0};
                }
                case "skewx" -> {
                    Double ax = angleArg(args, 0);
                    if (ax == null) return null;
                    t = new double[] {1, 0, Math.tan(ax), 1, 0, 0};
                }
                case "skewy" -> {
                    Double ay = angleArg(args, 0);
                    if (ay == null) return null;
                    t = new double[] {1, Math.tan(ay), 0, 1, 0, 0};
                }
                case "matrix" -> {
                    Double[] n = new Double[6];
                    for (int i = 0; i < 6; i++) {
                        n[i] = numArg(args, i);
                        if (n[i] == null) return null;
                    }
                    t = new double[] {n[0], n[1], n[2], n[3], n[4] * Style.PX, n[5] * Style.PX};
                }
                default -> {
                    return null;
                }
            }
            m = mul(m, t);
        }
        return m;
    }

    private static Double lenArg(List<String> args, int i, double basis, double em) {
        if (i >= args.size()) return null;
        Len l = len(args.get(i), em, em);
        return l == null ? null : l.resolve(basis);
    }

    private static Double numArg(List<String> args, int i) {
        return i < args.size() ? parseNumber(args.get(i).trim()) : null;
    }

    private static Double angleArg(List<String> args, int i) {
        if (i >= args.size()) return null;
        Double a = angle(args.get(i));
        return a == null ? null : Math.toRadians(a);
    }

    /** {@code counter(name, style)} / {@code counters(name, sep, style)}: sep is null for counter() */
    @FunctionalInterface
    public interface CounterText {
        String text(String name, String sep, String style);
    }

    /**
     * {@code content} of a pseudo-element: strings, {@code attr(x)}, quotes, counters.
     * null = no box ({@code none}, {@code normal}, invalid).
     */
    public static String content(String v, Function<String, String> attr, CounterText counter) {
        StringBuilder out = new StringBuilder();
        String s = v.trim();
        int i = 0, n = s.length();
        boolean any = false;
        while (i < n) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == '"' || c == '\'') {
                any = true;
                while (i < n) {
                    int ch = s.codePointAt(i);
                    i += Character.charCount(ch);
                    if (ch == c) break;
                    if (ch == '\\') {
                        // \201C, \a (newline), \"
                        int start = i;
                        while (i < n && i - start < 6 && Character.digit(s.charAt(i), 16) >= 0) i++;
                        if (i == start) {
                            if (i < n) {
                                int e = s.codePointAt(i);
                                i += Character.charCount(e);
                                out.appendCodePoint(e);
                            }
                        } else {
                            int cp = Integer.parseInt(s.substring(start, i), 16);
                            if (i < n && s.charAt(i) == ' ') i++;
                            if (Character.isValidCodePoint(cp) && !(cp >= 0xD800 && cp <= 0xDFFF)) out.appendCodePoint(cp);
                        }
                    } else {
                        out.appendCodePoint(ch);
                    }
                }
            } else if (Character.isWhitespace(c)) {
                // separator
            } else {
                // a keyword or function
                StringBuilder word = new StringBuilder().appendCodePoint(c);
                while (i < n) {
                    char ch = s.charAt(i);
                    if (Character.isWhitespace(ch) && word.indexOf("(") < 0 || word.charAt(word.length() - 1) == ')') break;
                    word.append(ch);
                    i++;
                }
                String raw = word.toString();
                String w = raw.toLowerCase(Locale.ROOT);
                if (w.equals("none") || w.equals("normal")) return null;
                if (w.equals("open-quote")) out.append('“');
                else if (w.equals("close-quote")) out.append('”');
                else if (w.equals("no-open-quote") || w.equals("no-close-quote")) {
                    // nothing
                } else if (w.startsWith("attr(")) {
                    String a = attr.apply(stripParen(w.substring(5)).trim());
                    if (a != null) out.append(a);
                } else if (w.startsWith("counter(") || w.startsWith("counters(")) {
                    boolean multi = w.startsWith("counters(");
                    String inner = stripParen(raw.substring(multi ? 9 : 8));
                    List<String> args = new ArrayList<>();
                    for (String a : Css.splitTopLevel(inner, ',')) args.add(a.trim());
                    String name = args.isEmpty() ? "" : args.get(0);
                    String sep = null, style;
                    if (multi) {
                        sep = args.size() > 1 ? trimQuotes(args.get(1)) : null;
                        style = args.size() > 2 ? args.get(2) : "decimal";
                    } else {
                        style = args.size() > 1 ? args.get(1) : "decimal";
                    }
                    out.append(counter.text(name, sep, style.toLowerCase(Locale.ROOT)));
                } else {
                    return null;
                }
                any = true;
            }
        }
        return any ? out.toString() : null;
    }

    private static String stripParen(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ')') e--;
        return s.substring(0, e);
    }

    static Double borderWidth(String v, double em, double rem) {
        switch (v.toLowerCase(Locale.ROOT)) {
            case "thin" -> {
                return Style.PX;
            }
            case "medium" -> {
                return 3.0 * Style.PX;
            }
            case "thick" -> {
                return 5.0 * Style.PX;
            }
            default -> {
                Len l = len(v, em, rem);
                return l == null ? null : l.pxValue();
            }
        }
    }

    static BorderStyle borderStyle(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "none", "hidden" -> BorderStyle.NONE;
            case "solid", "groove", "ridge", "inset", "outset" -> BorderStyle.SOLID;
            case "dashed" -> BorderStyle.DASHED;
            case "dotted" -> BorderStyle.DOTTED;
            case "double" -> BorderStyle.DOUBLE;
            default -> null;
        };
    }

    static Rgba cssColor(String v, Rgba current) {
        return v.equalsIgnoreCase("currentcolor") ? current : Colors.parse(v);
    }

    /** whitespace split that keeps {@code rgb(1, 2, 3)} together */
    public static List<String> splitValues(String v) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (Character.isWhitespace(ch) && depth == 0) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(ch);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    static Double fontWeight(String v, double parent) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "normal" -> 400.0;
            case "bold" -> 700.0;
            case "bolder" -> Math.min(parent + 300.0, 900.0);
            case "lighter" -> Math.max(parent - 300.0, 100.0);
            default -> parseNumber(v.trim());
        };
    }
}
