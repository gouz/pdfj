package io.github.gouz.pdfj.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gouz.pdfj.template.Templates.Source;
import io.github.gouz.pdfj.template.Templates.TemplateException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatesTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static String r(String t, String data) throws TemplateException {
        return Templates.render(new Source.Inline(t), json(data), null, "fr");
    }

    static String err(String t, String data) {
        return assertThrows(TemplateException.class, () -> r(t, data)).getMessage();
    }

    @Test
    void variablesLoopsAndEscaping() throws TemplateException {
        assertEquals("<td>&lt;b&gt;a&lt;/b&gt; &amp; b</td><td>c</td>",
            r("{% for l in lignes %}<td>{{ l.nom }}</td>{% endfor %}", "{\"lignes\": [{\"nom\": \"<b>a</b> & b\"}, {\"nom\": \"c\"}]}"));
        assertEquals("<i>ok</i>", r("{{ x | safe }}", "{\"x\": \"<i>ok</i>\"}"));
    }

    @Test
    void strict() throws TemplateException {
        String e = err("<p>{{ client.nom }}</p>", "{\"client\": {}}");
        assertTrue(e.contains("undefined") && e.contains("line 1"), e);
        err("{{ nom }}", "{}");
        assertEquals("anonyme", r("{{ nom | default('anonyme') }}", "{}"));
        r("{% if remise is defined %}x{% endif %}", "{}");
        err("{{ x }}", "[1]"); // data must be an object
        for (String f : List.of("money", "number", "date", "nl2br")) {
            String m = err("{{ absent | " + f + " }}", "{}");
            assertTrue(m.contains("undefined"), f + ": " + m);
        }
    }

    /** null is a value of the data, as in pdfrs: only a missing variable or key is undefined */
    @Test
    void nullIsDefined() throws TemplateException {
        String d = "{\"x\": null, \"l\": [], \"o\": {\"t\": null}, \"ops\": [{\"t\": null}]}";
        assertEquals("", r("{{ x }}", d));
        assertEquals("-", r("{% if x %}a{% else %}-{% endif %}", d));
        assertEquals("-", r("{% if l or x %}a{% else %}-{% endif %}", d));
        assertEquals("-", r("{% if o.t %}a{% else %}-{% endif %}", d));
        assertEquals("-", r("{% for o in ops %}{% if o.t %}a{% else %}-{% endif %}{% endfor %}", d));
        assertEquals("—", r("{{ o.t or '—' }}", d));
        assertEquals("n", r("{% if x is none %}n{% endif %}", d));
        assertEquals("y", r("{% if x is defined %}y{% endif %}", d));
        String e = err("{% if l or absent %}a{% endif %}", d);
        assertTrue(e.contains("undefined") && e.contains("absent"), e);
        e = err("{% for o in ops %}{{ o.u }}{% endfor %}", d);
        assertTrue(e.contains("undefined"), e);
        err("{{ l[3] }}", d);
        err("{{ absent or '—' }}", d);
        err("{% if absent %}a{% endif %}", d);
        assertEquals("—", r("{{ absent | default('—') }}", d));
        assertEquals("u", r("{% if o.u is undefined %}u{% endif %}", d));
        assertEquals("-", r("{% if absent is defined and absent %}a{% else %}-{% endif %}", d));
        assertEquals("ok", r("{% for k, v in o.items() %}ok{% endfor %}", d));
    }

    @Test
    void includeOfAnExpression() throws IOException, TemplateException {
        Path tpl = Files.createTempDirectory("pdfj-inc");
        Files.createDirectories(tpl.resolve("brands/acme"));
        Files.writeString(tpl.resolve("brands/acme/legal.txt"), "ACME SAS");
        JsonNode data = json("{\"brand\": \"acme\"}");
        assertEquals("<p>ACME SAS</p>", Templates.render(new Source.Inline("<p>{% include \"brands/\" ~ brand ~ \"/legal.txt\" %}</p>"), data, tpl, "fr"));
        assertEquals("<p>ACME SAS</p>", Templates.render(new Source.Inline("<p>{% include 'brands/' ~ brand ~ '/legal.txt' %}</p>"), data, tpl, "fr"));
        String e = assertThrows(TemplateException.class,
            () -> Templates.render(new Source.Inline("{% include \"brands/\" ~ brand ~ \"/../../../etc/passwd\" %}"), data, tpl, "fr")).getMessage();
        assertTrue(e.contains("not found"), e);
    }

    @Test
    void bounded() {
        String e = err("{% for i in range(100000) %}{% for j in range(100000) %}{% endfor %}{% endfor %}", "{}");
        assertTrue(e.contains("too long"), e);
    }

    @Test
    void filters() throws TemplateException {
        String d = "{\"n\": 1234.5, \"neg\": -3.456, \"s\": \"2026-10-15\", \"dt\": \"2026-10-15T08:30:00Z\"}";
        assertEquals("1 234,50 €", r("{{ n | money }}", d));
        assertEquals("$1,234.50", r("{{ n | money('USD', locale='en') }}", d));
        assertEquals("-$3.46", r("{{ neg | money('USD', locale='en') }}", d));
        assertEquals("1.235 €", r("{{ n | money(decimals=0, locale='de') }}", d));
        assertEquals("1 235 1 234,5", r("{{ n | number }} {{ n | number(1) }}", d));
        assertEquals("12,50", r("{{ '12,5' | number(2) }}", d));
        assertEquals("2,68 1 0,00 €", r("{{ 2.675 | number(2) }} {{ 0.5 | number }} {{ -0.004 | money }}", d));
        assertEquals("15/10/2026 jeudi 15 octobre 2026", r("{{ s | date }} {{ s | date('%A %e %B %Y') }}", d));
        assertEquals("15/10 08:30 10/15/2026", r("{{ dt | date('%d/%m %H:%M', locale='en') }} {{ s | date(locale='en') }}", d));
        err("{{ 'hier' | date }}", d);
        assertEquals("1 rue &lt;X&gt;<br>\n75000 Paris", r("{{ a | nl2br }}", "{\"a\": \"1 rue <X>\\n75000 Paris\"}"));
        assertEquals("3,50 €", r("{{ [1, 2.5] | sum | money }}", d));
    }

    /** the examples of the README's filter table */
    @Test
    void readmeExamples() throws TemplateException {
        java.util.function.Function<String, String> en = t -> {
            try {
                return Templates.render(new Source.Inline(t), json("{}"), null, "en");
            } catch (TemplateException e) {
                throw new AssertionError(e);
            }
        };
        assertEquals("99,00 $", r("{{ 99 | money('USD') }}", "{}"));
        assertEquals("€1,234.50", en.apply("{{ 1234.5 | money }}"));
        assertEquals("$99.00", en.apply("{{ 99 | money('USD') }}"));
        assertEquals("€1,235", en.apply("{{ 1234.5 | money(decimals=0) }}"));
        assertEquals("1 235 €", r("{{ 1234.5 | money(decimals=0) }}", "{}"));
        assertEquals("1,235 / 1,234.50", en.apply("{{ 1234.5 | number }} / {{ 1234.5 | number(2) }}"));
        assertEquals("10/15/2026", en.apply("{{ '2026-10-15' | date }}"));
        assertEquals("Thursday 15 October 2026", en.apply("{{ '2026-10-15' | date('%A %e %B %Y') }}"));
    }

    @Test
    void templatesDirectory() throws IOException, TemplateException {
        Path root = Files.createTempDirectory("pdfj-tpl");
        Path dir = Files.createDirectories(root.resolve("tpl/parts"));
        Files.writeString(root.resolve("tpl/base.html"), "<h1>{% block title %}{% endblock %}</h1>{% include 'parts/foot.html' %}");
        Files.writeString(root.resolve("tpl/parts/foot.html"), "<p>{{ societe }}</p>");
        Files.writeString(root.resolve("tpl/facture.html"), "{% extends 'base.html' %}{% block title %}Facture {{ n }}{% endblock %}");
        Files.writeString(root.resolve("secret.html"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("tpl/link.html"), root.resolve("secret.html"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // no symlinks here
        }
        Path tpl = root.resolve("tpl");
        JsonNode data = json("{\"n\": 42, \"societe\": \"ACME\"}");
        assertEquals("<h1>Facture 42</h1><p>ACME</p>", Templates.render(new Source.Named("facture.html"), data, tpl, "fr"));
        // inline templates can extend the directory's ones
        assertEquals("<h1>T</h1><p>ACME</p>", Templates.render(new Source.Inline("{% extends 'base.html' %}{% block title %}T{% endblock %}"), data, tpl, "fr"));
        for (String escape : List.of("../secret.html", "link.html", "/etc/passwd")) {
            String e = assertThrows(TemplateException.class, () -> Templates.render(new Source.Named(escape), data, tpl, "fr")).getMessage();
            assertTrue(e.contains("not found"), escape + ": " + e);
        }
        assertThrows(TemplateException.class, () -> Templates.render(new Source.Named("facture.html"), data, null, "fr")); // no directory, no files
        assertTrue(dir.toFile().exists());
    }
}
