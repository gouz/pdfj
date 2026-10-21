package io.github.gouz.pdfj.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlsTest {
    @Test
    void urlsRelativeToTheirStylesheet() {
        assertEquals("https://cdn.test/css/b.css", Builder.joinUrl("https://cdn.test/css/a.css", "b.css"));
        assertEquals("https://cdn.test/css/../img/x.png", Builder.joinUrl("https://cdn.test/css/a.css", "../img/x.png"));
        assertEquals("https://cdn.test/root.css", Builder.joinUrl("https://cdn.test/css/a.css", "/root.css"));
        assertEquals("https://cdn.test/b.css", Builder.joinUrl("https://cdn.test", "b.css"));
        assertEquals("css/../img/x.png", Builder.joinUrl("css/site.css", "../img/x.png"));
        assertEquals("img.png", Builder.joinUrl("site.css", "img.png"));
        assertEquals("data:image/png;base64,AA", Builder.joinUrl("css/site.css", "data:image/png;base64,AA"));
        assertEquals("a { background: url(\"s/i.png\") } @font-face { src: url(\"s/f.woff2\") }",
            Builder.absoluteUrls("a { background: url('i.png') } @font-face { src: url(f.woff2) }", "s/x.css"));
    }
}
