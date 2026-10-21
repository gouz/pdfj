package io.github.gouz.pdfj;

import io.github.gouz.pdfj.css.Css;
import io.github.gouz.pdfj.css.Css.Decl;
import io.github.gouz.pdfj.css.Css.Media;
import io.github.gouz.pdfj.css.Css.PageRule;
import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.layout.Box;
import io.github.gouz.pdfj.layout.Builder;
import io.github.gouz.pdfj.layout.Deco;
import io.github.gouz.pdfj.layout.Layout;
import io.github.gouz.pdfj.layout.Tree;
import io.github.gouz.pdfj.net.Remote;
import io.github.gouz.pdfj.render.Pages;
import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Values;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** HTML/CSS -> PDF without a browser: jsoup parses, a small cascade computes styles, the layout engine lays out, PDFBox writes. */
public final class Pdf {
    private Pdf() {}

    /** First, left (even) or right (odd) page: which {@code @page} rules apply. The first page is a right page. */
    enum PageKind { FIRST, LEFT, RIGHT }

    static PageKind pageKind(int index) {
        if (index == 0) return PageKind.FIRST;
        return index % 2 == 1 ? PageKind.LEFT : PageKind.RIGHT;
    }

    /** What {@code @page} rules set: size, orientation, margins (each null when not set). */
    private static final class PageProps {
        double[] size;
        Boolean landscape;
        Double[] margin = new Double[4];
    }

    private static Double px(String v, Options o) {
        Len l = Values.len(v, o.fontSize(), o.fontSize());
        return l == null ? null : l.pxValue();
    }

    private static PageProps pageProps(Options o, List<Decl> decls) {
        PageProps p = new PageProps();
        String[] sides = {"margin-top", "margin-right", "margin-bottom", "margin-left"};
        for (Decl d : decls) {
            String v = d.value().toLowerCase(Locale.ROOT);
            switch (d.prop()) {
                case "size" -> {
                    List<Double> dims = new ArrayList<>();
                    for (String w : v.trim().split("\\s+")) {
                        Double x = px(w, o);
                        if (x != null) dims.add(x);
                    }
                    if (dims.size() == 1) p.size = new double[] {dims.get(0), dims.get(0)};
                    else if (dims.size() == 2) p.size = new double[] {dims.get(0), dims.get(1)};
                    for (String w : v.trim().split("\\s+")) {
                        switch (w) {
                            case "landscape" -> p.landscape = true;
                            case "portrait" -> p.landscape = false;
                            default -> {
                                double[] s = Options.pageSize(w);
                                if (s != null && (!w.matches(".*\\d.*") || w.length() <= 3)) p.size = s;
                            }
                        }
                    }
                }
                case "margin" -> {
                    List<Double> m = new ArrayList<>();
                    for (String w : v.trim().split("\\s+")) {
                        Double x = px(w, o);
                        if (x != null) m.add(x);
                    }
                    Double[] four = switch (m.size()) {
                        case 1 -> new Double[] {m.get(0), m.get(0), m.get(0), m.get(0)};
                        case 2 -> new Double[] {m.get(0), m.get(1), m.get(0), m.get(1)};
                        case 3 -> new Double[] {m.get(0), m.get(1), m.get(2), m.get(1)};
                        case 4 -> new Double[] {m.get(0), m.get(1), m.get(2), m.get(3)};
                        default -> null;
                    };
                    if (four != null) p.margin = four;
                }
                case "margin-top", "margin-right", "margin-bottom", "margin-left" -> {
                    int i = Arrays.asList(sides).indexOf(d.prop());
                    Double x = px(v, o);
                    if (x != null) p.margin[i] = x;
                }
                default -> {}
            }
        }
        return p;
    }

    /**
     * The {@code @page} rules of a kind of page with a {@code page} name, least specific first: {@code @page},
     * {@code @page name}, then {@code :left} / {@code :right}, then {@code :first} (each unnamed, then named).
     */
    static List<PageRule> rulesFor(List<PageRule> pages, PageKind kind, String name) {
        List<Object[]> rules = new ArrayList<>();
        for (PageRule r : pages) {
            int named;
            if (r.name == null) named = 0;
            else if (r.name.equals(name)) named = 1;
            else continue;
            int pseudo;
            if (r.pseudo == null) pseudo = 0;
            else if (r.pseudo.equals(":left") && kind == PageKind.LEFT || r.pseudo.equals(":right") && (kind == PageKind.RIGHT || kind == PageKind.FIRST)) pseudo = 2;
            else if (r.pseudo.equals(":first") && kind == PageKind.FIRST) pseudo = 4;
            else continue;
            rules.add(new Object[] {pseudo + named, r});
        }
        rules.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
        List<PageRule> out = new ArrayList<>();
        for (Object[] r : rules) out.add((PageRule) r[1]);
        return out;
    }

    private static List<Decl> decls(List<PageRule> rules) {
        List<Decl> out = new ArrayList<>();
        for (PageRule r : rules) out.addAll(r.decls);
        return out;
    }

    /** page size (landscape applied), content width, margins of each kind of page (first, left, right) */
    record Setup(double pw, double ph, double cw, double[][] margins) {}

    /**
     * Page size, content width, and the margins of each kind of page: the request's options, else the
     * document's {@code @page} rules, else A4 / 40pt. The column has one width: a page whose side margins
     * add up differently keeps its left margin and the common width.
     */
    static Setup pageSetup(Options o, List<PageRule> pages, String name) {
        List<PageRule> baseRules = new ArrayList<>();
        for (PageRule r : rulesFor(pages, PageKind.RIGHT, name)) if (r.pseudo == null) baseRules.add(r);
        PageProps base = pageProps(o, decls(baseRules));
        // a named page's own size wins over the request's (that is the point of naming it)
        boolean namedSize = false;
        if (name != null) {
            for (PageRule p : pages) if (name.equals(p.name)) for (Decl d : p.decls) namedSize |= d.prop().equals("size");
        }
        double[] wh = namedSize ? base.size : o.size() != null ? o.size() : base.size;
        if (wh == null) wh = Options.pageSize("A4");
        Boolean landscape = namedSize ? base.landscape : o.landscape() != null ? o.landscape() : base.landscape;
        double pw = wh[0], ph = wh[1];
        if (landscape != null && landscape) {
            pw = Math.max(wh[0], wh[1]);
            ph = Math.min(wh[0], wh[1]);
        }
        double[] baseM = new double[4];
        for (int i = 0; i < 4; i++) baseM[i] = o.margin() != null ? o.margin()[i] : base.margin[i] != null ? base.margin[i] : 40.0;
        double cw = Math.max(1, pw - baseM[1] - baseM[3]);
        double[][] margins = new double[3][];
        PageKind[] kinds = {PageKind.FIRST, PageKind.LEFT, PageKind.RIGHT};
        for (int k = 0; k < 3; k++) {
            double[] m = new double[4];
            if (o.margin() != null) {
                m = o.margin().clone();
            } else {
                PageProps p = pageProps(o, decls(rulesFor(pages, kinds[k], name)));
                for (int i = 0; i < 4; i++) m[i] = p.margin[i] != null ? p.margin[i] : baseM[i];
            }
            m[1] = pw - m[3] - cw;
            margins[k] = m;
        }
        return new Setup(pw, ph, cw, margins);
    }

    static int kindIndex(PageKind k) {
        return k.ordinal();
    }

    /** a margin box: its HTML and rectangle (x, y, w, h) */
    public record Band(String html, double x, double y, double w, double h) {}

    private static String escape(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * The {@code @page} margin boxes of a kind of page as HTML in their rectangle: edges (left / center /
     * right side by side), corners and sides. {@code counter(page)} / {@code counter(pages)} become
     * {@code {page}} / {@code {pages}}. The request's header / footer replace the top / bottom edges.
     */
    static List<Band> marginBands(Options o, List<PageRule> pages, PageKind kind, String name, double pw, double ph, double[] m) {
        double mt = m[0], mr = m[1], mb = m[2], ml = m[3];
        Map<String, List<Decl>> boxes = new HashMap<>();
        for (PageRule rule : rulesFor(pages, kind, name)) for (var b : rule.boxes) boxes.put(b.getKey(), b.getValue());
        List<Band> out = new ArrayList<>();
        String[][] edges = {{"top", o.header()}, {"bottom", o.footer()}};
        for (int e = 0; e < 2; e++) {
            String edge = edges[e][0];
            double y = e == 0 ? 0 : ph - mb, h = e == 0 ? mt : mb;
            String html = edges[e][1];
            if (html == null) {
                List<String> cells = new ArrayList<>();
                boolean any = false;
                for (String slot : new String[] {"left", "center", "right"}) {
                    String c = cell(boxes.get(edge + "-" + slot), slot, "flex: 1;");
                    any |= c != null;
                    cells.add(c == null ? "<div style=\"flex: 1\"></div>" : c);
                }
                if (any) html = "<div style=\"display: flex; align-items: center\">" + String.join("", cells) + "</div>";
            }
            if (html != null) out.add(new Band(html, ml, y, pw - ml - mr, h));
            for (int s = 0; s < 2; s++) {
                String side = s == 0 ? "left" : "right";
                String c = cell(boxes.get(edge + "-" + side + "-corner"), "center", "");
                if (c != null) out.add(new Band(c, s == 0 ? 0 : pw - mr, y, s == 0 ? ml : mr, h));
            }
        }
        double ch = ph - mt - mb;
        for (int s = 0; s < 2; s++) {
            String side = s == 0 ? "left" : "right";
            String[] parts = {"top", "middle", "bottom"};
            for (int i = 0; i < 3; i++) {
                String c = cell(boxes.get(side + "-" + parts[i]), "center", "");
                if (c != null) out.add(new Band(c, s == 0 ? 0 : pw - mr, mt + ch * i / 3, s == 0 ? ml : mr, ch / 3));
            }
        }
        return out;
    }

    /** a box's content and style, or null when it has no content */
    private static String cell(List<Decl> decls, String align, String extra) {
        if (decls == null) return null;
        String content = null;
        for (int i = decls.size() - 1; i >= 0; i--) {
            if (decls.get(i).prop().equals("content")) {
                content = Values.content(decls.get(i).value(), a -> null, (counter, sep, style) -> switch (counter) {
                    case "page" -> "{page}";
                    case "pages" -> "{pages}";
                    default -> "";
                });
                break;
            }
        }
        if (content == null) return null;
        StringBuilder css = new StringBuilder();
        for (Decl d : decls) if (!d.prop().equals("content")) css.append(d.prop()).append(": ").append(d.value().replace("\"", "&quot;")).append(';');
        return "<div style=\"text-align: " + align + "; " + extra + " " + css + "\">" + escape(content) + "</div>";
    }

    /** A laid out header / footer for one page. It can use the document's @font-face fonts. */
    public static Tree band(String html, int page, int pages, Options o, double width, Media media, List<FontFace> faces) {
        String h = html.replace("{page}", Integer.toString(page)).replace("{pages}", Integer.toString(pages));
        Document doc = Jsoup.parse(h);
        Css.Sheet sheet = Builder.stylesheet(doc, media, o.images());
        List<FontFace> all = new ArrayList<>(faces);
        all.addAll(Builder.loadFontFaces(sheet.fontFaces, o.images()));
        Fonts.DocFonts df = new Fonts.DocFonts(all);
        Tree t = Builder.build(doc, sheet, df, o.images(), o.font(), o.fontSize(), width);
        Fonts.withDocFonts(df, () -> {
            Layout.layoutRoot(t.root, width);
            return null;
        });
        return t;
    }

    /** a section of named pages ({@code page: wide}), laid out at the width of its pages */
    record Section(Box root, String name, Setup setup) {}

    /** Render {@code html} to PDF bytes. */
    public static byte[] htmlToPdf(String html, Options o) {
        try {
            return render(html, o);
        } finally {
            Fonts.endDocument();
            Remote.endDocument();
            Builder.endDocument();
        }
    }

    private static byte[] render(String html, Options o) {
        Document doc = Jsoup.parse(html);
        if (o.images().remote() != null) Remote.prefetch(Builder.remoteImages(doc), o.images().remote());
        // @page first (it can sit in @media print), then the sheets for the real page area
        Media provisional = new Media(515.28, 761.89);
        Css.Sheet sheet = Builder.stylesheet(doc, provisional, o.images());
        Setup base = pageSetup(o, sheet.pages, null);
        double cw = base.cw, ch = Math.max(1, base.ph - base.margins[2][0] - base.margins[2][2]);
        Media media = new Media(cw, ch);
        Values.setViewport(cw, ch);
        if (Math.abs(cw - provisional.width()) >= 0.5 || Math.abs(ch - provisional.height()) >= 0.5) sheet = Builder.stylesheet(doc, media, o.images());
        List<FontFace> faces = Builder.loadFontFaces(sheet.fontFaces, o.images());
        Fonts.DocFonts df = new Fonts.DocFonts(faces);
        Tree tree = Builder.build(doc, sheet, df, o.images(), o.font(), o.fontSize(), cw);
        List<Section> sections = new ArrayList<>();
        Css.Sheet sh = sheet;
        for (Tree.Section s : tree.sections()) {
            Setup setup = pageSetup(o, sh.pages, s.name());
            Fonts.withDocFonts(df, () -> {
                Tree.layout(s.root(), setup.cw);
                return null;
            });
            sections.add(new Section(s.root(), s.name(), setup));
        }
        // the root's background goes to the whole page (the canvas), its borders stay on the root box
        Deco canvas = null;
        for (Section sec : sections) {
            Deco d = sec.root.deco;
            if (d == null) continue;
            Deco c = new Deco();
            c.background = d.background;
            c.layers = new ArrayList<>(d.layers);
            c.colorClip = d.colorClip;
            d.background = null;
            d.layers = new ArrayList<>();
            if (canvas == null && (c.background != null || !c.layers.isEmpty())) canvas = c;
        }
        String title = o.title() != null ? o.title() : htmlTitle(html);
        List<Pages.Section> secs = sections.stream().map(s -> new Pages.Section(s.root, s.name, s.setup.pw, s.setup.ph, s.setup.margins)).toList();
        return Pages.write(o, title, tree, secs, canvas, sh.pages, media, faces);
    }

    /** the page kind index (0 first, 1 left, 2 right) of a page */
    public static int kindOf(int pageIndex) {
        return kindIndex(pageKind(pageIndex));
    }

    /** the margin boxes of a page (cached per section and kind by the caller) */
    public static List<Band> bandsFor(Options o, List<PageRule> pages, int pageIndex, String name, double pw, double ph, double[] margins) {
        return marginBands(o, pages, pageKind(pageIndex), name, pw, ph, margins);
    }

    static String htmlTitle(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("<title");
        if (start < 0) return null;
        int open = lower.indexOf('>', start);
        if (open < 0) return null;
        int close = lower.indexOf("</title", open);
        if (close < 0) return null;
        String t = html.substring(open + 1, close).trim();
        return t.isEmpty() ? null : Objects.requireNonNull(Jsoup.parse(t).text());
    }
}
