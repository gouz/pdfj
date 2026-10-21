package io.github.gouz.pdfj.style;

import io.github.gouz.pdfj.css.Css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code grid-template-columns} / {@code -rows} tracks, and {@code grid-column} / {@code grid-row} placements. */
public final class GridTemplate {
    private GridTemplate() {}

    public enum MinKind { FIXED, AUTO, MIN_CONTENT, MAX_CONTENT }

    public enum MaxKind { FIXED, AUTO, MIN_CONTENT, MAX_CONTENT, FR, FIT_CONTENT }

    /** A track sizing function: {@code minmax(min, max)}; fixed lengths are in {@code minLen} / {@code maxLen}. */
    public record Track(MinKind min, Len minLen, MaxKind max, Len maxLen, double fr) {
        public static final Track AUTO = new Track(MinKind.AUTO, null, MaxKind.AUTO, null, 0);

        public static Track fixed(Len l) {
            return new Track(MinKind.FIXED, l, MaxKind.FIXED, l, 0);
        }

        public static Track fr(double fr) {
            return new Track(MinKind.AUTO, null, MaxKind.FR, null, fr);
        }

        /** {@code minmax(auto, max)} */
        public static Track autoTo(MaxKind max, Len maxLen, double fr) {
            return new Track(MinKind.AUTO, null, max, maxLen, fr);
        }

        public boolean flexible() {
            return max == MaxKind.FR;
        }
    }

    /** Tracks, repeated {@code repeat} times; {@code AUTO_FILL} / {@code AUTO_FIT} as many as fit. */
    public record Item(List<Track> tracks, int repeat) {
        public static final int AUTO_FILL = -1, AUTO_FIT = -2;
    }

    /** null when the value is not a track list we understand */
    public static List<Item> parse(String value, double em, double rem) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("none") || v.isEmpty()) return List.of();
        List<Item> out = new ArrayList<>();
        for (String word : words(v)) {
            if (word.startsWith("[")) continue; // line names
            if (word.startsWith("repeat(") && word.endsWith(")")) {
                String inner = word.substring(7, word.length() - 1);
                int comma = topLevelComma(inner);
                if (comma < 0) return null;
                String count = inner.substring(0, comma).trim();
                int repeat;
                if (count.equals("auto-fill")) repeat = Item.AUTO_FILL;
                else if (count.equals("auto-fit")) repeat = Item.AUTO_FIT;
                else {
                    try {
                        repeat = Integer.parseInt(count);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (repeat < 1) return null;
                    repeat = Math.min(repeat, 1000);
                }
                List<Track> tracks = new ArrayList<>();
                for (String w : words(inner.substring(comma + 1))) {
                    if (w.startsWith("[")) continue;
                    Track t = track(w, em, rem);
                    if (t == null) return null;
                    tracks.add(t);
                }
                if (tracks.isEmpty()) return null;
                out.add(new Item(List.copyOf(tracks), repeat));
            } else {
                Track t = track(word, em, rem);
                if (t == null) return null;
                out.add(new Item(List.of(t), 1));
            }
        }
        return List.copyOf(out);
    }

    private static int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /** whitespace split keeping functions and [names] together */
    private static List<String> words(String v) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (Character.isWhitespace(c) && depth == 0) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static Track track(String w, double em, double rem) {
        switch (w) {
            case "auto" -> {
                return Track.AUTO;
            }
            case "min-content" -> {
                return new Track(MinKind.MIN_CONTENT, null, MaxKind.MIN_CONTENT, null, 0);
            }
            case "max-content" -> {
                return new Track(MinKind.MAX_CONTENT, null, MaxKind.MAX_CONTENT, null, 0);
            }
            default -> {}
        }
        if (w.endsWith("fr")) {
            Double fr = Values.number(w.substring(0, w.length() - 2));
            return fr == null || fr < 0 ? null : Track.fr(fr);
        }
        if (w.startsWith("minmax(") && w.endsWith(")")) {
            List<String> args = Css.splitTopLevel(w.substring(7, w.length() - 1), ',');
            if (args.size() != 2) return null;
            String a = args.get(0).trim(), b = args.get(1).trim();
            MinKind min;
            Len minLen = null;
            switch (a) {
                case "auto" -> min = MinKind.AUTO;
                case "min-content" -> min = MinKind.MIN_CONTENT;
                case "max-content" -> min = MinKind.MAX_CONTENT;
                default -> {
                    minLen = Values.len(a, em, rem);
                    if (minLen == null || minLen.isAuto()) return null;
                    min = MinKind.FIXED;
                }
            }
            MaxKind max;
            Len maxLen = null;
            double fr = 0;
            switch (b) {
                case "auto" -> max = MaxKind.AUTO;
                case "min-content" -> max = MaxKind.MIN_CONTENT;
                case "max-content" -> max = MaxKind.MAX_CONTENT;
                default -> {
                    if (b.endsWith("fr")) {
                        Double f = Values.number(b.substring(0, b.length() - 2));
                        if (f == null || f < 0) return null;
                        max = MaxKind.FR;
                        fr = f;
                    } else {
                        maxLen = Values.len(b, em, rem);
                        if (maxLen == null || maxLen.isAuto()) return null;
                        max = MaxKind.FIXED;
                    }
                }
            }
            return new Track(min, minLen, max, maxLen, fr);
        }
        if (w.startsWith("fit-content(") && w.endsWith(")")) {
            Len l = Values.len(w.substring(12, w.length() - 1), em, rem);
            return l == null || l.isAuto() ? null : new Track(MinKind.AUTO, null, MaxKind.FIT_CONTENT, l, 0);
        }
        Len l = Values.len(w, em, rem);
        return l == null || l.isAuto() ? null : Track.fixed(l);
    }

    /** A grid line: {@code auto}, a line number (negative: from the end of the explicit grid), {@code span n}. */
    public record Line(Kind kind, int n) {
        public enum Kind { AUTO, LINE, SPAN }

        public static final Line AUTO = new Line(Kind.AUTO, 0);

        public static Line line(int n) {
            return new Line(Kind.LINE, n);
        }

        public static Line span(int n) {
            return new Line(Kind.SPAN, Math.max(1, n));
        }
    }

    public record Placement(Line start, Line end) {
        public static final Placement AUTO = new Placement(Line.AUTO, Line.AUTO);
    }

    /** {@code 1 / 3}, {@code span 2}, {@code 2}, {@code 1 / -1}; null if invalid */
    public static Placement placement(String v) {
        String[] parts = v.split("/", -1);
        if (parts.length > 2) return null;
        Line start = line(parts[0].trim());
        Line end = parts.length > 1 ? line(parts[1].trim()) : Line.AUTO;
        return start == null || end == null ? null : new Placement(start, end);
    }

    private static Line line(String s) {
        if (s.equals("auto") || s.isEmpty()) return Line.AUTO;
        String[] w = s.split("\\s+");
        try {
            if (w[0].equals("span")) return w.length == 2 ? Line.span(Integer.parseInt(w[1])) : w.length == 1 ? Line.span(1) : null;
            if (w.length == 1) {
                int n = Integer.parseInt(w[0]);
                return n == 0 ? null : Line.line(Math.max(-10000, Math.min(10000, n)));
            }
        } catch (NumberFormatException e) {
            return Line.AUTO; // named lines: unsupported, auto-placed
        }
        return Line.AUTO;
    }
}
