package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.css.Css;
import io.github.gouz.pdfj.css.Css.Decl;
import io.github.gouz.pdfj.css.Css.Media;
import io.github.gouz.pdfj.css.Css.Pseudo;
import io.github.gouz.pdfj.css.Css.Rule;
import io.github.gouz.pdfj.css.Css.Sheet;
import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.font.Woff;
import io.github.gouz.pdfj.net.ImagePolicy;
import io.github.gouz.pdfj.net.Images;
import io.github.gouz.pdfj.style.Colors;
import io.github.gouz.pdfj.style.GridTemplate;
import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Rgba;
import io.github.gouz.pdfj.style.Style;
import io.github.gouz.pdfj.style.Style.Align;
import io.github.gouz.pdfj.style.Style.Background;
import io.github.gouz.pdfj.style.Style.BgSize;
import io.github.gouz.pdfj.style.Style.BgSource;
import io.github.gouz.pdfj.style.Style.BorderStyle;
import io.github.gouz.pdfj.style.Style.BoxArea;
import io.github.gouz.pdfj.style.Style.Display;
import io.github.gouz.pdfj.style.Style.FlexDirection;
import io.github.gouz.pdfj.style.Style.ObjectFit;
import io.github.gouz.pdfj.style.Style.TextAlign;
import io.github.gouz.pdfj.style.Style.TextTransform;
import io.github.gouz.pdfj.style.Style.VAlign;
import io.github.gouz.pdfj.style.Style.WhiteSpace;
import io.github.gouz.pdfj.style.Values;
import io.github.gouz.pdfj.text.Brush;
import io.github.gouz.pdfj.text.Paragraph;
import io.github.gouz.pdfj.text.Piece;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** DOM + computed styles -> box tree (block, flex, grid; tables as grids) with text leaves. */
public final class Builder {
    /** Default (user-agent) styles, close to browsers'. */
    static final String UA_CSS = """
        html, body, div, p, h1, h2, h3, h4, h5, h6, ul, ol, dl, dt, dd, section, article, aside, header, footer,
        main, nav, form, blockquote, pre, figure, figcaption, address, hr, fieldset, legend, details, summary,
        center, hgroup, menu, dir, noscript { display: block }
        head, style, script, title, meta, link, template, base, datalist, param, source, track, area, map { display: none }
        li { display: list-item }
        table { display: table; border-spacing: 2px }
        thead, tbody, tfoot { display: table-row-group }
        tr { display: table-row }
        td, th { display: table-cell; padding: 1px; vertical-align: middle }
        caption { display: table-caption; text-align: center }
        th { font-weight: bold; text-align: center }
        p { margin: 1em 0 }
        h1 { font-size: 2em; margin: 0.67em 0; font-weight: bold }
        h2 { font-size: 1.5em; margin: 0.83em 0; font-weight: bold }
        h3 { font-size: 1.17em; margin: 1em 0; font-weight: bold }
        h4 { margin: 1.33em 0; font-weight: bold }
        h5 { font-size: 0.83em; margin: 1.67em 0; font-weight: bold }
        h6 { font-size: 0.67em; margin: 2.33em 0; font-weight: bold }
        ul, ol, menu, dir { margin: 1em 0; padding-left: 40px }
        ol { list-style-type: decimal }
        ul ul, ol ul { list-style-type: circle }
        ul ul ul, ol ul ul, ul ol ul { list-style-type: square }
        li ul, li ol { margin: 0 }
        dl { margin: 1em 0 }
        dd { margin-left: 40px }
        blockquote, figure { margin: 1em 40px }
        pre { white-space: pre; margin: 1em 0; font-family: monospace }
        code, kbd, samp, tt { font-family: monospace }
        b, strong { font-weight: bold }
        i, em, cite, var, dfn, address { font-style: italic }
        u, ins { text-decoration: underline }
        s, strike, del { text-decoration: line-through }
        small, sub, sup { font-size: 0.83em }
        sub { vertical-align: sub }
        sup { vertical-align: super }
        big { font-size: 1.17em }
        mark { background-color: yellow }
        a { color: #0000ee; text-decoration: underline }
        hr { border-top: 1px solid #888; margin: 0.5em 0 }
        center { text-align: center }
        img { object-fit: contain }
        [dir=rtl] { direction: rtl }
        [dir=ltr] { direction: ltr }
        q::before { content: open-quote }
        q::after { content: close-quote }
        """;

    static final List<Rule> UA;

    static {
        Sheet sheet = new Sheet();
        Css.parseStylesheet(UA_CSS, new Media(0, 0), sheet);
        UA = List.copyOf(sheet.rules);
    }

    /** Forget what the user-agent rules memoized while matching this thread's last document. */
    public static void endDocument() {
        for (Rule r : UA) org.jsoup.select.Evaluators.reset(r.selector);
    }

    /** Deeper boxes are flattened into their ancestor; deeper inline content is dropped. */
    static final int MAX_BOX_DEPTH = 100;
    static final int MAX_WALK_DEPTH = 400;

    private static String attr(Element el, String name) {
        return el.hasAttr(name) ? el.attr(name) : null;
    }

    // ---- stylesheets

    /**
     * The {@code <style>} sheets of a document, for a given medium. {@code <link rel="stylesheet" href>}
     * are read from the image directory (or the network when remote resources are enabled).
     */
    public static Sheet stylesheet(Document doc, Media media, ImagePolicy policy) {
        Sheet sheet = new Sheet();
        for (Element s : doc.select("style, link")) {
            String m = attr(s, "media");
            if (m != null && !Css.mediaMatches(m, media)) continue;
            if (s.normalName().equals("style")) {
                Css.parseStylesheet(withImports(s.data(), null, policy, 0), media, sheet);
            } else {
                String rel = attr(s, "rel");
                if (rel == null || java.util.Arrays.stream(rel.split("\\s+")).noneMatch(r -> r.equalsIgnoreCase("stylesheet"))) continue;
                String href = s.attr("href");
                String css = loadCss(href, policy);
                if (css != null) Css.parseStylesheet(withImports(css, href, policy, 0), media, sheet);
            }
        }
        pageVars(doc, sheet);
        return sheet;
    }

    /** var() in {@code @page} rules and their margin boxes: the page context inherits the root element's custom properties. */
    private static void pageVars(Document doc, Sheet sheet) {
        boolean any = false;
        for (Css.PageRule p : sheet.pages) {
            for (Decl d : p.decls) any |= d.value().contains("var(");
            for (var b : p.boxes) for (Decl d : b.getValue()) any |= d.value().contains("var(");
        }
        if (!any) return;
        Element root = doc.children().isEmpty() ? null : doc.child(0);
        Map<String, String> vars = new HashMap<>();
        if (root != null) {
            String style = attr(root, "style");
            List<Decl> inline = style == null ? List.of() : Css.parseDeclarations(style);
            for (Decl d : Css.matched(root, doc, null, UA, sheet.rules, inline)) if (d.prop().startsWith("--")) vars.put(d.prop(), d.value());
        }
        for (Css.PageRule page : sheet.pages) {
            page.decls = substitute(page.decls, vars);
            List<Map.Entry<String, List<Decl>>> boxes = new ArrayList<>();
            for (var b : page.boxes) boxes.add(Map.entry(b.getKey(), substitute(b.getValue(), vars)));
            page.boxes = boxes;
        }
    }

    /** a reference to an undefined variable without fallback drops the declaration, as in style rules */
    private static List<Decl> substitute(List<Decl> decls, Map<String, String> vars) {
        List<Decl> out = new ArrayList<>();
        for (Decl d : decls) {
            String v = Values.substituteVars(d.value(), vars, 0);
            if (v != null) out.add(new Decl(d.prop(), v, d.important()));
        }
        return out;
    }

    /** A stylesheet file: from the image directory, or http(s) when remote resources are enabled. */
    static String loadCss(String href, ImagePolicy policy) {
        byte[] data = Images.load(href, policy);
        if (data == null) {
            System.err.println("pdfj: stylesheet blocked or not found: " + (href.length() > 80 ? href.substring(0, 80) : href));
            return null;
        }
        // url() in the sheet are relative to the sheet, not to the document
        return absoluteUrls(new String(data, StandardCharsets.UTF_8), href);
    }

    /** {@code rel} relative to {@code base} (a URL or a path in the image directory) */
    static String joinUrl(String base, String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || lower.startsWith("data:") || lower.startsWith("#")) return rel;
        if (rel.startsWith("/")) {
            String path = rel.substring(1);
            int i = base.indexOf("://");
            if (i < 0) return path;
            String scheme = base.substring(0, i), rest = base.substring(i + 3);
            return scheme + "://" + rest.split("/", 2)[0] + "/" + path;
        }
        String dir = base.split("[?#]", 2)[0];
        // the directory of the base: up to its last '/' (keeping `scheme://host`)
        int cut = dir.lastIndexOf('/');
        if (cut >= 0 && (dir.substring(0, cut).endsWith("/") || dir.substring(0, cut).endsWith(":"))) cut = -1;
        if (cut >= 0) return dir.substring(0, cut) + "/" + rel;
        if (dir.contains("://")) return dir + "/" + rel;
        return rel;
    }

    /** Rewrite the relative {@code url(...)} of a stylesheet against the stylesheet's own location. */
    static String absoluteUrls(String css, String base) {
        if (!base.contains("/")) return css;
        StringBuilder out = new StringBuilder(css.length());
        int pos = 0;
        while (true) {
            int i = css.indexOf("url(", pos);
            if (i < 0) break;
            out.append(css, pos, i + 4);
            int close = css.indexOf(')', i + 4);
            if (close < 0) close = css.length();
            String inner = css.substring(i + 4, close).trim();
            out.append('"').append(joinUrl(base, Values.trimQuotes(inner))).append('"');
            pos = close;
        }
        out.append(css.substring(pos));
        return out.toString();
    }

    /** Inline the {@code @import url(x) [media];} statements of a stylesheet (recursively, 4 levels at most). */
    static String withImports(String css, String base, ImagePolicy policy, int depth) {
        if (!css.contains("@import") || depth >= 4) return css;
        StringBuilder out = new StringBuilder(css.length());
        int pos = 0;
        while (true) {
            int at = css.indexOf("@import", pos);
            if (at < 0) break;
            out.append(css, pos, at);
            int end = css.indexOf(';', at);
            if (end < 0) {
                pos = css.length();
                break;
            }
            String stmt = css.substring(at + "@import".length(), end).trim();
            pos = end + 1;
            String target, media;
            if (stmt.startsWith("url(")) {
                String u = stmt.substring(4);
                int close = u.indexOf(')');
                if (close < 0) close = u.length();
                target = u.substring(0, close);
                media = close + 1 <= u.length() ? u.substring(Math.min(close + 1, u.length())) : "";
            } else {
                char q = stmt.isEmpty() ? '"' : stmt.charAt(0);
                String inner = stmt.length() > 0 ? stmt.substring(1) : "";
                int close = inner.indexOf(q);
                if (close < 0) close = inner.length();
                target = inner.substring(0, close);
                media = close + 1 <= inner.length() ? inner.substring(Math.min(close + 1, inner.length())) : "";
            }
            target = Values.trimQuotes(target.trim());
            if (base != null) target = joinUrl(base, target);
            String imported = loadCss(target, policy);
            if (imported != null) {
                imported = withImports(imported, target, policy, depth + 1);
                if (media.isBlank()) out.append(imported);
                else out.append("@media ").append(media.trim()).append(" { ").append(imported).append(" }");
            }
        }
        out.append(css.substring(pos));
        return out.toString();
    }

    /** The http(s) {@code <img>} sources of a document, to download in parallel before laying it out. */
    public static List<String> remoteImages(Document doc) {
        List<String> out = new ArrayList<>();
        for (Element i : doc.select("img[src]")) {
            String s = i.attr("src");
            if (s.startsWith("http://") || s.startsWith("https://")) out.add(s);
        }
        return out;
    }

    /**
     * Load the @font-face fonts a sheet declares: {@code data:} URLs, or files of the image directory
     * (or http(s) when enabled). TrueType, OpenType, WOFF and WOFF2.
     */
    public static List<FontFace> loadFontFaces(List<List<Decl>> faces, ImagePolicy policy) {
        List<FontFace> out = new ArrayList<>();
        for (List<Decl> decls : faces) {
            String family = get(decls, "font-family");
            if (family == null) continue;
            family = Values.trimQuotes(family.trim());
            if (family.isEmpty()) continue;
            Integer weight = null;
            String w = get(decls, "font-weight");
            if (w != null) {
                String first = w.trim().split("\\s+")[0];
                weight = switch (first) {
                    case "normal" -> 400;
                    case "bold" -> 700;
                    default -> {
                        Double d = Values.number(first);
                        yield d == null ? null : (int) Math.round(d);
                    }
                };
            }
            String st = get(decls, "font-style");
            boolean italic = st != null && (st.contains("italic") || st.contains("oblique"));
            String srcs = get(decls, "src");
            if (srcs == null) continue;
            for (String src : Css.splitTopLevel(srcs, ',')) {
                String s = src.trim();
                int i = s.indexOf("url(");
                if (i < 0) continue;
                int close = s.indexOf(')', i + 4);
                if (close < 0) continue;
                String url = Values.trimQuotes(s.substring(i + 4, close).trim());
                byte[] data;
                if (url.toLowerCase(Locale.ROOT).startsWith("data:")) {
                    int comma = url.indexOf(',');
                    if (comma < 0 || !url.substring(0, comma).toLowerCase(Locale.ROOT).endsWith(";base64")) continue;
                    try {
                        data = Base64.getMimeDecoder().decode(url.substring(comma + 1).replaceAll("\\s", ""));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                } else {
                    data = Images.load(url, policy);
                    if (data == null) continue;
                }
                byte[] sfnt = Woff.toSfnt(data);
                if (sfnt == null) {
                    System.err.println("pdfj: @font-face \"" + family + "\": not a TrueType / OpenType / WOFF / WOFF2 font");
                    continue;
                }
                try {
                    List<FontFace> loaded = FontFace.load(sfnt, false);
                    if (loaded.isEmpty()) continue;
                    out.add(loaded.get(0).as(family, weight, st == null ? null : italic));
                    break;
                } catch (IOException | RuntimeException e) {
                    System.err.println("pdfj: @font-face \"" + family + "\": " + e.getMessage());
                }
            }
        }
        return out;
    }

    private static String get(List<Decl> decls, String prop) {
        for (int i = decls.size() - 1; i >= 0; i--) if (decls.get(i).prop().equals(prop)) return decls.get(i).value();
        return null;
    }

    // ---- the box tree

    private final Document doc;
    private final Sheet sheet;
    private final double rem;
    private final ImagePolicy policy;
    private final List<String> links = new ArrayList<>();
    /** CSS counters: name -> nested instances (innermost last) */
    private final Map<String, List<Integer>> counters = new HashMap<>();
    /** nesting of boxes and of walk() calls: bounded, so hostile HTML cannot overflow the stack */
    private int boxDepth, walkDepth;
    /** {@code ::first-letter} style waiting for the first letter of the current block */
    private Style firstLetter;
    /** {@code ::first-line} declarations waiting for the first text of the current block */
    private List<Decl> firstLine;
    /** {@code background-clip: text} elements (their box, once built), and the one painting the current text */
    private final List<Box> textFills = new ArrayList<>();
    private int textFill;
    /** decoded background images by url (the same image is often repeated on many boxes) */
    private final Map<String, Optional<Decode.Decoded>> bgImages = new HashMap<>();

    private Builder(Document doc, Sheet sheet, ImagePolicy policy, double rem) {
        this.doc = doc;
        this.sheet = sheet;
        this.policy = policy;
        this.rem = rem;
    }

    private static final class Ctx {
        final List<Box> children = new ArrayList<>();
        List<Piece> group = new ArrayList<>();
        /** the element whose box this is: its direct children of a flex / grid box are blockified */
        final Element owner;

        Ctx(Element owner) {
            this.owner = owner;
        }
    }

    /** Build the box tree of a document, the root ({@code <body>}) being a block of {@code width} pt. */
    public static Tree build(Document doc, Sheet sheet, Fonts.DocFonts fonts, ImagePolicy policy, String font, double fontSize, double width) {
        return Fonts.withDocFonts(fonts, () -> {
            Builder b = new Builder(doc, sheet, policy, fontSize);
            Style base = Style.root(font, fontSize);
            Element html = doc.children().isEmpty() ? null : doc.child(0);
            Box root;
            if (html == null) {
                root = new Box();
            } else {
                Style htmlStyle = b.styleOf(html, base);
                Element body = doc.body() == null ? html : doc.body();
                Style bodyStyle = b.styleOf(body, htmlStyle);
                // the page margins replace body's margin; the root fills the content width
                bodyStyle.margin = new Len[] {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
                bodyStyle.display = Display.BLOCK;
                bodyStyle.size[0] = Len.px(width);
                if (bodyStyle.background == null) bodyStyle.background = htmlStyle.background;
                if (bodyStyle.bg == null) bodyStyle.bg = htmlStyle.bg;
                b.applyCounters(body, bodyStyle, new ArrayList<>());
                root = b.buildBox(body, bodyStyle);
                if (root == null) root = new Box();
            }
            List<Box> fills = new ArrayList<>();
            for (Box f : b.textFills) fills.add(f == null ? root : f);
            return new Tree(root, b.links, fills);
        });
    }

    /** A {@code width} / {@code height} attribute: a bare number is px (HTML), else a CSS length. */
    private static Len htmlLen(String v, double em, double rem) {
        Double n = Values.number(v);
        return n != null ? Len.px(n * Style.PX) : Values.len(v, em, rem);
    }

    static String applyTransform(String t, TextTransform tr) {
        return switch (tr) {
            case NONE -> t;
            case UPPER -> t.toUpperCase(Locale.ROOT);
            case LOWER -> t.toLowerCase(Locale.ROOT);
            case CAPITALIZE -> {
                StringBuilder out = new StringBuilder(t.length());
                boolean start = true;
                for (int i = 0; i < t.length(); ) {
                    int c = t.codePointAt(i);
                    i += Character.charCount(c);
                    if (start && Character.isLetterOrDigit(c)) {
                        out.append(new String(Character.toChars(c)).toUpperCase(Locale.ROOT));
                        start = false;
                    } else {
                        start = Character.isWhitespace(c) || c == '-';
                        out.appendCodePoint(c);
                    }
                }
                yield out.toString();
            }
        };
    }

    /** Default marker text of a list item ({@code disc} -> "•", {@code decimal} -> "3.", {@code "→ "} -> "→ "). */
    private static String defaultMarker(String kind, int n) {
        if (!kind.isEmpty() && (kind.charAt(0) == '"' || kind.charAt(0) == '\'')) {
            char q = kind.charAt(0);
            int a = 0, e = kind.length();
            while (a < e && kind.charAt(a) == q) a++;
            while (e > a && kind.charAt(e - 1) == q) e--;
            return kind.substring(a, e);
        }
        return switch (kind) {
            case "none" -> null;
            case "disc", "circle", "square" -> Values.formatCounter(n, kind);
            default -> Values.formatCounter(n, kind) + ".";
        };
    }

    private static Brush brushOf(Style s) {
        return new Brush(s.color, null, 0, 0);
    }

    /** the layout box of an element style */
    private static Box boxOf(Style s) {
        Box b = new Box();
        b.display = switch (s.display) {
            case FLEX -> Box.Display.FLEX;
            case GRID, TABLE -> Box.Display.GRID;
            case NONE -> Box.Display.NONE;
            default -> Box.Display.BLOCK;
        };
        b.absolute = s.positionAbsolute;
        b.inset = s.inset.clone();
        b.size = s.size.clone();
        b.minSize = s.minSize.clone();
        b.maxSize = s.maxSize.clone();
        b.margin = s.margin.clone();
        b.padding = s.padding.clone();
        for (int i = 0; i < 4; i++) b.border[i] = s.borderStyle[i] == BorderStyle.NONE ? 0 : Math.max(0, s.borderWidth[i]);
        b.borderBox = s.borderBox;
        b.floating = s.floating != Style.Float.NONE;
        b.flexDirection = s.flexDirection;
        b.flexWrap = s.flexWrap;
        b.grow = Math.max(0, s.flexGrow);
        b.shrink = Math.max(0, s.flexShrink);
        b.basis = s.flexBasis;
        b.justifyContent = s.justifyContent;
        b.alignContent = s.alignContent;
        b.alignItems = s.alignItems;
        b.alignSelf = s.alignSelf;
        b.justifyItems = s.justifyItems;
        b.justifySelf = s.justifySelf;
        b.gapRow = s.gap[0];
        b.gapColumn = s.gap[1];
        b.aspectRatio = s.aspectRatio;
        b.rtl = s.rtl;
        b.order = s.order;
        b.overflowHidden = s.overflowHidden;
        if (s.gridTemplateColumns != null) b.columns = s.gridTemplateColumns;
        if (s.gridTemplateRows != null) b.rows = s.gridTemplateRows;
        b.autoColumns = s.gridAutoColumns;
        b.autoRows = s.gridAutoRows;
        b.autoFlow = s.gridAutoFlow;
        if (s.gridColumn != null) b.gridColumn = s.gridColumn;
        if (s.gridRow != null) b.gridRow = s.gridRow;
        return b;
    }

    private static Deco decoOf(Style s) {
        Deco d = new Deco();
        d.background = s.background;
        for (int i = 0; i < 4; i++) {
            boolean none = s.borderStyle[i] == BorderStyle.NONE;
            d.borderWidth[i] = none ? 0 : Math.max(0, s.borderWidth[i]);
            d.borderStyle[i] = none ? null : s.borderStyle[i];
            d.borderColor[i] = s.borderColor[i] != null ? s.borderColor[i] : s.color;
        }
        d.radius = s.radius.clone();
        d.opacity = s.opacity;
        d.clip = s.overflowHidden;
        d.breakBefore = s.breakBefore;
        d.breakAfter = s.breakAfter;
        d.avoidBreakInside = s.breakInsideAvoid;
        if (s.transformFn != null) {
            d.transform = s.transformFn;
            d.transformOrigin = s.transformOrigin;
            d.transformEm = s.fontSize;
        }
        d.colorClip = s.bg == null ? BoxArea.BORDER : Background.nth(s.bg.clips, Math.max(s.bg.images.size(), 1) - 1, BoxArea.BORDER);
        d.shadows = s.shadows;
        return d;
    }

    private Style styleOf(Element el, Style parent) {
        String style = attr(el, "style");
        List<Decl> inline = style == null ? List.of() : Css.parseDeclarations(style);
        List<Decl> decls = Css.matched(el, doc, null, UA, sheet.rules, inline);
        Style s = Style.compute(parent, decls, rem);
        // presentational attributes
        String align = attr(el, "align");
        if (align != null && List.of("td", "th", "p", "div", "h1", "h2", "h3", "h4", "h5", "h6").contains(el.normalName())) {
            s.textAlign = switch (align.toLowerCase(Locale.ROOT)) {
                case "center" -> TextAlign.CENTER;
                case "right" -> TextAlign.RIGHT;
                case "justify" -> TextAlign.JUSTIFY;
                default -> TextAlign.LEFT;
            };
        }
        String bgcolor = attr(el, "bgcolor");
        if (bgcolor != null && s.background == null) {
            Rgba c = Colors.parse(bgcolor);
            if (c != null) s.background = c;
        }
        return s;
    }

    /** Decoration of a new element box. */
    private void finish(Box node, Style s) {
        Deco d = decoOf(s);
        if (s.bg != null) {
            for (int i = 0; i < s.bg.images.size(); i++) {
                BgSource src = s.bg.images.get(i);
                Deco.LayerPaint paint;
                if (src.gradient() != null) {
                    paint = new Deco.LayerPaint.GradientPaint(src.gradient());
                } else {
                    Optional<Decode.Decoded> decoded = bgImages.computeIfAbsent(src.url(), url -> {
                        Decode.Decoded img = Decode.image(Images.load(url, policy));
                        if (img == null) System.err.println("pdfj: background image blocked or not found: " + (url.length() > 80 ? url.substring(0, 80) : url));
                        return Optional.ofNullable(img);
                    });
                    if (decoded.isEmpty()) continue;
                    paint = new Deco.LayerPaint.ImagePaint(decoded.get().kind(), decoded.get().width(), decoded.get().height());
                }
                d.layers.add(new Deco.BgLayer(paint,
                    Background.nth(s.bg.sizes, i, BgSize.AUTO),
                    Background.nth(s.bg.positions, i, new Len[] {Len.pct(0), Len.pct(0)}),
                    Background.nth(s.bg.repeats, i, new boolean[] {true, true}),
                    Background.nth(s.bg.origins, i, BoxArea.PADDING),
                    Background.nth(s.bg.clips, i, BoxArea.BORDER),
                    Background.nth(s.bg.fixed, i, false)));
            }
        }
        if (d.needed()) node.deco = d;
    }

    /** {@code ::first-letter} / {@code ::first-line} of a block: armed for the text that follows. Returns which ones this block set. */
    private boolean[] firstPseudos(Element el, Style s) {
        boolean[] owns = new boolean[2];
        if (!sheet.hasPseudo) return owns;
        List<Decl> fl = Css.matched(el, doc, Pseudo.FIRST_LINE, UA, sheet.rules, List.of());
        if (!fl.isEmpty()) {
            firstLine = List.copyOf(fl);
            owns[1] = true;
        }
        List<Decl> letter = Css.matched(el, doc, Pseudo.FIRST_LETTER, UA, sheet.rules, List.of());
        if (!letter.isEmpty()) {
            Style st = Style.compute(s, letter, rem);
            if (st.floating == Style.Float.NONE) st.display = Display.INLINE;
            firstLetter = st;
            owns[0] = true;
        }
        return owns;
    }

    /** {@code counter(name, style)} / {@code counters(name, sep, style)} */
    private String counterText(String name, String sep, String style) {
        List<Integer> stack = counters.getOrDefault(name, List.of());
        if (sep != null) {
            if (stack.isEmpty()) return Values.formatCounter(0, style);
            List<String> all = new ArrayList<>();
            for (int n : stack) all.add(Values.formatCounter(n, style));
            return String.join(sep, all);
        }
        return Values.formatCounter(stack.isEmpty() ? 0 : stack.get(stack.size() - 1), style);
    }

    /** {@code ::before} / {@code ::after} content of {@code el}: inline text in the current group, or its own box. */
    private void pseudo(Element el, Style s, Pseudo which, Brush brush, Ctx ctx, Style container) {
        if (!sheet.hasPseudo && !el.normalName().equals("q")) return;
        List<Decl> decls = Css.matched(el, doc, which, UA, sheet.rules, List.of());
        if (decls.isEmpty()) return;
        Style ps = Style.compute(s, decls, rem);
        if (ps.content == null) return;
        String text = Values.content(ps.content, a -> attr(el, a), this::counterText);
        if (text == null) return;
        text = applyTransform(text, ps.transform);
        switch (ps.display) {
            case NONE -> {}
            case INLINE, CONTENTS -> {
                Brush b = new Brush(ps.color, ps.background != null ? ps.background : brush.background(), brush.link(), brush.textFill());
                ctx.group.add(new Piece.Text(text, ps, b));
            }
            default -> {
                boolean inline = ps.inlineBlock && !ps.positionAbsolute && ps.floating == Style.Float.NONE;
                if (!inline) flush(ctx, container);
                Ctx inner = new Ctx(null);
                if (!text.isEmpty()) {
                    inner.group.add(new Piece.Text(text, ps, brushOf(ps)));
                    flush(inner, ps);
                }
                Box node = boxOf(ps);
                for (Box c : inner.children) node.add(c);
                finish(node, ps);
                if (inline) ctx.group.add(new Piece.Box(node, ps));
                else ctx.children.add(node);
            }
        }
    }

    /** An element box (block / flex / grid / list item / table / cell) and its content. */
    private Box buildBox(Element el, Style s) {
        if (s.display == Display.TABLE) return buildTable(el, s);
        Ctx ctx = new Ctx(el);
        if (s.display == Display.LIST_ITEM) {
            List<Integer> li = counters.get("list-item");
            int n = li == null || li.isEmpty() ? 1 : li.get(li.size() - 1);
            // ::marker: its style, and its content instead of the list-style-type one
            List<Decl> decls = sheet.hasPseudo ? Css.matched(el, doc, Pseudo.MARKER, UA, sheet.rules, List.of()) : List.of();
            Style ms = decls.isEmpty() ? s.copy() : Style.compute(s, decls, rem);
            String text = ms.content != null ? Values.content(ms.content, a -> attr(el, a), this::counterText) : defaultMarker(s.listStyle, n);
            if (text != null) {
                ms.whiteSpace = WhiteSpace.NOWRAP;
                ms.underline = false;
                ms.strike = false;
                List<Piece> pieces = new ArrayList<>();
                pieces.add(new Piece.Text(text, ms, brushOf(ms)));
                Box leaf = textLeaf(pieces, ms);
                if (leaf != null) {
                    // outside marker: right edge against the item's left edge
                    leaf.absolute = true;
                    leaf.inset = s.rtl ? new Len[] {Len.ZERO, Len.AUTO, Len.AUTO, Len.pct(100)} : new Len[] {Len.ZERO, Len.pct(100), Len.AUTO, Len.AUTO};
                    leaf.margin = s.rtl ? new Len[] {Len.ZERO, Len.ZERO, Len.ZERO, Len.px(s.fontSize * 0.5)} : new Len[] {Len.ZERO, Len.px(s.fontSize * 0.5), Len.ZERO, Len.ZERO};
                    ctx.children.add(leaf);
                }
            }
        }
        // background-clip: text: this element's background paints the text inside it
        int outerFill = textFill;
        int fillSlot = -1;
        if (s.bg != null && s.bg.clips.contains(BoxArea.TEXT)) {
            textFills.add(null);
            fillSlot = textFills.size() - 1;
            textFill = fillSlot + 1;
        }
        Brush brush = brushOf(s).withTextFill(textFill);
        int link = link(el);
        if (link > 0) brush = brush.withLink(link);
        boolean[] owns = firstPseudos(el, s);
        pseudo(el, s, Pseudo.BEFORE, brush, ctx, s);
        walk(el, s, brush, ctx, s);
        pseudo(el, s, Pseudo.AFTER, brush, ctx, s);
        flush(ctx, s);
        if (owns[0]) firstLetter = null;
        if (owns[1]) firstLine = null;
        textFill = outerFill;
        Box node = boxOf(s);
        if (s.display == Display.TABLE_CELL) {
            // a cell centers (vertical-align) its content in the row height
            node.display = Box.Display.FLEX;
            node.flexDirection = FlexDirection.COLUMN;
            node.justifyContent = switch (s.verticalAlign.kind()) {
                case MIDDLE -> Align.CENTER;
                case BOTTOM -> Align.END;
                default -> Align.START;
            };
        }
        for (Box c : ctx.children) node.add(c);
        finish(node, s);
        if (fillSlot >= 0) {
            textFills.set(fillSlot, node);
            node.textFill = fillSlot + 1;
        }
        return node;
    }

    private void walk(Element parent, Style s, Brush brush, Ctx ctx, Style container) {
        if (walkDepth >= MAX_WALK_DEPTH) return;
        walkDepth++;
        try {
            walkChildren(parent, s, brush, ctx, container);
        } finally {
            walkDepth--;
        }
    }

    /**
     * Children of {@code parent} in an inline context: text and inline elements join {@code ctx.group},
     * block-level children close the group and become boxes.
     */
    private void walkChildren(Element parent, Style s, Brush brush, Ctx ctx, Style container) {
        // counters reset by these children live until the end of the parent (siblings see them)
        List<String> scope = new ArrayList<>();
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode t) {
                text(t.getWholeText(), s, brush, ctx);
                continue;
            }
            if (!(child instanceof Element el)) continue;
            Style cs = styleOf(el, s);
            if (cs.display == Display.NONE) continue;
            // sub / sup shift their inline descendants too
            if (s.verticalAlign.shifts() && cs.verticalAlign.kind() == VAlign.Kind.BASELINE && cs.display == Display.INLINE) cs.verticalAlign = s.verticalAlign;
            // children of a flex / grid box are its items, whatever their display (blockified)
            String name = el.normalName();
            boolean item = parent == ctx.owner && (container.display == Display.FLEX || container.display == Display.GRID);
            if (item && cs.display == Display.INLINE && !name.equals("br") && !name.equals("img") && !name.equals("svg")) cs.display = Display.BLOCK;
            applyCounters(el, cs, scope);
            boolean inFlow = !cs.positionAbsolute && cs.floating == Style.Float.NONE;
            // a float in a block of text: laid out on its own, the text of the block wraps around it
            if (!item && cs.floating != Style.Float.NONE && !cs.positionAbsolute && boxDepth < MAX_BOX_DEPTH) {
                floatPiece(el, cs, ctx);
                continue;
            }
            if (name.equals("br")) {
                ctx.group.add(new Piece.Break(cs, brush));
                continue;
            }
            if (name.equals("img") || name.equals("svg")) {
                if (item || !inFlow || cs.display != Display.INLINE) {
                    flush(ctx, container);
                    Box n = replaced(el, cs);
                    if (n != null) ctx.children.add(n);
                } else {
                    Box n = replaced(el, cs);
                    if (n != null) ctx.group.add(new Piece.Box(n, cs));
                }
                continue;
            }
            if (cs.display == Display.INLINE || cs.display == Display.CONTENTS) {
                Brush b = brush.withColor(cs.color);
                if (cs.display == Display.INLINE) b = b.withBackground(cs.background != null ? cs.background : brush.background());
                int link = link(el);
                if (link > 0) b = b.withLink(link);
                pseudo(el, cs, Pseudo.BEFORE, b, ctx, container);
                walk(el, cs, b, ctx, container);
                pseudo(el, cs, Pseudo.AFTER, b, ctx, container);
            } else if (boxDepth >= MAX_BOX_DEPTH) {
                walk(el, cs, brush, ctx, container);
            } else if (cs.inlineBlock && !item && inFlow) {
                boxDepth++;
                Box n = buildBox(el, cs);
                boxDepth--;
                if (n != null) ctx.group.add(new Piece.Box(n, cs));
            } else {
                flush(ctx, container);
                String page = boxDepth == 0 ? cs.page : null;
                boxDepth++;
                Box n = buildBox(el, cs);
                boxDepth--;
                if (n != null) {
                    ctx.children.add(n);
                    if (page != null) n.page = page;
                }
            }
        }
        endCounterScope(scope);
    }

    /** An {@code <a href>} to an external target: its 1-based index in the links. */
    private int link(Element el) {
        if (!el.normalName().equals("a")) return 0;
        String href = attr(el, "href");
        if (href == null) return 0;
        String h = href.toLowerCase(Locale.ROOT);
        if (!(h.startsWith("http://") || h.startsWith("https://") || h.startsWith("mailto:") || h.startsWith("tel:"))) return 0;
        links.add(href);
        return links.size();
    }

    /** A floated element as a box of its own, for the text of the block to wrap around. */
    private void floatPiece(Element el, Style cs, Ctx ctx) {
        boolean left = cs.floating == Style.Float.LEFT;
        boxDepth++;
        Box node = el.normalName().equals("img") || el.normalName().equals("svg") ? replaced(el, cs) : buildBox(el, cs);
        boxDepth--;
        if (node != null) {
            node.floating = false;
            ctx.group.add(new Piece.Float(node, left));
        }
    }

    private static boolean isPunct(int c) {
        return c < 128 && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0 || "«»“”‘’„".indexOf(c) >= 0;
    }

    /** A text node into the group; the first letter split off when a ::first-letter waits for it. */
    private void text(String t, Style s, Brush brush, Ctx ctx) {
        String text = applyTransform(t, s.transform);
        if (firstLetter == null || text.isBlank()) {
            ctx.group.add(new Piece.Text(text, s, brush));
            return;
        }
        Style fl = firstLetter;
        firstLetter = null;
        // the first letter, with the punctuation around it
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = start;
        boolean seenLetter = false;
        for (int i = start; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (seenLetter) break;
                seenLetter = true;
            } else if (!isPunct(c) || seenLetter && "'’.,;:!?»”".indexOf(c) < 0) {
                break;
            }
            i += Character.charCount(c);
            end = i;
        }
        if (start > 0) ctx.group.add(new Piece.Text(text.substring(0, start), s, brush));
        Brush b = new Brush(fl.color, fl.background != null ? fl.background : brush.background(), brush.link(), brush.textFill());
        if (fl.floating != Style.Float.NONE) {
            // a drop cap: the letter in its own floated box
            List<Decl> pending = firstLine;
            firstLine = null;
            List<Piece> pieces = new ArrayList<>();
            pieces.add(new Piece.Text(text.substring(start, end), fl, b));
            Box leaf = textLeaf(pieces, fl);
            firstLine = pending;
            if (leaf != null) {
                Box node = boxOf(fl);
                node.display = Box.Display.BLOCK;
                node.floating = false;
                node.add(leaf);
                finish(node, fl);
                ctx.group.add(new Piece.Float(node, fl.floating == Style.Float.LEFT));
            }
        } else {
            ctx.group.add(new Piece.Text(text.substring(start, end), fl, b));
        }
        ctx.group.add(new Piece.Text(text.substring(end), s, brush));
    }

    /**
     * CSS counters of an element, in order: counter-reset, counter-set, counter-increment
     * (lists reset {@code list-item}, list items increment it). New instances go into {@code scope}.
     */
    private void applyCounters(Element el, Style s, List<String> scope) {
        List<Style.Counter> resets = new ArrayList<>(s.counterReset == null ? List.of() : s.counterReset);
        String tag = el.normalName();
        if (List.of("ol", "ul", "menu", "dir").contains(tag) && resets.stream().noneMatch(r -> r.name().equals("list-item"))) {
            int start = 1;
            String st = attr(el, "start");
            if (st != null) {
                try {
                    start = Integer.parseInt(st.trim());
                } catch (NumberFormatException ignored) {
                    // default
                }
            }
            resets.add(new Style.Counter("list-item", start == Integer.MIN_VALUE ? start : start - 1));
        }
        for (Style.Counter r : resets) {
            counters.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r.value());
            scope.add(r.name());
        }
        List<Style.Counter> sets = new ArrayList<>(s.counterSet == null ? List.of() : s.counterSet);
        List<Style.Counter> incs = new ArrayList<>(s.counterIncrement == null ? List.of() : s.counterIncrement);
        if (s.display == Display.LIST_ITEM && sets.stream().noneMatch(c -> c.name().equals("list-item")) && incs.stream().noneMatch(c -> c.name().equals("list-item"))) {
            Integer v = null;
            String value = attr(el, "value");
            if (value != null) {
                try {
                    v = Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    // not a number: incremented
                }
            }
            if (v != null) sets.add(new Style.Counter("list-item", v));
            else incs.add(new Style.Counter("list-item", 1));
        }
        for (int pass = 0; pass < 2; pass++) {
            for (Style.Counter c : pass == 0 ? sets : incs) {
                List<Integer> stack = counters.computeIfAbsent(c.name(), k -> new ArrayList<>());
                if (stack.isEmpty()) {
                    stack.add(0);
                    scope.add(c.name());
                }
                int top = stack.get(stack.size() - 1);
                long v = pass == 0 ? c.value() : (long) top + c.value();
                stack.set(stack.size() - 1, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v)));
            }
        }
    }

    private void endCounterScope(List<String> scope) {
        for (String name : scope) {
            List<Integer> stack = counters.get(name);
            if (stack != null && !stack.isEmpty()) stack.remove(stack.size() - 1);
        }
    }

    private void flush(Ctx ctx, Style container) {
        List<Piece> group = ctx.group;
        ctx.group = new ArrayList<>();
        Box n = textLeaf(group, container);
        if (n != null) ctx.children.add(n);
    }

    /** One anonymous block of inline content; null when it is only collapsible whitespace. */
    private Box textLeaf(List<Piece> group, Style container) {
        boolean visible = false;
        for (Piece p : group) {
            if (p instanceof Piece.Text t) {
                WhiteSpace ws = t.style().whiteSpace;
                boolean pre = ws == WhiteSpace.PRE || ws == WhiteSpace.PRE_WRAP || ws == WhiteSpace.PRE_LINE;
                if (pre && !t.text().isEmpty() || !t.text().isBlank()) visible = true;
            } else {
                visible = true;
            }
        }
        if (!visible) return null;
        Paragraph para = Paragraph.build(group, container);
        TextLeaf.FirstLine fl = null;
        if (firstLine != null) {
            fl = new TextLeaf.FirstLine(List.copyOf(group), container, firstLine, rem);
            firstLine = null;
        }
        List<TextLeaf.FloatBox> floats = new ArrayList<>();
        for (Piece p : group) if (p instanceof Piece.Float f) floats.add(new TextLeaf.FloatBox((Box) f.node(), f.left()));
        Box leaf = new Box();
        leaf.text = new TextLeaf(para, floats, container.textAlign, fl);
        for (TextLeaf.FloatBox f : floats) f.node.parent = leaf;
        for (Box ib : leaf.text.inlineBoxes()) ib.parent = leaf;
        return leaf;
    }

    /** {@code <img>} and inline {@code <svg>}: replaced boxes with a natural size. */
    private Box replaced(Element el, Style cs) {
        Decode.Decoded d;
        if (el.normalName().equals("svg")) {
            d = Decode.svg(Svg.markup(el));
        } else {
            String src = el.attr("src");
            byte[] data = Images.load(src, policy);
            if (data == null) {
                System.err.println("pdfj: image blocked or not found: " + (src.length() > 80 ? src.substring(0, 80) : src));
                return null;
            }
            d = Decode.image(data);
        }
        if (d == null) return null;
        Style s = cs.copy();
        // width / height attributes, CSS wins (same as browsers)
        String[] names = {"width", "height"};
        for (int i = 0; i < 2; i++) {
            if (s.size[i].isAuto()) {
                String v = attr(el, names[i]);
                Len l = v == null ? null : htmlLen(v, s.fontSize, rem);
                if (l != null) s.size[i] = l;
            }
        }
        if (s.maxSize[0].isAuto()) s.maxSize[0] = Len.pct(100);
        Box node = boxOf(s);
        node.display = Box.Display.BLOCK;
        node.replaced = true;
        if (d.width() > 0 && d.height() > 0) node.aspectRatio = d.width() / d.height();
        ObjectFit fit = el.normalName().equals("svg") ? ObjectFit.CONTAIN : s.objectFit;
        node.image = new ImageLeaf(d.kind(), d.width(), d.height(), fit, s.radius.clone());
        finish(node, s);
        return node;
    }

    private record Cell(Element el, Style style, int r, int c, int rowspan, int colspan) {}

    /** Tables become grids: one track per column, cells placed by row / colspan / rowspan. */
    private Box buildTable(Element el, Style s) {
        List<Element> rowEls = new ArrayList<>();
        List<Style> rowStyles = new ArrayList<>();
        List<Box> captions = new ArrayList<>();
        int[] hf = tableRows(el, s, rowEls, rowStyles, captions);
        int headerRows = hf[0], footerRows = hf[1];
        List<Box> headerCells = new ArrayList<>(), footerCells = new ArrayList<>();
        List<String> scope = new ArrayList<>();
        List<Box> children = new ArrayList<>();
        List<boolean[]> taken = new ArrayList<>();
        int ncols = 1;
        int firstRow = captions.isEmpty() ? 0 : 1;
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < rowEls.size(); r++) {
            int c = 0;
            for (Element cell : rowEls.get(r).children()) {
                Style cs = styleOf(cell, rowStyles.get(r));
                if (cs.display != Display.TABLE_CELL) continue;
                if (cs.background == null) cs.background = rowStyles.get(r).background;
                int colspan = span(cell, "colspan"), rowspan = span(cell, "rowspan");
                while (r < taken.size() && c < taken.get(r).length && taken.get(r)[c]) c++;
                for (int rr = r; rr < r + rowspan; rr++) {
                    while (taken.size() <= rr) taken.add(new boolean[0]);
                    if (taken.get(rr).length < c + colspan) taken.set(rr, java.util.Arrays.copyOf(taken.get(rr), c + colspan));
                    for (int cc = c; cc < c + colspan; cc++) taken.get(rr)[cc] = true;
                }
                ncols = Math.max(ncols, c + colspan);
                cells.add(new Cell(cell, cs, r, c, rowspan, colspan));
                c += colspan;
            }
        }
        int nrows = rowEls.size();
        // column widths: <col> / <colgroup>, else the widest single-column cell's; the cell then fills its column
        List<Len> widths = colWidths(el, s);
        for (Cell cell : cells) {
            Style cs = cell.style;
            if (cs.size[0].isAuto()) continue;
            if (cell.colspan == 1) {
                while (widths.size() <= cell.c) widths.add(Len.AUTO);
                Len cur = widths.get(cell.c), w = cs.size[0];
                Len merged;
                if (cur.isPx() && w.isPx()) merged = Len.px(Math.max(cur.px(), w.px()));
                else if (cur.isPct() && w.isPct()) merged = Len.pct(Math.max(cur.pct(), w.pct()));
                else if (cur.isAuto() && (w.isPx() || w.isPct())) merged = w;
                else merged = cur;
                widths.set(cell.c, merged);
            }
            cs.size[0] = Len.AUTO;
        }
        int lastRow = -1;
        for (Cell cell : cells) {
            // counters of rows (once per row) and cells, in document order
            if (lastRow != cell.r) {
                lastRow = cell.r;
                applyCounters(rowEls.get(cell.r), rowStyles.get(cell.r), scope);
            }
            Style cs = cell.style;
            applyCounters(cell.el, cs, scope);
            if (s.borderCollapse) {
                // collapse: neighbours overlap their shared border
                if (cell.c > 0) cs.margin[3] = Len.px(-cs.borderWidth[3]);
                if (cell.r > 0) cs.margin[0] = Len.px(-cs.borderWidth[0]);
            }
            Box n = buildBox(cell.el, cs);
            if (n == null) continue;
            n.gridRow = new GridTemplate.Placement(GridTemplate.Line.line(firstRow + cell.r + 1), GridTemplate.Line.span(cell.rowspan));
            n.gridColumn = new GridTemplate.Placement(GridTemplate.Line.line(cell.c + 1), GridTemplate.Line.span(cell.colspan));
            n.cell = true;
            children.add(n);
            if (cell.r < headerRows) headerCells.add(n);
            if (cell.r >= nrows - footerRows) footerCells.add(n);
        }
        for (Box cap : captions) {
            cap.gridRow = new GridTemplate.Placement(GridTemplate.Line.line(1), GridTemplate.Line.span(1));
            cap.gridColumn = new GridTemplate.Placement(GridTemplate.Line.line(1), GridTemplate.Line.line(-1));
            children.add(cap);
        }
        Box node = boxOf(s);
        node.display = Box.Display.GRID;
        ncols = Math.max(ncols, widths.size());
        // a given width is the column's preferred width: it still grows to fit its content. In a table
        // with a width, the room left goes (like Chromium) to the auto columns in proportion to their
        // max-content width, else to the fixed columns in proportion to their width, else to the percentages
        boolean wide = !s.size[0].isAuto();
        List<Integer> autoCols = new ArrayList<>();
        boolean anyFixed = false;
        for (int c = 0; c < ncols; c++) {
            Len g = c < widths.size() ? widths.get(c) : Len.AUTO;
            if (!(g.isPx() || g.isPct())) autoCols.add(c);
            if (g.isPx()) anyFixed = true;
        }
        List<GridTemplate.Item> tracks = new ArrayList<>();
        for (int c = 0; c < ncols; c++) {
            Len g = c < widths.size() ? widths.get(c) : Len.AUTO;
            GridTemplate.Track t;
            if (g.isPx() && wide && autoCols.isEmpty()) t = GridTemplate.Track.autoTo(GridTemplate.MaxKind.FR, null, Math.max(g.px(), 0.01));
            else if (g.isPct() && wide && autoCols.isEmpty() && !anyFixed) t = GridTemplate.Track.autoTo(GridTemplate.MaxKind.FR, null, Math.max(g.pct(), 0.01));
            else if (g.isPx() || g.isPct()) t = GridTemplate.Track.autoTo(GridTemplate.MaxKind.FIXED, g, 0);
            else t = GridTemplate.Track.AUTO;
            tracks.add(new GridTemplate.Item(List.of(t), 1));
        }
        node.columns = tracks;
        node.rows = List.of();
        double spacing = s.borderCollapse ? 0 : s.borderSpacing;
        node.gapRow = Len.px(spacing);
        node.gapColumn = Len.px(spacing);
        // shrink to content like a browser table, unless a width is given
        if (!wide) {
            node.justifyContent = Align.START;
            node.shrinkToFit = true;
        }
        for (Box c : children) node.add(c);
        if (wide && !autoCols.isEmpty()) node.tableAutoColumns = autoCols;
        finish(node, s);
        endCounterScope(scope);
        if (!headerCells.isEmpty()) node.headCells = headerCells;
        if (!footerCells.isEmpty()) node.footCells = footerCells;
        return node;
    }

    private static int span(Element cell, String a) {
        String v = attr(cell, a);
        if (v == null) return 1;
        try {
            return Math.max(1, Math.min(1000, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Widths of a table's columns from its {@code <col>} / {@code <colgroup>} ({@code width} attribute or CSS), auto when not given. */
    private List<Len> colWidths(Element el, Style s) {
        List<Len> out = new ArrayList<>();
        for (Element child : el.children()) {
            switch (child.normalName()) {
                case "col" -> {
                    Len w = colWidth(child, s);
                    for (int i = 0; i < span(child, "span"); i++) out.add(w);
                }
                case "colgroup" -> {
                    Style gs = styleOf(child, s);
                    Len gw = colWidth(child, s);
                    List<Element> cols = new ArrayList<>();
                    for (Element c : child.children()) if (c.normalName().equals("col")) cols.add(c);
                    if (cols.isEmpty()) for (int i = 0; i < span(child, "span"); i++) out.add(gw);
                    for (Element col : cols) {
                        Len w = colWidth(col, gs);
                        if (w.isAuto()) w = gw;
                        for (int i = 0; i < span(col, "span"); i++) out.add(w);
                    }
                }
                default -> {}
            }
        }
        return out;
    }

    private Len colWidth(Element e, Style parent) {
        Style cs = styleOf(e, parent);
        if (!cs.size[0].isAuto()) return cs.size[0];
        String w = attr(e, "width");
        Len l = w == null ? null : htmlLen(w, cs.fontSize, rem);
        return l == null ? Len.AUTO : l;
    }

    /** Rows of a table (thead first, tfoot last); returns the number of {@code <thead>} and {@code <tfoot>} rows. */
    private int[] tableRows(Element el, Style s, List<Element> rows, List<Style> styles, List<Box> captions) {
        List<List<Object[]>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Element child : el.children()) {
            Style cs = styleOf(child, s);
            switch (cs.display) {
                case TABLE_ROW -> groups.get(1).add(new Object[] {child, cs});
                case TABLE_ROW_GROUP -> {
                    int g = switch (child.normalName()) {
                        case "thead" -> 0;
                        case "tfoot" -> 2;
                        default -> 1;
                    };
                    for (Element tr : child.children()) {
                        Style ts = styleOf(tr, cs);
                        if (ts.display == Display.TABLE_ROW) {
                            if (ts.background == null) ts.background = cs.background;
                            groups.get(g).add(new Object[] {tr, ts});
                        }
                    }
                }
                case TABLE_CAPTION -> {
                    Style caption = cs.copy();
                    caption.display = Display.BLOCK;
                    Box n = buildBox(child, caption);
                    if (n != null) captions.add(n);
                }
                default -> {}
            }
        }
        for (List<Object[]> g : groups) {
            for (Object[] r : g) {
                rows.add((Element) r[0]);
                styles.add((Style) r[1]);
            }
        }
        return new int[] {groups.get(0).size(), groups.get(2).size()};
    }
}
