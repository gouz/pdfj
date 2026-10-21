package io.github.gouz.pdfj;

import com.sun.net.httpserver.HttpServer;
import io.github.gouz.pdfj.net.ImagePolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP API end to end. */
class ServerTest {
    record Res(int status, byte[] body) {
        String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean pdf() {
            return body.length > 5 && new String(body, 0, 5).equals("%PDF-");
        }
    }

    static HttpServer start(Path templates) throws IOException {
        PdfText.init();
        return Main.start(new Main.ServerConfig(0, 2, new ImagePolicy(templates, null), templates, "fr", 20L * 1024 * 1024));
    }

    static Res request(HttpServer s, String method, String path, String type, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + path));
        if (type != null) b.header("Content-Type", type);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Res(r.statusCode(), r.body());
    }

    @Test
    void templatesOverHttp() throws Exception {
        Path dir = Files.createTempDirectory("pdfj-srv");
        Files.writeString(dir.resolve("base.html"), "<h1>{% block t %}{% endblock %}</h1>");
        Files.writeString(dir.resolve("facture.html"), "{% extends 'base.html' %}{% block t %}Facture {{ n }} : {{ total | money }}{% endblock %}");
        HttpServer srv = start(dir);
        try {
            String json = "application/json";
            Res r = request(srv, "POST", "/", json, "{\"template_name\": \"facture.html\", \"data\": {\"n\": 42, \"total\": 1234.5}, \"size\": \"A5\"}");
            assertEquals(200, r.status(), r.text());
            assertTrue(r.pdf());
            assertTrue(PdfText.text(r.body()).contains("Facture 42 : 1 234,50 €"), PdfText.text(r.body()));

            r = request(srv, "POST", "/pdf", json, "{\"template\": \"<p>{% for x in items %}{{ x }} {% endfor %}</p>\", \"data\": {\"items\": [1, 2]}, \"footer\": \"<p>{{ items | length }} / {page}</p>\"}");
            assertEquals(200, r.status(), r.text());
            assertTrue(PdfText.text(r.body()).contains("2 / 1"), PdfText.text(r.body()));

            // strict: a missing variable is a 400 that says where
            r = request(srv, "POST", "/", json, "{\"template_name\": \"facture.html\", \"data\": {\"n\": 1}}");
            assertEquals(400, r.status());
            assertTrue(r.text().contains("undefined") && r.text().contains("facture.html"), r.text());

            for (String bad : List.of(
                "{\"template_name\": \"../etc/passwd\", \"data\": {}}",
                "{\"template\": \"{% for %}\", \"data\": {}}",
                "{\"html\": \"<p>x</p>\", \"template\": \"<p>y</p>\"}",
                "{\"template\": \"{{ x }}\", \"data\": [1]}",
                "{}")) {
                assertEquals(400, request(srv, "POST", "/", json, bad).status(), bad);
            }
            // plain HTML still works
            r = request(srv, "POST", "/?size=A6", "text/html", "<h1>ok</h1>");
            assertTrue(r.status() == 200 && r.pdf());
            assertEquals(List.of("297.64 419.53"), PdfText.mediaBoxes(r.body()));
            // an invalid option is a 400
            assertEquals(400, request(srv, "POST", "/?size=A42", "text/html", "<h1>ok</h1>").status());
            assertEquals(200, request(srv, "GET", "/health", null, null).status());
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void templateNameNeedsATemplatesDir() throws Exception {
        HttpServer srv = start(null);
        try {
            Res r = request(srv, "POST", "/", "application/json", "{\"template_name\": \"a.html\", \"data\": {}}");
            assertEquals(400, r.status());
            assertTrue(r.text().contains("TEMPLATES_DIR"));
        } finally {
            srv.stop(0);
        }
    }
}
