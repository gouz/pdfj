package io.github.gouz.pdfj.css;

import io.github.gouz.pdfj.css.Css.Media;
import io.github.gouz.pdfj.css.Css.Pseudo;
import io.github.gouz.pdfj.css.Css.Sheet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CssTest {
    static final Media A4 = new Media(515, 762);

    @Test
    void declarations() {
        List<Css.Decl> d = Css.parseDeclarations("color: red; background: url(a;b) no-repeat ; margin:0 !important;;");
        assertEquals(3, d.size());
        assertEquals("url(a;b) no-repeat", d.get(1).value());
        assertTrue(d.get(2).important() && d.get(2).value().equals("0"));
    }

    @Test
    void atRules() {
        Sheet s = new Sheet();
        Css.parseStylesheet("""
            @import url(x.css); /* c */ @media print { p { color: red } } @media screen { p { color: blue } }
            h1, .a { color: blue } @font-face { font-family: X; src: url(x.ttf) } @page { size: A5 } @page :first { margin: 0 }
            @keyframes k { from { color: red } } p::before { content: 'x' } li:after { content: '' } ::marker { color: red }""", A4, s);
        assertEquals(6, s.rules.size()); // p (print), h1, .a, p::before, li::after, *::marker
        assertEquals(Pseudo.MARKER, s.rules.get(5).pseudo);
        assertArrayEquals(new int[] {0, 0, 1}, s.rules.get(1).specificity);
        assertArrayEquals(new int[] {0, 1, 0}, s.rules.get(2).specificity);
        assertEquals(Pseudo.BEFORE, s.rules.get(3).pseudo);
        assertEquals(Pseudo.AFTER, s.rules.get(4).pseudo);
        assertTrue(s.hasPseudo);
        assertEquals(2, s.pages.size());
        assertEquals(1, s.fontFaces.size());
        assertNull(s.pages.get(0).pseudo);
        assertEquals(":first", s.pages.get(1).pseudo);
        s = new Sheet();
        Css.parseStylesheet("@page { margin: 1cm; @top-center { content: 'T'; color: red } @bottom-right { content: counter(page) } size: A5 } li::marker { color: red } p::first-letter { font-size: 2em }", A4, s);
        Css.PageRule page = s.pages.get(0);
        assertEquals(List.of("margin", "size"), page.decls.stream().map(Css.Decl::prop).toList());
        assertEquals(List.of("top-center", "bottom-right"), page.boxes.stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(List.of(Pseudo.MARKER, Pseudo.FIRST_LETTER), s.rules.stream().map(r -> r.pseudo).toList());
    }

    @Test
    void mediaQueries() {
        assertTrue(Css.mediaMatches("print", A4));
        assertTrue(Css.mediaMatches("all and (min-width: 400px)", A4));
        assertFalse(Css.mediaMatches("screen", A4));
        assertTrue(Css.mediaMatches("not screen", A4));
        assertTrue(Css.mediaMatches("screen, print", A4));
        assertFalse(Css.mediaMatches("(max-width: 400px)", A4));
        assertTrue(Css.mediaMatches("(width >= 500px)", A4));
        assertTrue(Css.mediaMatches("(orientation: portrait)", A4));
        assertFalse(Css.mediaMatches("(min-resolution: 2dppx)", A4));
    }

    @Test
    void specificities() {
        assertArrayEquals(new int[] {1, 1, 1}, Css.specificity("#a .b p"));
        assertArrayEquals(new int[] {0, 1, 2}, Css.specificity("ul li:first-child"));
        assertArrayEquals(new int[] {0, 1, 1}, Css.specificity("a[href^=http]"));
        assertArrayEquals(new int[] {0, 0, 2}, Css.specificity("p::before"));
    }
}
