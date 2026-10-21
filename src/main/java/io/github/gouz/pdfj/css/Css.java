package io.github.gouz.pdfj.css;

import io.github.gouz.pdfj.style.Style;
import org.jsoup.nodes.Element;
import org.jsoup.select.Evaluator;
import org.jsoup.select.QueryParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal CSS: stylesheet / declaration parsing, specificity, cascade.
 * Selectors are matched by jsoup, so anything it parses works.
 */
public final class Css {
    private Css() {}

    public record Decl(String prop, String value, boolean important) {}

    public enum Pseudo { BEFORE, AFTER, MARKER, FIRST_LETTER, FIRST_LINE }

    /** An {@code @page} rule: page properties, and its margin boxes ({@code @top-center { content: ... }}). */
    public static final class PageRule {
        /** {@code @page wide} (pages of elements with {@code page: wide}); null for every page */
        public String name;
        /** {@code :first}, {@code :left}, {@code :right}; null for {@code @page} itself */
        public String pseudo;
        public List<Decl> decls = new ArrayList<>();
        /** ({@code top-left}, {@code top-center}, {@code bottom-right}...) -> declarations */
        public List<Map.Entry<String, List<Decl>>> boxes = new ArrayList<>();
    }

    public static final class Rule {
        public final Evaluator selector;
        /** (ids, classes/attributes/pseudo-classes, types), compared lexicographically */
        public final int[] specificity;
        /** rule of {@code sel::before} / {@code sel::after}: matches {@code sel}, styles its generated content */
        public final Pseudo pseudo;
        public final List<Decl> decls;

        Rule(Evaluator selector, int[] specificity, Pseudo pseudo, List<Decl> decls) {
            this.selector = selector;
            this.specificity = specificity;
            this.pseudo = pseudo;
            this.decls = decls;
        }
    }

    /** What a stylesheet contributes: style rules, {@code @page} and {@code @font-face} declarations. */
    public static final class Sheet {
        public final List<Rule> rules = new ArrayList<>();
        public final List<PageRule> pages = new ArrayList<>();
        public final List<List<Decl>> fontFaces = new ArrayList<>();
        public boolean hasPseudo;
    }

    /** The medium @media queries are evaluated against: print, a page area of this size (pt). */
    public record Media(double width, double height) {}

    static String stripComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (true) {
            int start = css.indexOf("/*", i);
            if (start < 0) {
                out.append(css, i, css.length());
                break;
            }
            out.append(css, i, start);
            int end = css.indexOf("*/", start + 2);
            if (end < 0) break;
            i = end + 2;
        }
        return out.toString();
    }

    /** {@code color: red; margin: 0 !important} -> declarations, property names lowercased */
    public static List<Decl> parseDeclarations(String css) {
        List<Decl> out = new ArrayList<>();
        for (String d : splitTopLevel(css, ';')) {
            int colon = d.indexOf(':');
            if (colon < 0) continue;
            String prop = d.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = d.substring(colon + 1).trim();
            boolean important = value.toLowerCase(Locale.ROOT).endsWith("!important");
            if (important) value = value.substring(0, value.length() - "!important".length()).stripTrailing();
            if (!prop.isEmpty() && !value.isEmpty()) out.add(new Decl(prop, value, important));
        }
        return out;
    }

    /** split on {@code sep} outside parentheses and quotes ({@code url(a;b)}, {@code "a;b"}) */
    public static List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == sep && depth <= 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /**
     * Rules of a stylesheet, in source order. {@code @media} blocks that match {@code media} are included,
     * {@code @page} / {@code @font-face} collected, other at-rules ({@code @import}, {@code @keyframes}...) skipped.
     */
    public static void parseStylesheet(String css, Media media, Sheet sheet) {
        parseBlock(stripComments(css), media, sheet, 0);
    }

    private static void parseBlock(String css, Media media, Sheet sheet, int depth) {
        int pos = 0;
        while (true) {
            int open = css.indexOf('{', pos);
            if (open < 0) break;
            // statements before the block (`@import url(x);`) are not part of the prelude
            String before = css.substring(pos, open);
            int semi = before.lastIndexOf(';');
            String prelude = (semi >= 0 ? before.substring(semi + 1) : before).trim();
            // matching close brace (at-rules nest blocks)
            int d = 0, close = css.length();
            for (int i = open; i < css.length(); i++) {
                char c = css.charAt(i);
                if (c == '{') d++;
                else if (c == '}') {
                    d--;
                    if (d == 0) {
                        close = i;
                        break;
                    }
                }
            }
            String body = css.substring(open + 1, Math.min(close, css.length()));
            pos = Math.min(close + 1, css.length());
            if (prelude.startsWith("@")) {
                String at = prelude.substring(1);
                int ws = indexOfWhitespace(at);
                String name = (ws < 0 ? at : at.substring(0, ws)).toLowerCase(Locale.ROOT);
                String args = ws < 0 ? "" : at.substring(ws + 1);
                switch (name) {
                    case "media" -> {
                        if (depth < 8 && mediaMatches(args, media)) parseBlock(body, media, sheet, depth + 1);
                    }
                    case "supports" -> {
                        if (depth < 8) parseBlock(body, media, sheet, depth + 1);
                    }
                    case "page" -> {
                        // `@page`, `@page :first`, `@page wide`, `@page wide:left`
                        String a = args.trim();
                        int c = a.indexOf(':');
                        String pageName = c < 0 ? a : a.substring(0, c);
                        String pseudo = c < 0 ? null : ":" + a.substring(c + 1).trim().toLowerCase(Locale.ROOT);
                        if (pseudo == null || pseudo.equals(":first") || pseudo.equals(":left") || pseudo.equals(":right")) {
                            PageRule rule = parsePage(body, pseudo);
                            String n = pageName.trim();
                            rule.name = n.isEmpty() ? null : n;
                            sheet.pages.add(rule);
                        }
                    }
                    case "font-face" -> sheet.fontFaces.add(parseDeclarations(body));
                    default -> {}
                }
                continue;
            }
            List<Decl> decls = parseDeclarations(body);
            if (decls.isEmpty()) continue;
            // one rule per selector of the list: each has its own specificity
            for (String raw : splitTopLevel(prelude, ',')) {
                String sel = raw.trim();
                String lower = sel.toLowerCase(Locale.ROOT);
                Pseudo pseudo = null;
                String base = sel;
                String[][] suffixes = {
                    {"::before", "BEFORE"}, {":before", "BEFORE"}, {"::after", "AFTER"}, {":after", "AFTER"},
                    {"::marker", "MARKER"}, {"::first-letter", "FIRST_LETTER"}, {":first-letter", "FIRST_LETTER"},
                    {"::first-line", "FIRST_LINE"}, {":first-line", "FIRST_LINE"},
                };
                for (String[] s : suffixes) {
                    if (lower.endsWith(s[0])) {
                        pseudo = Pseudo.valueOf(s[1]);
                        base = sel.substring(0, sel.length() - s[0].length());
                        break;
                    }
                }
                if (pseudo == null && lower.contains("::")) continue; // ::placeholder...: not generated
                if (base.isBlank() || base.endsWith(" ") || base.endsWith(">")) base = base + "*";
                Evaluator evaluator = parseSelector(base);
                if (evaluator == null) continue;
                int[] spec = specificity(base);
                if (pseudo != null) {
                    spec[2]++;
                    sheet.hasPseudo = true;
                }
                sheet.rules.add(new Rule(evaluator, spec, pseudo, decls));
            }
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    /** A selector jsoup understands, else null (`:hover`...: never matches in print anyway). */
    static Evaluator parseSelector(String sel) {
        try {
            return QueryParser.parse(sel);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    /** {@code @page} body: declarations, and nested margin boxes {@code @top-center { ... }} */
    static PageRule parsePage(String body, String pseudo) {
        PageRule rule = new PageRule();
        rule.pseudo = pseudo;
        StringBuilder decls = new StringBuilder();
        String rest = body;
        while (true) {
            int at = rest.indexOf('@');
            if (at < 0) break;
            decls.append(rest, 0, at);
            int open = rest.indexOf('{', at);
            if (open < 0) {
                rest = "";
                break;
            }
            int close = rest.indexOf('}', open);
            if (close < 0) {
                rest = "";
                break;
            }
            String name = rest.substring(at + 1, open).trim().toLowerCase(Locale.ROOT);
            rule.boxes.add(Map.entry(name, parseDeclarations(rest.substring(open + 1, close))));
            rest = rest.substring(close + 1);
        }
        decls.append(rest);
        rule.decls = parseDeclarations(decls.toString());
        return rule;
    }

    /** {@code print}, {@code screen and (min-width: 600px)}, {@code (orientation: landscape)}, {@code not print}, lists with commas. */
    public static boolean mediaMatches(String query, Media m) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        for (String raw : splitTopLevel(q, ',')) {
            String one = raw.trim();
            boolean not = false;
            if (one.startsWith("not ")) {
                not = true;
                one = one.substring(4);
            } else if (one.startsWith("only ")) {
                one = one.substring(5);
            }
            boolean ok = true;
            for (String p : one.split(" and ", -1)) {
                String part = p.trim();
                if (part.startsWith("(") && part.endsWith(")")) {
                    ok &= mediaFeature(part.substring(1, part.length() - 1).trim(), m);
                } else {
                    ok &= part.equals("all") || part.equals("print") || part.isEmpty();
                }
            }
            if (ok != not) return true;
        }
        return false;
    }

    private static Double mediaLength(String v) {
        v = v.trim();
        int unit = 0;
        while (unit < v.length() && !Character.isLetter(v.charAt(unit))) unit++;
        double n;
        try {
            n = Double.parseDouble(v.substring(0, unit).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (v.substring(unit)) {
            case "em", "rem" -> n * 12.0;
            case "in" -> n * 72.0;
            case "cm" -> n * 72.0 / 2.54;
            case "mm" -> n * 72.0 / 25.4;
            case "pt" -> n;
            default -> n * Style.PX;
        };
    }

    private static boolean mediaFeature(String f, Media m) {
        // range syntax: `width >= 600px`
        for (String op : new String[] {">=", "<=", ">", "<"}) {
            int i = f.indexOf(op);
            if (i < 0) continue;
            String name = f.substring(0, i).trim();
            double actual;
            if (name.equals("width")) actual = m.width();
            else if (name.equals("height")) actual = m.height();
            else return false;
            Double v = mediaLength(f.substring(i + op.length()));
            if (v == null) return false;
            return switch (op) {
                case ">=" -> actual >= v;
                case "<=" -> actual <= v;
                case ">" -> actual > v;
                default -> actual < v;
            };
        }
        int colon = f.indexOf(':');
        String name = colon < 0 ? f : f.substring(0, colon).trim();
        String value = colon < 0 ? "" : f.substring(colon + 1).trim();
        Double v = mediaLength(value);
        return switch (name) {
            case "min-width" -> v != null && m.width() >= v;
            case "max-width" -> v != null && m.width() <= v;
            case "width" -> v != null && Math.abs(m.width() - v) < 0.5;
            case "min-height" -> v != null && m.height() >= v;
            case "max-height" -> v != null && m.height() <= v;
            case "orientation" -> value.equals("landscape") == (m.width() > m.height());
            case "color" -> true;
            case "prefers-color-scheme" -> value.equals("light");
            case "prefers-reduced-motion" -> value.equals("reduce");
            case "hover", "pointer" -> value.equals("none");
            default -> false;
        };
    }

    /** Approximate specificity from the selector text (good enough for tag / .class / #id / [attr] / :pseudo). */
    static int[] specificity(String sel) {
        int a = 0, b = 0, c = 0;
        int i = 0, n = sel.length();
        while (i < n) {
            char ch = sel.charAt(i);
            boolean counted = false;
            if (ch == '#') {
                a++;
                counted = true;
            } else if (ch == '.') {
                b++;
                counted = true;
            } else if (ch == '[') {
                b++;
                while (i < n && sel.charAt(i) != ']') i++;
            } else if (ch == ':' && i + 1 < n && sel.charAt(i + 1) == ':') {
                c++;
                i++;
                counted = true;
            } else if (ch == ':') {
                b++;
                counted = true;
            } else if (ch < 128 && Character.isLetter(ch) && (i == 0 || " >+~(,".indexOf(sel.charAt(i - 1)) >= 0)) {
                c++;
                counted = true;
            }
            i++;
            if (counted) {
                // skip the rest of the name
                while (i < n && (Character.isLetterOrDigit(sel.charAt(i)) && sel.charAt(i) < 128 || sel.charAt(i) == '-' || sel.charAt(i) == '_')) i++;
            }
        }
        return new int[] {a, b, c};
    }

    private record Entry(boolean important, int origin, int[] spec, int order, Decl decl) {}

    /**
     * Winning declarations for an element (or its {@code ::before} / {@code ::after}): sorted by
     * (important, origin, specificity, source order), inline {@code style} beats every stylesheet rule.
     * Later entries win.
     */
    public static List<Decl> matched(Element el, Element root, Pseudo pseudo, List<Rule> ua, List<Rule> author, List<Decl> inline) {
        List<Entry> out = new ArrayList<>();
        int order = 0;
        List<List<Rule>> origins = List.of(ua, author);
        for (int origin = 0; origin < 2; origin++) {
            for (Rule rule : origins.get(origin)) {
                if (rule.pseudo == pseudo && matches(rule.selector, root, el)) {
                    for (Decl d : rule.decls) out.add(new Entry(d.important(), origin, rule.specificity, order++, d));
                }
            }
        }
        if (pseudo == null) {
            for (Decl d : inline) out.add(new Entry(d.important(), 2, new int[] {Integer.MAX_VALUE, 0, 0}, order++, d));
        }
        out.sort((x, y) -> {
            if (x.important != y.important) return x.important ? 1 : -1;
            if (x.origin != y.origin) return Integer.compare(x.origin, y.origin);
            for (int i = 0; i < 3; i++) if (x.spec[i] != y.spec[i]) return Integer.compare(x.spec[i], y.spec[i]);
            return Integer.compare(x.order, y.order);
        });
        List<Decl> decls = new ArrayList<>(out.size());
        for (Entry e : out) decls.add(e.decl);
        return decls;
    }

    static boolean matches(Evaluator selector, Element root, Element el) {
        try {
            return selector.matches(root, el);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
