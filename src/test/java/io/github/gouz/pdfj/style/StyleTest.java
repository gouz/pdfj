package io.github.gouz.pdfj.style;

import io.github.gouz.pdfj.css.Css;
import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.style.Style.BgSize;
import io.github.gouz.pdfj.style.Style.BorderStyle;
import io.github.gouz.pdfj.style.Style.BoxArea;
import io.github.gouz.pdfj.style.Style.Gradient;
import io.github.gouz.pdfj.style.Style.GradientKind;
import io.github.gouz.pdfj.style.Style.LineHeight;
import io.github.gouz.pdfj.style.Style.Shadow;
import io.github.gouz.pdfj.style.Style.VAlign;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleTest {
    @BeforeAll
    static void fonts() {
        if (Fonts.registry().faces().isEmpty()) Fonts.init(List.of(Path.of(System.getenv().getOrDefault("FONTS_DIR", "/usr/share/fonts"))));
    }

    static Style computed(String css) {
        return Style.compute(Style.root("Helvetica", 12), Css.parseDeclarations(css), 12);
    }

    static Len len(String v) {
        return Values.len(v, 12, 12);
    }

    @Test
    void lengths() {
        assertEquals(Len.px(10), len("10pt"));
        assertEquals(Len.px(12), len("16px")); // 1px = 0.75pt
        assertEquals(len("1in"), len("96px"));
        Values.setViewport(400, 600);
        assertEquals(Len.px(200), len("50vw"));
        assertEquals(Len.px(60), len("10vh"));
        assertEquals(Len.px(40), len("10vmin"));
        assertEquals(Len.px(500), len("calc(100vh - 100pt)"));
        assertEquals(Len.px(24), len("2em"));
        assertEquals(Len.pct(50), len("50%"));
        assertEquals(Len.px(72), len("1in"));
        assertEquals(Len.px(0), len("0"));
        assertNull(len("red"));
    }

    @Test
    void shorthands() {
        Style s = computed("margin: 1pt 2pt; padding: 1em; border: medium dashed #f00; border-left: none; flex: 1");
        assertArrayEquals(new Len[] {Len.px(1), Len.px(2), Len.px(1), Len.px(2)}, s.margin);
        assertEquals(Len.px(12), s.padding[3]);
        assertEquals(3 * Style.PX, s.borderWidth[0]); // medium = 3px
        assertEquals(BorderStyle.DASHED, s.borderStyle[1]);
        assertEquals(BorderStyle.NONE, s.borderStyle[3]);
        assertEquals(new Rgba(255, 0, 0, 255), s.borderColor[0]);
        assertEquals(1, s.flexGrow);
        assertEquals(1, s.flexShrink);
        assertEquals(Len.px(0), s.flexBasis);
    }

    @Test
    void fontSizeBeforeEm() {
        // em lengths use the element's own font-size, whatever the declaration order
        assertEquals(Len.px(20), computed("padding: 1em; font-size: 20pt").padding[0]);
        Style s = computed("font: italic bold 10pt/2 serif");
        assertTrue(s.italic && s.fontWeight == 700 && s.fontSize == 10 && s.lineHeight.equals(new LineHeight(LineHeight.Kind.FACTOR, 2)));
    }

    @Test
    void normalKeywords() {
        // `normal` spacings crashed react-pdf; here they are their default
        Style s = computed("gap: normal; letter-spacing: normal; line-height: normal");
        assertArrayEquals(new Len[] {Len.ZERO, Len.ZERO}, s.gap);
        assertEquals(0, s.letterSpacing);
    }

    @Test
    void customProperties() {
        Style root = Style.root("Helvetica", 12);
        Style s = Style.compute(root, Css.parseDeclarations("--gap: 4pt; --pad: calc(var(--gap) * 2); padding: var(--pad); margin: var(--nope, 3pt); width: var(--nope)"), 12);
        assertEquals(Len.px(8), s.padding[0]);
        assertEquals(Len.px(3), s.margin[0]); // fallback
        assertEquals(Len.AUTO, s.size[0]); // undefined, no fallback: declaration dropped
        // inherited by children
        assertEquals("4pt", Style.compute(s, List.of(), 12).vars.get("--gap"));
        // a cycle does not hang
        assertEquals(Len.AUTO, Style.compute(root, Css.parseDeclarations("--a: var(--b); --b: var(--a); width: var(--a)"), 12).size[0]);
    }

    @Test
    void calcAndFriends() {
        java.util.function.Function<String, Len> l = v -> Values.len(v, 10, 12);
        assertEquals(Len.px(30), l.apply("calc(10pt + 2em)"));
        assertEquals(Len.pct(25), l.apply("calc(50% / 2)"));
        assertEquals(Len.calc(-20, 100), l.apply("calc(100% - 20pt)"));
        assertEquals(Len.px(30), l.apply("calc((1rem + 3pt) * 2)"));
        assertEquals(Len.px(-10), l.apply("calc(2 * -5pt)"));
        assertNull(l.apply("calc(10pt * 2pt)"));
        assertEquals(Len.px(10), l.apply("min(10pt, 2em, 50pt)"));
        assertEquals(Len.px(30), l.apply("clamp(5pt, 100pt, 30pt)"));
        assertEquals(180, Len.calc(-20, 100).resolve(200));
    }

    @Test
    void borderRadiusPctAndCalc() {
        Style s = computed("width: 60pt; height: 60pt; border: 3pt solid black; border-radius: 50%");
        assertArrayEquals(new Len[] {Len.pct(50), Len.pct(50), Len.pct(50), Len.pct(50)}, s.radius);
        assertArrayEquals(new double[] {30, 30, 30, 30}, Values.resolveRadius(s.radius, 60, 60));
        // calc with division in border-radius: not split on the `/`
        s = computed("border-radius: calc(80pt / 2)");
        assertArrayEquals(new Len[] {Len.px(40), Len.px(40), Len.px(40), Len.px(40)}, s.radius);
        s = computed("border-radius: 10pt 50%");
        assertArrayEquals(new double[] {10, 30, 10, 30}, Values.resolveRadius(s.radius, 80, 60)); // 50% of min(80, 60)
        assertEquals(Len.pct(25), computed("border-top-left-radius: 25%").radius[0]);
    }

    @Test
    void gradients() {
        Rgba black = Rgba.BLACK;
        Gradient g = Values.gradient("linear-gradient(to right, red, rgb(0, 0, 255) 80%)", 12, 12, black);
        assertEquals(new GradientKind.Linear(90), g.kind());
        assertEquals(List.of(new Style.Stop(new Rgba(255, 0, 0, 255), null), new Style.Stop(new Rgba(0, 0, 255, 255), Len.pct(80))), g.stops());
        assertEquals(new GradientKind.Linear(45), Values.gradient("linear-gradient(45deg, red, blue)", 12, 12, black).kind());
        assertEquals(new GradientKind.LinearCorner(-1, -1), Values.gradient("linear-gradient(to top left, red, blue)", 12, 12, black).kind());
        assertEquals(new GradientKind.Radial(true, Len.pct(0), Len.pct(0)), Values.gradient("radial-gradient(circle at top left, red, blue)", 12, 12, black).kind());
        assertTrue(Values.gradient("repeating-linear-gradient(red 0, blue 10px)", 12, 12, black).repeating());
        assertNull(Values.gradient("linear-gradient(red)", 12, 12, black));
        Style s = computed("background: #eee linear-gradient(red, blue)");
        assertTrue(s.bg.images.get(0).gradient() != null && s.background.equals(new Rgba(238, 238, 238, 255)));
    }

    @Test
    void transforms() {
        assertArrayEquals(new double[] {2, 0, 0, 2, 10, 20}, Values.transformMatrix("translate(10pt, 50%) scale(2)", 100, 40, 12), 1e-9);
        double[] r = Values.transformMatrix("rotate(90deg)", 10, 10, 12);
        assertTrue(Math.abs(r[0]) < 1e-6 && Math.abs(r[1] - 1) < 1e-6 && Math.abs(r[2] + 1) < 1e-6);
        assertNull(Values.transformMatrix("none", 1, 1, 12));
        assertNull(Values.transformMatrix("wobble(3)", 1, 1, 12));
        assertArrayEquals(new Len[] {Len.pct(100), Len.pct(100)}, Values.position("right bottom", 12, 12));
        assertArrayEquals(new Len[] {Len.pct(50), Len.pct(0)}, Values.position("top", 12, 12));
    }

    @Test
    void countersAndVerticalAlign() {
        Style s = computed("counter-reset: chapter section 3; counter-increment: section -1; vertical-align: 3pt");
        assertEquals(List.of(new Style.Counter("chapter", 0), new Style.Counter("section", 3)), s.counterReset);
        assertEquals(List.of(new Style.Counter("section", -1)), s.counterIncrement);
        assertEquals(new VAlign(VAlign.Kind.LENGTH, 3), s.verticalAlign);
        assertEquals("IV", Values.formatCounter(4, "upper-roman"));
        assertEquals("ab", Values.formatCounter(28, "lower-alpha"));
        assertEquals("07", Values.formatCounter(7, "decimal-leading-zero"));
        assertEquals("'→ '", computed("list-style-type: '→ '").listStyle);
    }

    @Test
    void generatedContent() {
        java.util.function.Function<String, String> attr = a -> a.equals("href") ? "https://x.test" : null;
        Values.CounterText counter = (name, sep, style) -> sep != null
            ? Values.formatCounter(1, style) + sep + Values.formatCounter(2, style)
            : name + ":" + Values.formatCounter(3, style);
        assertEquals("(https://x.test)", Values.content("\"(\" attr(href) \")\"", attr, counter));
        assertEquals("\u201C“a'b", Values.content("\"\\201C\" open-quote 'a\\'b'", attr, counter));
        assertEquals("", Values.content("\"\"", attr, counter));
        assertNull(Values.content("none", attr, counter));
        assertEquals("item:3. ", Values.content("counter(item) \". \"", attr, counter));
        assertEquals("item:III", Values.content("counter(item, upper-roman)", attr, counter));
        assertEquals("1.2 ", Values.content("counters(item, \".\") \" \"", attr, counter));
    }

    @Test
    void backgroundsAndShadows() {
        Style s = computed("background: #fff url('img/a.png') no-repeat right 10pt top / 50% auto");
        assertEquals("img/a.png", s.bg.images.get(0).url());
        assertEquals(new Rgba(255, 255, 255, 255), s.background);
        assertArrayEquals(new boolean[] {false, false}, s.bg.repeats.get(0));
        assertEquals(BgSize.size(Len.pct(50), Len.AUTO), s.bg.sizes.get(0));
        assertArrayEquals(new Len[] {Len.calc(-10, 100), Len.pct(0)}, s.bg.positions.get(0));
        s = computed("background: #222 url(a.png) center / contain no-repeat");
        assertEquals(BgSize.CONTAIN, s.bg.sizes.get(0));
        assertArrayEquals(new boolean[] {false, false}, s.bg.repeats.get(0));
        assertEquals(new Rgba(34, 34, 34, 255), s.background);
        s = computed("background-image: url(a.png); background-size: cover; background-position: center; background-repeat: repeat-x");
        assertEquals(BgSize.COVER, s.bg.sizes.get(0));
        assertArrayEquals(new Len[] {Len.pct(50), Len.pct(50)}, s.bg.positions.get(0));
        assertArrayEquals(new boolean[] {true, false}, s.bg.repeats.get(0));
        // several layers, cycled lists, origin / clip
        s = computed("background: url(a.png) no-repeat content-box, linear-gradient(red, blue) padding-box border-box, #eee");
        assertEquals(2, s.bg.images.size());
        assertEquals(List.of(BoxArea.CONTENT, BoxArea.PADDING), s.bg.origins);
        assertEquals(List.of(BoxArea.CONTENT, BoxArea.BORDER), s.bg.clips);
        s = computed("background: linear-gradient(red, blue) fixed; -webkit-background-clip: text");
        assertEquals(List.of(BoxArea.TEXT), s.bg.clips);
        assertEquals(List.of(true), s.bg.fixed);
        s = computed("background-image: url(a.png), url(b.png), url(c.png); background-repeat: no-repeat, repeat");
        assertArrayEquals(new boolean[] {false, false}, Style.Background.nth(s.bg.repeats, 2, new boolean[] {true, true}));
        s = computed("color: red; box-shadow: 0 2pt 4pt rgba(0, 0, 0, .5), inset 1pt 1pt blue, 3pt 3pt 0 2pt");
        assertEquals(new Shadow(0, 2, 4, 0, new Rgba(0, 0, 0, 128), false), s.shadows.get(0));
        assertTrue(s.shadows.get(1).inset() && s.shadows.get(1).color().equals(new Rgba(0, 0, 255, 255)));
        assertEquals(2, s.shadows.get(2).spread());
        assertEquals(new Rgba(255, 0, 0, 255), s.shadows.get(2).color());
        assertNull(computed("box-shadow: 1pt").shadows);
    }

    @Test
    void direction() {
        assertTrue(computed("direction: rtl").rtl);
    }

    @Test
    void gridTemplates() {
        List<GridTemplate.Item> t = GridTemplate.parse("1fr 2em repeat(2, 10pt) minmax(auto, 30%) [a] auto", 10, 10);
        assertEquals(5, t.size());
        assertEquals(GridTemplate.Track.fr(1), t.get(0).tracks().get(0));
        assertEquals(GridTemplate.Track.fixed(Len.px(20)), t.get(1).tracks().get(0));
        assertEquals(2, t.get(2).repeat());
        assertEquals(GridTemplate.MaxKind.FIXED, t.get(3).tracks().get(0).max());
        assertEquals(GridTemplate.Track.AUTO, t.get(4).tracks().get(0));
        assertEquals(new GridTemplate.Placement(GridTemplate.Line.line(1), GridTemplate.Line.line(-1)), GridTemplate.placement("1 / -1"));
        assertEquals(new GridTemplate.Placement(GridTemplate.Line.span(2), GridTemplate.Line.AUTO), GridTemplate.placement("span 2"));
    }
}
