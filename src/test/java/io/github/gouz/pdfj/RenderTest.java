package io.github.gouz.pdfj;

import io.github.gouz.pdfj.PdfText.Word;
import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.font.Woff;
import io.github.gouz.pdfj.net.ImagePolicy;
import io.github.gouz.pdfj.net.Remote;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import static io.github.gouz.pdfj.PdfText.pages;
import static io.github.gouz.pdfj.PdfText.pagesText;
import static io.github.gouz.pdfj.PdfText.pdf;
import static io.github.gouz.pdfj.PdfText.text;
import static io.github.gouz.pdfj.PdfText.word;
import static io.github.gouz.pdfj.PdfText.words;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end: HTML in, PDF bytes out (same cases as pdfrs's tests/render.rs). */
class RenderTest {
    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static String flat(String text) {
        return String.join(" ", text.trim().split("\\s+"));
    }

    private static long count(String text, String what) {
        long n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }

    @Test
    void flexHeaderFooterMetadata() throws IOException {
        Options o = Options.defaults().withSize(Options.pageSize("A5")).withLandscape(true).withMargin(new double[] {60, 40, 40, 40})
            .withFooter("<p>{page}/{pages}</p>").withTitle("T");
        byte[] out = pdf("<div style=\"display:flex;justify-content:space-between\"><p>a</p><p>b</p></div>", o);
        try (var d = org.apache.pdfbox.Loader.loadPDF(out)) {
            assertEquals("T", d.getDocumentInformation().getTitle());
        }
        assertTrue(text(out).contains("1/1"));
    }

    @Test
    void whatCrashedOrHungPdfjsRenders() {
        // font stacks, `normal` spacings, a min-height taller than the page, unknown fonts
        String html = "<style>p { font-family: \"Segoe UI\", sans-serif; letter-spacing: normal }</style>"
            + "<div style=\"display:flex;gap:normal;min-height:2000px\"><p style=\"font-family:Foo\">a</p></div><p>gap: normal</p>";
        assertTrue(pages(pdf(html, Options.defaults().withSize(new double[] {400, 300}))) >= 2);
    }

    @Test
    void subsetFontsAreReusedAcrossDocuments() {
        // the second "hello" takes its subset font from the cache: the same PDF but for its /ID
        byte[] a = pdf("<p>hello</p>"), b = pdf("<p>world</p>"), c = pdf("<p>hello</p>");
        String noId = "/ID \\[<[0-9A-F]+> <[0-9A-F]+>\\]";
        assertEquals(new String(a, StandardCharsets.ISO_8859_1).replaceAll(noId, ""), new String(c, StandardCharsets.ISO_8859_1).replaceAll(noId, ""));
        assertEquals("hello", text(c).trim());
        assertEquals("world", text(b).trim());
    }

    @Test
    void longContentPaginates() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 200; i++) rows.append("<tr><td>row ").append(i).append("</td><td>").append(i).append("</td></tr>");
        int n = pages(pdf("<table>" + rows + "</table><p style=\"page-break-before:always\">end</p>"));
        assertTrue(n >= 4, "got " + n + " pages");
    }

    @Test
    void emptyDocumentHasOnePage() {
        assertEquals(1, pages(pdf("")));
    }

    @Test
    void hostileInputDoesNotCrash() throws Exception {
        // on a thread like the server's render threads (16 MB of stack)
        Throwable[] failure = {null};
        Thread t = new Thread(null, () -> {
            try {
                for (String html : List.of(
                    "<div style='width:-10px;height:1e30px;margin:-1e9px'>x</div>",
                    "<table><tr><td colspan=999999 rowspan=0>x</td></tr></table>",
                    "<ol start=-5><li>a<li value=abc>b</ol>",
                    "<img src='data:image/png;base64,!!!'><img><svg><rect/></svg><svg width=0 height=0></svg>",
                    "<p style='font-size:0;line-height:-3'>tiny</p><p style='font-size:10000px'>huge</p>",
                    "<div>".repeat(5000),
                    "<span>".repeat(5000),
                    "<ul><li>".repeat(2000),
                    "<span style='display:inline-block;padding:1px'>x".repeat(2000),
                    "<pre>\t\ttabs\n\n\nlines</pre><p>&nbsp;&amp;&lt;&#x1F600;</p>")) {
                    pdf(html);
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "render-test", 16L * 1024 * 1024);
        t.start();
        t.join();
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    @Test
    void pageRuleSetsSizeAndMarginsUnlessTheRequestDoes() {
        String html = "<style>@page { size: A5 landscape; margin: 10mm } @media print { p { color: red } }</style><p>x</p>";
        assertEquals(List.of("595.28 419.53"), PdfText.mediaBoxes(pdf(html)), "A5 landscape expected");
        assertEquals(List.of("595.28 841.89"), PdfText.mediaBoxes(pdf(html, Options.defaults().withSize(Options.pageSize("A4")).withLandscape(false))), "the request's A4 portrait wins");
    }

    @Test
    void pseudoElementsAndVariablesRenderText() {
        String html = """
            <style>
              :root { --accent: #c00; --label: "Total"; }
              .price::before { content: var(--label) " : "; color: var(--accent) }
              .price::after { content: " €" }
              a[href]::after { content: " (" attr(href) ")" }
            </style>
            <p class="price">42</p><p><a href="https://x.test">lien</a> <q>cité</q></p>""";
        String t = text(pdf(html));
        assertTrue(t.contains("Total : 42 €"), t);
        assertTrue(t.contains("lien (https://x.test)"), t);
        assertTrue(t.contains("“cité”"), t);
    }

    @Test
    void calcWidthsUseTheContainingBlock() {
        // two boxes of calc(50% - 10px) + 20px gap fit on one line
        String html = "<div style=\"display:flex;gap:20px;width:400px\"><div style=\"width:calc(50% - 10px);flex-shrink:0\">aaa bbb ccc ddd</div>"
            + "<div style=\"width:calc(50% - 10px);flex-shrink:0\">eee</div></div>";
        String first = text(pdf(html)).lines().findFirst().orElse("");
        assertTrue(first.contains("aaa bbb ccc ddd"), first);
    }

    @Test
    void fontFaceIsScopedToItsDocument() {
        byte[] ttf = PdfText.liberationSerif();
        if (ttf == null) return;
        String b64 = Base64.getEncoder().encodeToString(ttf);
        String html = "<style>@font-face { font-family: 'Maison'; src: url(data:font/ttf;base64," + b64 + ") format('truetype') } p { font-family: Maison }</style><p>x</p>";
        assertTrue(PdfText.fontNames(pdf(html)).stream().anyMatch(n -> n.contains("LiberationSerif")), "the @font-face font is embedded");
        // the next document does not see it
        assertEquals(null, Fonts.resolveStack("Maison"));
        assertTrue(PdfText.fontNames(pdf("<p style='font-family: Maison'>x</p>")).stream().noneMatch(n -> n.contains("LiberationSerif")));
    }

    @Test
    void lot1FeaturesDoNotCrash() {
        String html = """
            <style>
            .g { background: linear-gradient(135deg, #f00, #00f 70%); height: 40px; border-radius: 8px }
            .r { background-image: radial-gradient(circle at 30% 40%, white, black); height: 40px }
            .rep { background: repeating-linear-gradient(90deg, #eee 0 10px, #ccc 10px 20px); height: 20px }
            .t { transform: rotate(-5deg) scale(1.1); transform-origin: left top; width: 100px; background: #def }
            .x::before { content: ""; display: block; height: 4px; background: red }
            @media (max-width: 100px) { body { color: red } }
            </style>
            <div class="g"></div><div class="r"></div><div class="rep"></div><div class="t">tourné</div><div class="x">x</div>
            <div dir="rtl"><p>שלום עולם and English</p><ul><li>عربي</li></ul></div>""";
        pdf(html);
    }

    private static double lineOf(List<Word> w, String word) {
        return word(w, word).y();
    }

    @Test
    void inlineBlocksAndImagesStayInTheLine() {
        String html = "<p>avant <span style=\"display:inline-block;padding:2px 6px;background:#eee\">badge</span> milieu <img src=\"" + PNG + "\" width=\"10\" height=\"10\"> apres</p>"
            + "<div style=\"display:flex\"><span style=\"display:inline-block\">item</span><img src=\"" + PNG + "\" width=\"5\"></div>";
        List<Word> w = words(pdf(html));
        double y = lineOf(w, "avant");
        for (String word : List.of("badge", "milieu", "apres")) assertTrue(Math.abs(lineOf(w, word) - y) < 4, word + " left the line: " + w);
        Word badge = word(w, "badge"), milieu = word(w, "milieu");
        assertTrue(badge.xMax() + 6 <= milieu.x() + 0.5, "the badge's padding pushes the next word");
    }

    @Test
    void wideInlineBlockShrinksToTheLine() {
        String html = "<p>a <span style=\"display:inline-block;padding:4px;border:1px solid\">" + "mot ".repeat(200) + "</span> b</p>";
        List<Word> w = words(pdf(html));
        double right = w.stream().mapToDouble(Word::xMax).max().orElse(0);
        assertTrue(right <= 595.28 - 40 + 0.5, "text overflows the page: " + right);
        assertEquals(200, w.stream().filter(x -> x.text().equals("mot")).count());
    }

    @Test
    void theadRepeatsOnEveryPageOfTheTable() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 120; i++) rows.append("<tr><td>r").append(i).append("</td><td>").append(i).append("</td></tr>");
        byte[] out = pdf("<p>avant</p><table><thead><tr><th>Entete</th><th>Qte</th></tr></thead><tbody>" + rows + "</tbody></table><p>apres</p>");
        int n = pages(out);
        assertTrue(n >= 3);
        String t = text(out);
        assertEquals(n, count(t, "Entete"), "one header per page:\n" + t);
        // every row once, none hidden under the header
        List<String> ws = List.of(t.split("\\s+"));
        for (int i = 0; i < 120; i++) {
            String row = "r" + i;
            assertEquals(1, ws.stream().filter(x -> x.equals(row)).count(), "row " + row);
        }
    }

    @Test
    void shadowsAndBackgroundImagesRender() {
        String html = "<div style=\"box-shadow: 0 4px 12px rgba(0,0,0,.3), 2px 2px 0 red, inset 0 0 4px blue; border-radius: 6px; height: 40px\">a</div>"
            + "<div style=\"background: url(" + PNG + ") repeat; background-size: 4px; height: 3000pt; border-radius: 10px\">tall, tiled</div>"
            + "<div style=\"background: url(" + PNG + ") center / cover no-repeat; width: 50%; height: 60px\"></div>"
            + "<div style=\"background-image: url(https://x.test/a.png); height: 10px\"></div>"
            + "<div style=\"box-shadow: 0 0 400px 50px black; width: 5000pt; height: 5000pt\"></div>";
        long t = System.nanoTime();
        byte[] out = pdf(html);
        assertTrue(pages(out) >= 5);
        assertTrue((System.nanoTime() - t) / 1e9 < 20, "too slow");
    }

    @Test
    void countersMarkersAndFirstLetter() {
        String html = """
            <style>
              body { counter-reset: ch }
              h2 { counter-increment: ch } h2::before { content: counter(ch, upper-roman) ". " }
              ol.n { list-style: none; counter-reset: item } ol.n li { counter-increment: item }
              ol.n li::before { content: counters(item, ".") ") " }
              ul li::marker { content: "-> " }
              p::first-letter { color: red; font-size: 2em }
              tr { counter-increment: row } td.num::before { content: counter(row) }
            </style>
            <h2>Alpha</h2><h2>Beta</h2>
            <ol class="n"><li>a<ol class="n"><li>b</li><li>c</li></ol></li><li>d</li></ol>
            <ol start="5"><li>five</li><li>six</li></ol><ul><li>dash</li></ul>
            <p>Lettrine intacte</p>
            <table><tr><td class="num"></td><td>x</td></tr><tr><td class="num"></td><td>y</td></tr></table>""";
        String f = flat(text(pdf(html)));
        for (String want : List.of("I. Alpha", "II. Beta", "1) a", "1.1) b", "1.2) c", "2) d", "5. five", "6. six", "-> dash", "Lettrine intacte")) {
            assertTrue(f.replace("L ettrine", "Lettrine").contains(want), want + " not in " + f);
        }
        // row counters
        assertTrue(f.replace(" ", "").contains("1x2y"), f);
    }

    @Test
    void pageMarginBoxesAndFirstPage() {
        String html = "<style>@page { margin: 50px 40px; @top-right { content: \"Entete\" } @bottom-center { content: \"Page \" counter(page) \" / \" counter(pages) } }"
            + " @page :first { margin-top: 120px; @top-right { content: none } }</style>" + "<p>ligne</p>".repeat(150);
        byte[] out = pdf(html);
        int n = pages(out);
        assertTrue(n >= 2);
        List<String> perPage = pagesText(out);
        assertEquals(n, perPage.size());
        for (int i = 0; i < n; i++) {
            String p = perPage.get(i);
            assertTrue(p.contains("Page " + (i + 1) + " / " + n), "page " + (i + 1) + ": " + p);
            assertEquals(i > 0, p.contains("Entete"), "header on page " + (i + 1));
        }
        // the request's footer wins over the margin boxes
        assertTrue(!text(pdf(html, Options.defaults().withFooter("<p>pied</p>"))).contains("Page 1 /"));
    }

    @Test
    void tfootRepeatsWhereTheTableBreaks() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 120; i++) rows.append("<tr><td>r").append(i).append("</td></tr>");
        byte[] out = pdf("<table><thead><tr><th>Entete</th></tr></thead><tfoot><tr><td>Pied</td></tr></tfoot><tbody>" + rows + "</tbody></table>");
        int n = pages(out);
        assertTrue(n >= 3);
        String t = text(out);
        assertEquals(n, count(t, "Pied"), "one footer per page");
        assertEquals(n, count(t, "Entete"), "one header per page");
    }

    @Test
    void borderCollapseTableDoesNotWastePages() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 60; i++) rows.append("<tr><td>").append(i).append("</td><td>Item ").append(i).append("</td><td>$").append(i).append(".00</td></tr>");
        String html = "<style>table { border-collapse: collapse } th, td { border: 1px solid #333; padding: 6px }</style>"
            + "<table><thead><tr><th>#</th><th>Item</th><th>Price</th></tr></thead><tfoot><tr><td colspan=\"3\">End</td></tr></tfoot><tbody>" + rows + "</tbody></table>";
        int n = pages(pdf(html));
        assertTrue(n <= 5, "border-collapse table used " + n + " pages for 60 rows (expected ≤ 5)");
        assertTrue(n >= 2, "table should span at least 2 pages");
    }

    @Test
    void borderCollapseRowsAllPresent() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 60; i++) rows.append("<tr><td>r").append(i).append("</td><td>").append(i).append("</td></tr>");
        String html = "<style>table { border-collapse: collapse } th, td { border: 2px solid #333; padding: 8px }</style>"
            + "<table><thead><tr><th>Entete</th><th>N</th></tr></thead><tfoot><tr><td colspan=\"2\">Pied</td></tr></tfoot><tbody>" + rows + "</tbody></table>";
        byte[] out = pdf(html);
        int n = pages(out);
        String t = text(out);
        List<String> ws = List.of(t.split("\\s+"));
        for (int i = 0; i < 60; i++) {
            String row = "r" + i;
            assertEquals(1, ws.stream().filter(x -> x.equals(row)).count(), "row " + row + " count != 1");
        }
        assertEquals(n, count(t, "Entete"), "header count");
        assertEquals(n, count(t, "Pied"), "footer count");
    }

    @Test
    void linkStylesheetComesFromTheImageDirectoryOnly() throws IOException {
        Path root = Files.createTempDirectory("pdfj-link");
        Files.createDirectories(root.resolve("site"));
        Files.writeString(root.resolve("site/style.css"), "p::after { content: ' [styled]' }");
        Files.writeString(root.resolve("secret.css"), "p::after { content: ' [secret]' }");
        Options o = Options.defaults().withImages(new ImagePolicy(root.resolve("site"), null));
        String t = text(pdf("<link rel=\"stylesheet\" href=\"style.css\"><link rel=\"stylesheet\" href=\"../secret.css\"><p>x</p>", o));
        assertTrue(t.contains("[styled]") && !t.contains("[secret]"), t);
        // no image directory: nothing loads
        assertTrue(!text(pdf("<link rel=\"stylesheet\" href=\"style.css\"><p>x</p>")).contains("[styled]"));
    }

    /** A minimal WOFF 1.0 encoder (zlib per table), to test decoding without a font tool. */
    static byte[] toWoff1(byte[] sfnt) {
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int n = in.getShort(4) & 0xffff;
        List<byte[]> data = new ArrayList<>();
        int[][] dir = new int[n][5];
        for (int i = 0; i < n; i++) {
            int e = 12 + 16 * i;
            int tag = in.getInt(e), checksum = in.getInt(e + 4), offset = in.getInt(e + 8), len = in.getInt(e + 12);
            byte[] raw = java.util.Arrays.copyOfRange(sfnt, offset, offset + len);
            Deflater z = new Deflater();
            z.setInput(raw);
            z.finish();
            ByteArrayOutputStream comp = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!z.finished()) comp.write(buf, 0, z.deflate(buf));
            byte[] c = comp.size() < len ? comp.toByteArray() : raw;
            data.add(c);
            dir[i] = new int[] {tag, 0, c.length, len, checksum};
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[44 + 20 * n]);
        int totalSfnt = 12 + 16 * n;
        for (int i = 0; i < n; i++) {
            while (out.size() % 4 != 0) out.write(0);
            dir[i][1] = out.size();
            out.writeBytes(data.get(i));
            totalSfnt += (dir[i][3] + 3) & ~3;
        }
        while (out.size() % 4 != 0) out.write(0);
        byte[] b = out.toByteArray();
        ByteBuffer h = ByteBuffer.wrap(b);
        h.put("wOFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(in.getInt(0)).putInt(b.length).putShort((short) n).putShort((short) 0).putInt(totalSfnt);
        h.putShort((short) 1).putShort((short) 0);
        h.put(new byte[20]);
        for (int[] d : dir) for (int v : d) h.putInt(v);
        return b;
    }

    @Test
    void woffFontFace() {
        byte[] ttf = PdfText.liberationSerif();
        if (ttf == null) return;
        byte[] woff = toWoff1(ttf);
        assertEquals("wOFF", new String(woff, 0, 4, StandardCharsets.ISO_8859_1));
        assertTrue(Woff.toSfnt(woff) != null, "WOFF decodes");
        String b64 = Base64.getEncoder().encodeToString(woff);
        String html = "<style>@font-face { font-family: W; src: url(data:font/woff;base64," + b64 + ") format('woff') } p { font-family: W }</style><p>x</p>";
        assertTrue(PdfText.fontNames(pdf(html)).stream().anyMatch(n -> n.contains("LiberationSerif")));
    }

    @Test
    void lot4DecorationsDoNotCrash() {
        String html = "<div style=\"background: url(" + PNG + ") 0 0 / 5px repeat content-box, linear-gradient(red, blue) padding-box, url(" + PNG
            + ") center / 50% auto no-repeat, #eee; background-clip: content-box, border-box; padding: 10px; border: 4px dotted; height: 50px\"></div>"
            + "<div style=\"box-shadow: inset 0 0 10px 3px rgba(0,0,0,.5), inset 3px 3px 0 red, 0 2px 4px #000; border-radius: 20px; height: 40px; border: 2px solid\"></div>"
            + "<div style=\"box-shadow: inset 0 0 0 1000px red; width: 10px; height: 10px\"></div>"
            + "<p>a <span style=\"vertical-align: -3px\">b</span> <span style=\"vertical-align: 50%\">c</span> <img src=\"" + PNG + "\" width=\"5\" style=\"vertical-align: 2em\"></p>";
        pdf(html);
    }

    /** A tiny HTTP server on 127.0.0.1: path -> (status, extra header, body). Returns its base URL. */
    static String serve(Map<String, Object[]> routes) throws IOException {
        ServerSocket listener = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        String base = "http://127.0.0.1:" + listener.getLocalPort();
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket s = listener.accept()) {
                    InputStream in = s.getInputStream();
                    StringBuilder head = new StringBuilder();
                    int c;
                    while ((c = in.read()) >= 0) {
                        head.append((char) c);
                        if (head.toString().endsWith("\r\n\r\n")) break;
                    }
                    String path = head.toString().split(" ")[1];
                    Object[] r = routes.getOrDefault(path, new Object[] {404, "", new byte[0]});
                    byte[] body = (byte[]) r[2];
                    OutputStream o = s.getOutputStream();
                    o.write(("HTTP/1.1 " + r[0] + " X\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n" + r[1] + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    o.write(body);
                } catch (IOException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return base;
    }

    @Test
    void remoteResourcesWhenEnabled() throws IOException {
        byte[] png = Base64.getDecoder().decode(PNG.substring(PNG.indexOf(',') + 1));
        String base = serve(Map.of(
            "/a.png", new Object[] {200, "", png},
            "/moved.png", new Object[] {302, "Location: /a.png\r\n", new byte[0]},
            "/style.css", new Object[] {200, "", "@import url(more.css); p::after { content: ' [remote css]' }".getBytes(StandardCharsets.UTF_8)},
            "/more.css", new Object[] {200, "", "p::before { content: '[imported] ' }".getBytes(StandardCharsets.UTF_8)}));
        String html = "<link rel=\"stylesheet\" href=\"" + base + "/style.css\"><p>x</p><img src=\"" + base + "/a.png\" width=\"20\"><img src=\"" + base
            + "/moved.png\" width=\"20\"><img src=\"" + base + "/missing.png\">";
        Options allow = Options.defaults().withImages(new ImagePolicy(null, Remote.Config.defaults().withAllowPrivate(true)));
        byte[] out = pdf(html, allow);
        String t = text(out);
        assertTrue(t.contains("[imported] x [remote css]"), t);
        try (var d = org.apache.pdfbox.Loader.loadPDF(out)) {
            int images = 0;
            for (var n : d.getPage(0).getResources().getXObjectNames()) if (d.getPage(0).getResources().isImageXObject(n)) images++;
            assertTrue(images >= 1, "images embedded");
        }
        // default guards: 127.0.0.1 is private, refused
        assertTrue(!text(pdf(html, Options.defaults().withImages(new ImagePolicy(null, Remote.Config.defaults())))).contains("[remote css]"));
        // remote resources off: refused too
        assertTrue(!text(pdf(html)).contains("[remote css]"));
    }

    @Test
    void gradientTextAndFixedBackgrounds() {
        String html = "<style>h1 { background: linear-gradient(90deg, red, blue); -webkit-background-clip: text; -webkit-text-fill-color: transparent }"
            + " body { background: #fafafa linear-gradient(red, blue) fixed no-repeat; background-size: 100px 50px }</style><h1>Titre <em>dégradé</em></h1>"
            + "<p>ligne</p>".repeat(120);
        byte[] out = pdf(html);
        assertTrue(pages(out) >= 2);
        // the text stays real text, painted with a shading
        assertTrue(text(out).contains("Titre dégradé"));
        try (var d = org.apache.pdfbox.Loader.loadPDF(out)) {
            assertTrue(d.getPage(0).getResources().getShadingNames().iterator().hasNext(), "a shading");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void leftRightPagesAndAllMarginBoxes() {
        String html = "<style>@page { margin: 50pt 40pt; @top-left-corner { content: \"TL\" } @right-middle { content: \"RM\" } @bottom-right-corner { content: counter(page) } }"
            + " @page :left { margin-left: 60pt; margin-right: 20pt; @top-center { content: \"Gauche\" } }"
            + " @page :right { margin-left: 20pt; margin-right: 60pt; @top-center { content: \"Droite\" } }</style>" + "<p>ligne</p>".repeat(150);
        byte[] out = pdf(html);
        int n = pages(out);
        assertTrue(n >= 3);
        List<String> perPage = pagesText(out);
        for (int i = 0; i < n; i++) {
            String p = perPage.get(i);
            // page 1 is a right page, then left, right...
            assertTrue(p.contains(i % 2 == 1 ? "Gauche" : "Droite"), "page " + (i + 1) + ": " + p);
            assertTrue(p.contains("TL") && p.contains("RM"), "page " + (i + 1) + ": " + p);
        }
        // mirrored margins: the column moves, its width does not
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Word w : words(out)) {
            if (!w.text().equals("ligne")) continue;
            min = Math.min(min, w.x());
            max = Math.max(max, w.x());
        }
        assertTrue(Math.abs(min - 20) < 1 && Math.abs(max - 60) < 1, "x from " + min + " to " + max);
    }

    @Test
    void firstLine() {
        List<String> in = new ArrayList<>();
        for (int i = 0; i < 60; i++) in.add("mot" + i);
        String html = "<style>p::first-line { text-transform: uppercase; font-weight: bold; font-size: 14px }</style><p>" + String.join(" ", in) + "</p>";
        String t = text(pdf(html));
        List<String> lines = t.lines().filter(l -> !l.isBlank()).toList();
        assertTrue(lines.size() >= 3);
        assertTrue(List.of(lines.get(0).trim().split("\\s+")).stream().allMatch(w -> w.startsWith("MOT")), "first line: " + lines.get(0));
        assertTrue(List.of(lines.get(1).trim().split("\\s+")).stream().allMatch(w -> w.startsWith("mot")), "second line: " + lines.get(1));
        // every word once
        List<String> all = new ArrayList<>();
        for (String w : t.trim().split("\\s+")) all.add(w.toLowerCase(java.util.Locale.ROOT));
        assertEquals(in, all);
    }

    @Test
    void namedPagesMakeSections() {
        String html = """
            <style>
              @page { size: A5; @bottom-center { content: counter(page) "/" counter(pages) } }
              @page wide { size: A5 landscape; @top-center { content: "PAYSAGE" } }
              .wide { page: wide }
              body { counter-reset: n } h2 { counter-increment: n } h2::before { content: counter(n) ". " }
            </style>
            <h2>Avant</h2><p>texte</p>
            <div class="wide"><h2>Large</h2><p>tableau large</p></div>
            <h2>Apres</h2>""";
        byte[] out = pdf(html);
        // portrait, landscape, portrait, in this order
        assertEquals(List.of("419.53 595.28", "595.28 419.53", "419.53 595.28"), PdfText.mediaBoxes(out));
        List<String> p = pagesText(out);
        assertTrue(p.get(0).contains("1. Avant") && p.get(0).contains("1/3"), p.get(0));
        assertTrue(p.get(1).contains("PAYSAGE") && p.get(1).contains("2. Large") && p.get(1).contains("2/3"), p.get(1));
        assertTrue(p.get(2).contains("3. Apres") && p.get(2).contains("3/3") && !p.get(2).contains("PAYSAGE"), p.get(2));
    }

    @Test
    void textWrapsAroundFloats() {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 120; i++) t.append("w").append(i).append(' ');
        List<Word> w = words(pdf("<p><span style=\"float:left;width:100pt;height:60pt;background:#ccc\"></span>" + t + "</p><p style=\"float:right\">x</p>"));
        Word first = word(w, "w0"), last = word(w, "w119");
        assertTrue(Math.abs(first.x() - 140) < 1, "beside the float: " + first.x());
        // where the last word falls on its line depends on the fonts' widths: only inside the content box (A4, 40pt margins)
        assertTrue(last.y() > first.y() + 60 && last.x() > 39 && last.xMax() < 595.28 - 39, "below the float: " + last);
        // the first word of a line below the float starts at the content edge again
        assertTrue(w.stream().anyMatch(x -> x.text().startsWith("w") && x.y() > first.y() + 62 && Math.abs(x.x() - 40) < 1), w.toString());
    }

    @Test
    void borderRadiusPctOnBorderOnlyDiv() {
        String round = "<div style=\"width:60px;height:60px;border:3px solid #333;border-radius:50%\"></div>"
            + "<div style=\"width:60px;height:60px;background:#3498db;border-radius:50%\"></div><div style=\"width:120px;height:60px;background:#2ecc71;border-radius:50%\"></div>";
        String sharp = round.replace("50%", "0");
        assertNotEquals(PdfText.raw(pdf(round)).length() == PdfText.raw(pdf(sharp)).length() && java.util.Arrays.equals(pdf(round), pdf(sharp)), true);
    }

    @Test
    void stepCounterCircleRenders() {
        String html = """
            <style>
              :root { --accent: #e74c3c; --bg: #ecf0f1; --radius: 6px; }
              .steps { counter-reset: step; }
              .step { counter-increment: step; padding: 8px 8px 8px 40px; position: relative; margin-bottom: 6px; background: var(--bg); border-radius: var(--radius); }
              .step::before { content: counter(step); position: absolute; left: 8px; top: 8px; width: 24px; height: 24px; background: var(--accent); color: white; border-radius: 50%; text-align: center; line-height: 24px; font-weight: bold; }
            </style>
            <div class="steps"><div class="step">First step</div><div class="step">Second step</div></div>""";
        String f = flat(text(pdf(html)));
        assertTrue(f.contains("1") && f.contains("2"), "step numbers must be visible: " + f);
        assertTrue(f.contains("First") && f.contains("Second"), f);
    }

    @Test
    void varCalcBorderRadius() {
        String nested = "<style>.nested-var { --size: 80px; --half: calc(var(--size) / 2); width: var(--size); height: var(--size); border-radius: var(--half); background: #e74c3c; }</style><div class=\"nested-var\">x</div>";
        String sharp = "<style>.nested-var { --size: 80px; width: var(--size); height: var(--size); border-radius: 0; background: #e74c3c; }</style><div class=\"nested-var\">x</div>";
        assertTrue(!java.util.Arrays.equals(pdf(nested), pdf(sharp)), "calc'd var border-radius must differ from border-radius: 0");
    }

    @Test
    void dropCapFirstLetterVisible() {
        String html = "<style>:root { --accent: #e74c3c; } .drop-cap::first-letter { font-size: 36px; float: left; line-height: 1; margin-right: 4px; color: var(--accent); font-weight: bold; }</style>"
            + "<p class=\"drop-cap\">Lorem ipsum dolor sit amet, consectetur adipiscing elit.</p>";
        byte[] out = pdf(html);
        String f = flat(text(out));
        assertTrue(f.contains("L") && f.contains("orem"), "drop cap L must be visible: " + f);
        Word l = words(out).stream().filter(x -> x.text().equals("L")).findFirst().orElseThrow(() -> new AssertionError(words(out)));
        assertTrue(l.x() < 60, "L should be near the left margin: x=" + l.x());
    }

    @Test
    void pageRuleTakesRootVariables() {
        String html = "<style>:root { --m: 20mm; --size: A5 } @page { size: var(--size) landscape; margin: var(--m) } "
            + "@page { @bottom-center { content: 'p' ; color: var(--nope) } }</style><p>x</p>";
        byte[] out = pdf(html);
        assertEquals(List.of("595.28 419.53"), PdfText.mediaBoxes(out), "A5 landscape from var() expected");
        assertTrue(Math.abs(word(words(out), "x").x() - 20 * 72 / 25.4) < 1, "margin from var()");
    }

    @Test
    void pxIsThreeQuartersOfAPtAndViewportUnits() {
        Options o = Options.defaults().withSize(Options.pageSize("A5")).withMargin(new double[4]);
        List<Word> w = words(pdf("<p style=\"margin:0;padding-left:96px\">a</p><p style=\"margin:0;padding-left:50vw\">b</p><p style=\"margin:0;padding-left:calc(100vw - 100pt)\">c</p>", o));
        assertTrue(Math.abs(word(w, "a").x() - 72) < 1, "96px = 72pt: " + w);
        assertTrue(Math.abs(word(w, "b").x() - 419.53 / 2) < 1, "50vw: " + w);
        assertTrue(Math.abs(word(w, "c").x() - 319.53) < 1, "calc with vw: " + w);
    }

    @Test
    void inlineChildrenOfAGridAreGridItems() throws IOException {
        Options o = Options.defaults().withMargin(new double[4]);
        byte[] out = pdf("<div style=\"display:grid;grid-template-columns:100pt 100pt 100pt\"><span>a</span><span>b</span><a href=\"https://x.test\">c</a></div>", o);
        List<Word> w = words(out);
        assertTrue(word(w, "a").x() < 5 && Math.abs(word(w, "b").x() - 100) < 1 && Math.abs(word(w, "c").x() - 200) < 1, "one span per cell: " + w);
        try (var d = org.apache.pdfbox.Loader.loadPDF(out)) {
            var links = d.getPage(0).getAnnotations();
            assertTrue(links.stream().anyMatch(a -> a instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink l
                && l.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI u && u.getURI().equals("https://x.test")), "a grid item <a> keeps its link");
        }
    }

    @Test
    void tableColumnWidthsFromColgroupAndCells() {
        Options o = Options.defaults().withMargin(new double[4]);
        String cells = "<tr><td>a</td><td>b</td><td>c</td></tr>";
        String base = "<style>table { border-spacing: 0 } td, th { padding: 0; text-align: left }</style>";
        // <col width> (px), <col style="width: %">, <colgroup span>
        List<Word> w = words(pdf(base + "<table style=\"width:400pt\"><colgroup><col width=\"200\"><col style=\"width:25%\"></colgroup><colgroup span=\"1\"></colgroup>" + cells + "</table>", o));
        assertTrue(Math.abs(word(w, "b").x() - 150) < 1, "b at " + word(w, "b").x());
        assertTrue(Math.abs(word(w, "c").x() - 250) < 1, "c at " + word(w, "c").x());
        // widths on the header cells: a percentage is of the table, not of the column
        w = words(pdf(base + "<table style=\"width:400pt\"><tr><th style=\"width:25%\">h1</th><th style=\"width:150pt\">h2</th><th>h3</th></tr>" + cells + "</table>", o));
        assertTrue(Math.abs(word(w, "b").x() - 100) < 1, "b at " + word(w, "b").x());
        assertTrue(Math.abs(word(w, "c").x() - 250) < 1, "c at " + word(w, "c").x());
    }

    /** A table with a width shares the room its columns leave like Chromium (values measured in Chromium 130). */
    @Test
    void tableRoomLeftGoesToTheColumnsLikeChromium() {
        Options o = Options.defaults().withMargin(new double[4]);
        String html = """
            <style>table { border-spacing: 0 } td { padding: 0 }</style>
            <table style="width:400pt"><tr><td style="width:100pt">A1</td><td style="width:100pt">A2</td></tr></table>
            <table style="width:400pt"><tr><td style="width:20%">C1</td><td style="width:30%">C2</td></tr></table>
            <table style="width:400pt"><tr><td style="width:100pt">D1</td><td style="width:25%">D2</td></tr></table>
            <table style="width:400pt"><tr><td style="width:100pt">E1</td><td style="width:25%">E2</td><td>E3</td></tr></table>
            <table><tr><td style="width:100pt">F1</td><td style="width:50pt">F2</td></tr></table>
            <table style="width:400pt"><colgroup><col style="width:100pt"><col style="width:50pt"></colgroup><tr><td>G1</td><td>G2</td></tr></table>
            <table style="width:400pt"><tr><td>H1</td><td>H2 wider content</td></tr></table>""";
        List<Word> w = words(pdf(html, o));
        // all fixed: in proportion to their widths; all percentages: scaled; fixed and %: the fixed one grows
        assertEquals(200, word(w, "A2").x(), 1);
        assertEquals(160, word(w, "C2").x(), 1);
        assertEquals(300, word(w, "D2").x(), 1);
        // an auto column takes the room, the others keep their width
        assertEquals(100, word(w, "E2").x(), 1);
        assertEquals(200, word(w, "E3").x(), 1);
        // a table without a width: as given
        assertEquals(100, word(w, "F2").x(), 1);
        assertEquals(266.67, word(w, "G2").x(), 1);
        // auto columns: in proportion to their content, not evenly
        assertTrue(word(w, "H2").x() < 150, "H2 at " + word(w, "H2").x());
    }
}
