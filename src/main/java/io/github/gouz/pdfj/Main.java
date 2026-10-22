package io.github.gouz.pdfj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.net.ImagePolicy;
import io.github.gouz.pdfj.net.Remote;
import io.github.gouz.pdfj.template.Templates;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code pdfj} with no argument (or {@code pdfj serve}): HTTP server, POST HTML, get a PDF.
 * {@code pdfj input.html [output.pdf] [options]}: one-shot conversion ({@code --data d.json}: input is a template).
 */
public final class Main {
    private Main() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An invalid option or template: a 400. */
    static final class BadRequest extends Exception {
        BadRequest(String m) {
            super(m);
        }
    }

    /** Options shared by the query string, the JSON body and the command line (values as JSON). */
    static final class Params {
        final Map<String, JsonNode> values = new HashMap<>();

        void put(String k, JsonNode v) {
            values.put(k, v);
        }

        String str(String k) {
            JsonNode v = values.get(k);
            return v == null || v.isNull() ? null : v.isTextual() ? v.asText() : v.toString();
        }
    }

    static boolean truthy(JsonNode v) {
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) return !List.of("false", "0", "no").contains(v.asText());
        if (v.isNumber()) return v.asDouble() != 0;
        return false;
    }

    /** {@code 40}, {@code "40"}, {@code "60,40,50,40"} / {@code "60 40 50 40"} (top right bottom left, CSS order and shorthand) */
    static double[] margin(JsonNode v) throws BadRequest {
        String s;
        if (v.isNumber()) s = v.asText();
        else if (v.isTextual()) s = v.asText();
        else if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(x.asText()));
            s = String.join(" ", parts);
        } else throw new BadRequest("margin: number or \"top right bottom left\"");
        List<Double> n = new ArrayList<>();
        for (String p : s.split("[, ]")) {
            if (p.isEmpty()) continue;
            String q = p.endsWith("pt") ? p.substring(0, p.length() - 2) : p;
            try {
                n.add(Double.parseDouble(q));
            } catch (NumberFormatException e) {
                throw new BadRequest("margin: invalid value \"" + s + "\"");
            }
        }
        return switch (n.size()) {
            case 1 -> new double[] {n.get(0), n.get(0), n.get(0), n.get(0)};
            case 2 -> new double[] {n.get(0), n.get(1), n.get(0), n.get(1)};
            case 3 -> new double[] {n.get(0), n.get(1), n.get(2), n.get(1)};
            case 4 -> new double[] {n.get(0), n.get(1), n.get(2), n.get(3)};
            default -> throw new BadRequest("margin: invalid value \"" + s + "\"");
        };
    }

    static Options options(Params p, ImagePolicy images) throws BadRequest {
        Options o = Options.defaults().withImages(images);
        String size = p.str("size");
        if (size != null) {
            double[] s = Options.pageSize(size);
            if (s == null) throw new BadRequest("size: unknown page size \"" + size + "\" (A4, LETTER... or 400x600)");
            o = o.withSize(s);
        }
        if (p.values.get("landscape") != null) o = o.withLandscape(truthy(p.values.get("landscape")));
        if (p.values.get("margin") != null) o = o.withMargin(margin(p.values.get("margin")));
        if (p.str("font") != null) o = o.withFont(p.str("font"));
        JsonNode fs = p.values.get("fontSize");
        if (fs != null && !fs.isNull()) {
            double v;
            if (fs.isNumber()) v = fs.asDouble();
            else {
                try {
                    v = Double.parseDouble(fs.asText());
                } catch (NumberFormatException e) {
                    throw new BadRequest("fontSize: a number is expected");
                }
            }
            if (v > 0 && v < 1000) o = o.withFontSize(v);
        }
        return o.withHeader(p.str("header")).withFooter(p.str("footer")).withTitle(p.str("title")).withAuthor(p.str("author"))
            .withSubject(p.str("subject")).withKeywords(p.str("keywords"));
    }

    /** What to render: HTML, or a template with its data. */
    sealed interface Input {
        record Html(String html) implements Input {}

        record Template(String inline, String name, JsonNode data, String locale) implements Input {}
    }

    /** Fill the template, then render the HTML. The header / footer options are templates too. */
    static byte[] produce(Input input, Options opts, Path templates) throws BadRequest {
        String html;
        if (input instanceof Input.Html h) {
            html = h.html();
        } else {
            Input.Template t = (Input.Template) input;
            try {
                if (opts.header() != null) opts = opts.withHeader(Templates.render(new Templates.Source.Inline(opts.header()), t.data(), templates, t.locale()));
                if (opts.footer() != null) opts = opts.withFooter(Templates.render(new Templates.Source.Inline(opts.footer()), t.data(), templates, t.locale()));
                Templates.Source src = t.inline() != null ? new Templates.Source.Inline(t.inline()) : new Templates.Source.Named(t.name());
                html = Templates.render(src, t.data(), templates, t.locale());
            } catch (Templates.TemplateException e) {
                throw new BadRequest("template: " + e.getMessage());
            }
        }
        return Pdf.htmlToPdf(html, opts);
    }

    static final String USAGE = """
        pdfj: POST HTML to / (or /pdf), get a PDF back.

          curl --data-binary @page.html 'http://localhost:3000/?size=A4&margin=40' -o page.pdf
          curl -H 'content-type: application/json' -d '{"html":"<h1>Hi</h1>","footer":"<p>{page}/{pages}</p>"}' http://localhost:3000/ -o hi.pdf

        Options (query string or JSON fields): size, landscape, margin, font, fontSize, header, footer,
        title, author, subject, keywords.

        Templates (JSON): {"template": "<h1>{{ title }}</h1>", "data": {...}} or {"template_name": "invoice.html",
        "data": {...}} (TEMPLATES_DIR), optional "locale" (fr, en, de...) for the money / number / date filters.
        """;

    static List<Path> envDirs(String var, String dflt) {
        String v = System.getenv(var);
        List<Path> out = new ArrayList<>();
        for (String s : (v == null ? dflt : v).split(":")) if (!s.isEmpty()) out.add(Path.of(s));
        return out;
    }

    static int fontsInit() {
        int n = Fonts.init(envDirs("FONTS_DIR", "/usr/share/fonts:/fonts"));
        if (n == 0) System.err.println("pdfj: warning: no font found (set FONTS_DIR), text will not render");
        return n;
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? null : v;
    }

    private static boolean yes(String v) {
        return v != null && (v.equals("1") || v.equals("true") || v.equals("yes"));
    }

    /** Fixed pool of render threads (16 MB of stack: layout recurses along the box tree, depth is capped). */
    static ExecutorService renderPool(int n) {
        AtomicInteger i = new AtomicInteger();
        ThreadFactory f = r -> {
            Thread t = new Thread(null, r, "render-" + i.getAndIncrement(), 16L * 1024 * 1024);
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(n, f);
    }

    /** The server's settings (from the environment in production, given by the tests). */
    record ServerConfig(int port, int workers, ImagePolicy images, Path templates, String locale, long maxBody) {
        static ServerConfig fromEnv() {
            int workers = Runtime.getRuntime().availableProcessors();
            try {
                if (env("WORKERS") != null && Integer.parseInt(env("WORKERS")) > 0) workers = Integer.parseInt(env("WORKERS"));
            } catch (NumberFormatException ignored) {
                // default
            }
            boolean remote = yes(env("REMOTE_RESOURCES"));
            Path templates = env("TEMPLATES_DIR") == null ? null : Path.of(env("TEMPLATES_DIR"));
            // images and stylesheets next to the templates, unless they have a directory of their own
            Path imagesDir = env("IMAGES_DIR") != null ? Path.of(env("IMAGES_DIR")) : templates;
            ImagePolicy images = new ImagePolicy(imagesDir, remote ? Remote.Config.fromEnv() : null);
            long maxBody = 20L * 1024 * 1024;
            try {
                if (env("MAX_BODY_MB") != null) maxBody = Long.parseLong(env("MAX_BODY_MB")) * 1024 * 1024;
            } catch (NumberFormatException ignored) {
                // default
            }
            int port;
            try {
                port = Integer.parseInt(env("PORT") == null ? "3000" : env("PORT"));
            } catch (NumberFormatException e) {
                System.err.println("pdfj: invalid PORT " + env("PORT"));
                port = 3000;
            }
            return new ServerConfig(port, workers, images, templates, env("LOCALE") == null ? "fr" : env("LOCALE"), maxBody);
        }
    }

    static void serve() {
        int fonts = fontsInit();
        ServerConfig c = ServerConfig.fromEnv();
        HttpServer server;
        try {
            server = start(c);
        } catch (IOException e) {
            System.err.println("pdfj: cannot listen on :" + c.port() + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        System.err.println("pdfj listening on :" + c.port() + " (" + c.workers() + " render threads, " + fonts + " font files, remote resources "
            + (c.images().remote() != null ? "on" : "off") + ")");
    }

    /** Listen on the config's port (0: any free port), render with a pool of {@code workers} threads. */
    static HttpServer start(ServerConfig c) throws IOException {
        ExecutorService pool = renderPool(c.workers());
        HttpServer server = HttpServer.create(new InetSocketAddress(c.port()), 1024);
        server.createContext("/", ex -> handle(ex, pool, c.images(), c.templates(), c.locale(), c.maxBody()));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(body);
        }
    }

    private static void text(HttpExchange ex, int status, String msg) throws IOException {
        reply(ex, status, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
    }

    private static void handle(HttpExchange ex, ExecutorService pool, ImagePolicy images, Path templates, String locale, long maxBody) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/health") && method.equals("GET")) {
                text(ex, 200, "ok\n");
                return;
            }
            if (path.equals("/") && method.equals("GET")) {
                text(ex, 200, USAGE);
                return;
            }
            if (!(path.equals("/") || path.equals("/pdf"))) {
                text(ex, 404, "not found\n");
                return;
            }
            if (!method.equals("POST")) {
                text(ex, 405, "method not allowed\n");
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                // one byte more than allowed: tells a body too large
                body = in.readNBytes((int) Math.min(maxBody + 1, Integer.MAX_VALUE - 8));
                if (body.length > maxBody) {
                    text(ex, 413, "request too large\n");
                    return;
                }
            }
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            Input input;
            Params params = new Params();
            if (type != null && type.startsWith("application/json")) {
                JsonNode b;
                try {
                    b = JSON.readTree(body);
                } catch (IOException e) {
                    text(ex, 400, "invalid JSON body: " + e.getMessage() + "\n");
                    return;
                }
                if (b == null || !b.isObject()) {
                    text(ex, 400, "invalid JSON body: an object is expected\n");
                    return;
                }
                for (Iterator<Map.Entry<String, JsonNode>> it = b.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    params.put(e.getKey(), e.getValue());
                }
                String html = params.str("html"), tpl = params.str("template");
                String name = params.str("template_name") != null ? params.str("template_name") : params.str("templateName");
                JsonNode data = b.get("data");
                String loc = params.str("locale") != null ? params.str("locale") : locale;
                int given = (html != null ? 1 : 0) + (tpl != null ? 1 : 0) + (name != null ? 1 : 0);
                if (given != 1) {
                    text(ex, 400, "give one of html, template, template_name\n");
                    return;
                }
                if (html != null) input = new Input.Html(html);
                else if (tpl != null) input = new Input.Template(tpl, null, data, loc);
                else if (templates == null) {
                    text(ex, 400, "template_name: no TEMPLATES_DIR on this server\n");
                    return;
                } else input = new Input.Template(null, name, data, loc);
            } else {
                input = new Input.Html(new String(body, StandardCharsets.UTF_8));
                String q = ex.getRequestURI().getRawQuery();
                if (q != null) {
                    for (String kv : q.split("&")) {
                        if (kv.isEmpty()) continue;
                        int eq = kv.indexOf('=');
                        String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
                        String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                        params.put(k, JSON.getNodeFactory().textNode(v));
                    }
                }
            }
            Options opts;
            try {
                opts = options(params, images);
            } catch (BadRequest e) {
                text(ex, 400, e.getMessage() + "\n");
                return;
            }
            Input job = input;
            Options o = opts;
            Future<byte[]> f = pool.submit(() -> produce(job, o, templates));
            try {
                reply(ex, 200, "application/pdf", f.get());
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof BadRequest b) text(ex, 400, b.getMessage() + "\n");
                else text(ex, 500, (c == null ? "render failed" : String.valueOf(c)) + "\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text(ex, 500, "render failed\n");
            }
        } catch (IOException e) {
            // client gone
        }
    }

    static void cli(List<String> args) throws BadRequest, IOException {
        Params p = new Params();
        List<String> pos = new ArrayList<>();
        boolean remote = false;
        String data = null, locale = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            String flag = a;
            int idx = i;
            switch (flag) {
                case "--size", "--margin", "--font", "--font-size", "--header", "--footer", "--title", "--data", "--locale" -> {
                    if (idx + 1 >= args.size()) throw new BadRequest(a + ": missing value");
                    String v = args.get(++i);
                    switch (flag) {
                        case "--size" -> p.put("size", JSON.getNodeFactory().textNode(v));
                        case "--margin" -> p.put("margin", JSON.getNodeFactory().textNode(v));
                        case "--font" -> p.put("font", JSON.getNodeFactory().textNode(v));
                        case "--font-size" -> {
                            try {
                                p.put("fontSize", JSON.getNodeFactory().numberNode(Double.parseDouble(v)));
                            } catch (NumberFormatException e) {
                                throw new BadRequest("--font-size: number expected");
                            }
                        }
                        case "--header" -> p.put("header", JSON.getNodeFactory().textNode(v));
                        case "--footer" -> p.put("footer", JSON.getNodeFactory().textNode(v));
                        case "--title" -> p.put("title", JSON.getNodeFactory().textNode(v));
                        case "--data" -> data = v;
                        default -> locale = v;
                    }
                }
                case "--landscape" -> p.put("landscape", JSON.getNodeFactory().booleanNode(true));
                case "--remote" -> remote = true;
                case "-h", "--help" -> throw new BadRequest("");
                default -> {
                    if (a.startsWith("--")) throw new BadRequest("unknown option " + a);
                    pos.add(a);
                }
            }
        }
        if (pos.isEmpty()) throw new BadRequest("missing input file");
        Path input = Path.of(pos.get(0));
        String output = pos.size() > 1 ? pos.get(1) : "out.pdf";
        String html;
        try {
            html = Files.readString(input);
        } catch (IOException e) {
            throw new BadRequest(input + ": " + e.getMessage());
        }
        fontsInit();
        // local images: only from the input file's folder
        Path base = input.toAbsolutePath().normalize().getParent();
        Options opts = options(p, new ImagePolicy(base, remote ? Remote.Config.fromEnv() : null));
        // --data: the input is a template of its folder, filled with that JSON
        Input job;
        if (data == null) {
            job = new Input.Html(html);
        } else {
            JsonNode d;
            try {
                d = JSON.readTree(Files.readString(Path.of(data)));
            } catch (IOException e) {
                throw new BadRequest(data + ": " + e.getMessage());
            }
            job = new Input.Template(null, input.getFileName().toString(), d, locale == null ? "fr" : locale);
        }
        // same stack as the server's render threads
        ExecutorService pool = renderPool(1);
        try {
            byte[] pdf = pool.submit(() -> produce(job, opts, base)).get();
            Files.write(Path.of(output), pdf);
            System.out.println("✔ " + output);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BadRequest b) throw b;
            throw new BadRequest(String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            throw new BadRequest("interrupted");
        } finally {
            pool.shutdown();
        }
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        if (args.length == 0 || args[0].equals("serve")) {
            serve();
            return;
        }
        try {
            cli(List.of(args));
        } catch (BadRequest e) {
            if (!e.getMessage().isEmpty()) System.err.println("pdfj: " + e.getMessage());
            System.err.println("""
                Usage: pdfj                                   HTTP server (PORT, WORKERS, FONTS_DIR, IMAGES_DIR, TEMPLATES_DIR, LOCALE, REMOTE_RESOURCES)
                       pdfj <input.html> [output.pdf] [options]
                  --size A4|LETTER|400x600   --landscape   --margin 40 | '60 40 50 40'
                  --font Georgia             --font-size 12
                  --header '<p>Titre</p>'    --footer '<p>{page} / {pages}</p>'   --title 'Facture 42'
                  --remote                   also load http(s) images, stylesheets and fonts (REMOTE_HOSTS, REMOTE_ALLOW_PRIVATE...)
                  --data data.json           the input is a Jinja template, filled with this JSON   --locale fr|en|de...""");
            System.exit(1);
        }
    }
}
