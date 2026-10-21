package io.github.gouz.pdfj.text;

import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.style.Style;
import io.github.gouz.pdfj.style.Style.WhiteSpace;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The inline content of a block, ready to break into lines: white space processed, one font per
 * character (with fallback), advances, line break opportunities and bidi levels.
 */
public final class Paragraph {
    public static final byte TEXT = 0, SPACE = 1, PSPACE = 2, BREAK = 3, BOX = 4, ZW = 5;

    /** A run of text with one style. */
    public record Run(Style style, Brush brush, FontFace primary) {}

    public final int n;
    /** logical code points: {@code \n} for forced breaks, U+FFFC for inline boxes */
    public final int[] cp;
    public final byte[] kind;
    public final int[] run;
    public final List<Run> runs;
    public final FontFace[] face;
    /** glyph to draw; 0 = none */
    public final int[] glyph;
    /** advance in pt (inline boxes: set per layout) */
    public final double[] adv;
    /** a line may break before this index */
    public final boolean[] breakBefore;
    public final byte[] level;
    public final boolean bidi;
    /** inline boxes: the node of each BOX index */
    public final Object[] boxes;
    public final Style container;
    public final FontFace containerFace;

    private Paragraph(int[] cp, byte[] kind, int[] run, List<Run> runs, Object[] boxes, Style container) {
        this.n = cp.length;
        this.cp = cp;
        this.kind = kind;
        this.run = run;
        this.runs = runs;
        this.boxes = boxes;
        this.container = container;
        this.containerFace = primaryFace(container);
        this.face = new FontFace[n];
        this.glyph = new int[n];
        this.adv = new double[n];
        this.breakBefore = new boolean[n + 1];
        this.level = new byte[n];
        // Arabic contextual forms, in logical order
        int[] shaped = cp;
        boolean[] skip = new boolean[n];
        for (int c : cp) {
            if (Arabic.isArabic(c)) {
                shaped = new int[n];
                Arabic.shape(cp, shaped, skip);
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            Run r = runs.get(run[i]);
            FontFace primary = r.primary;
            Style s = r.style;
            double size = s.fontSize;
            switch (kind[i]) {
                case TEXT -> {
                    if (skip[i]) {
                        face[i] = i > 0 ? face[i - 1] : primary;
                        continue;
                    }
                    int c = cp[i];
                    FontFace f = primary;
                    if (f == null || !f.has(c)) {
                        int type = Character.getType(c);
                        boolean mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK;
                        if (mark && i > 0 && face[i - 1] != null && face[i - 1].has(c)) f = face[i - 1];
                        else {
                            FontFace any = Fonts.anyWith(c, s.fontWeight, s.italic);
                            if (any != null) f = any;
                        }
                    }
                    face[i] = f;
                    if (f == null) continue;
                    int g = shaped[i] != c ? f.glyph(shaped[i]) : 0;
                    if (g == 0) g = f.glyph(c);
                    glyph[i] = g;
                    adv[i] = f.advance(g) * size / f.unitsPerEm + s.letterSpacing + (c == 0xA0 ? s.wordSpacing : 0);
                }
                case SPACE, PSPACE -> {
                    FontFace f = primary != null && primary.has(' ') ? primary : Fonts.anyWith(' ', s.fontWeight, s.italic);
                    if (f == null) f = primary;
                    face[i] = f;
                    if (f == null) continue;
                    glyph[i] = f.glyph(' ');
                    adv[i] = f.advance(glyph[i]) * size / f.unitsPerEm + s.letterSpacing + s.wordSpacing;
                }
                default -> face[i] = primary;
            }
        }
        // line break opportunities (UAX #14), on UTF-16
        StringBuilder sb = new StringBuilder(n + 8);
        int[] charToCp = new int[n * 2 + 1];
        for (int i = 0; i < n; i++) {
            int c = cp[i] == '\n' ? ' ' : cp[i];
            int at = sb.length();
            sb.appendCodePoint(c);
            for (int k = at; k < sb.length(); k++) charToCp[k] = i;
        }
        charToCp[sb.length()] = n;
        String str = sb.toString();
        BreakIterator it = BreakIterator.getLineInstance(Locale.ROOT);
        it.setText(str);
        for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) {
            if (b > 0 && b < str.length()) breakBefore[charToCp[b]] = true;
        }
        for (int i = 0; i < n; i++) {
            if (kind[i] == BOX) {
                breakBefore[i] = true;
                breakBefore[i + 1] = true;
            }
            if (kind[i] == ZW && cp[i] == 0x200B) breakBefore[i + 1] = true;
        }
        for (int i = 1; i < n; i++) {
            WhiteSpace ws = runs.get(run[i - 1]).style.whiteSpace;
            if (ws == WhiteSpace.NOWRAP || ws == WhiteSpace.PRE) breakBefore[i] = false;
        }
        breakBefore[0] = false;
        breakBefore[n] = true;
        // bidi levels
        boolean rtl = container.rtl;
        char[] chars = str.toCharArray();
        boolean any = rtl || Bidi.requiresBidi(chars, 0, chars.length);
        boolean odd = false;
        if (any && chars.length > 0) {
            Bidi b = new Bidi(str, rtl ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT);
            for (int k = 0; k < chars.length; k++) {
                int i = charToCp[k];
                level[i] = (byte) b.getLevelAt(k);
                odd |= (level[i] & 1) != 0;
            }
        }
        this.bidi = odd;
    }

    static FontFace primaryFace(Style s) {
        FontFace f = Fonts.match(s.fontFamily, s.fontWeight, s.italic);
        if (f == null && Fonts.registry().fallback != null) f = Fonts.match(Fonts.registry().fallback, s.fontWeight, s.italic);
        if (f == null && !Fonts.registry().faces().isEmpty()) f = Fonts.registry().faces().get(0);
        return f;
    }

    /** Build from inline pieces in {@code container}; floats are not part of the text. */
    public static Paragraph build(List<Piece> pieces, Style container) {
        IntList cps = new IntList();
        ByteList kinds = new ByteList();
        IntList runIdx = new IntList();
        List<Object> boxes = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        Map<Style, FontFace> faces = new HashMap<>();
        boolean prevSpace = false;
        int column = 0;
        for (Piece p : pieces) {
            switch (p) {
                case Piece.Text t -> {
                    Style s = t.style();
                    int ri = runs.size();
                    runs.add(new Run(s, t.brush(), faces.computeIfAbsent(s, Paragraph::primaryFace)));
                    WhiteSpace ws = s.whiteSpace;
                    boolean collapse = ws == WhiteSpace.NORMAL || ws == WhiteSpace.NOWRAP || ws == WhiteSpace.PRE_LINE;
                    boolean keepNewlines = ws == WhiteSpace.PRE || ws == WhiteSpace.PRE_WRAP || ws == WhiteSpace.PRE_LINE;
                    String text = t.text();
                    for (int k = 0; k < text.length(); ) {
                        int c = text.codePointAt(k);
                        k += Character.charCount(c);
                        if (c == '\r') continue;
                        if (c == '\n' && keepNewlines) {
                            add(cps, kinds, runIdx, boxes, '\n', BREAK, ri, null);
                            prevSpace = false;
                            column = 0;
                            continue;
                        }
                        if (collapse && (c == ' ' || c == '\t' || c == '\n' || c == '\f')) {
                            if (prevSpace) continue;
                            add(cps, kinds, runIdx, boxes, ' ', SPACE, ri, null);
                            prevSpace = true;
                            column++;
                            continue;
                        }
                        if (!collapse && c == '\t') {
                            int spaces = 8 - column % 8;
                            for (int q = 0; q < spaces; q++) add(cps, kinds, runIdx, boxes, ' ', PSPACE, ri, null);
                            column += spaces;
                            prevSpace = false;
                            continue;
                        }
                        if (!collapse && c == ' ') {
                            add(cps, kinds, runIdx, boxes, ' ', PSPACE, ri, null);
                            column++;
                            prevSpace = false;
                            continue;
                        }
                        int type = Character.getType(c);
                        if (c == 0x200B || c == 0xAD || c < 0x20 || c == 0x7F || type == Character.FORMAT && c != 0x200D && c != 0x200C) {
                            add(cps, kinds, runIdx, boxes, c, ZW, ri, null);
                            continue;
                        }
                        add(cps, kinds, runIdx, boxes, c, TEXT, ri, null);
                        prevSpace = false;
                        column++;
                    }
                }
                case Piece.Break b -> {
                    int ri = runs.size();
                    runs.add(new Run(b.style(), b.brush(), faces.computeIfAbsent(b.style(), Paragraph::primaryFace)));
                    add(cps, kinds, runIdx, boxes, '\n', BREAK, ri, null);
                    prevSpace = false;
                    column = 0;
                }
                case Piece.Box b -> {
                    int ri = runs.size();
                    runs.add(new Run(b.style(), null, faces.computeIfAbsent(b.style(), Paragraph::primaryFace)));
                    add(cps, kinds, runIdx, boxes, 0xFFFC, BOX, ri, b.node());
                    prevSpace = false;
                    column++;
                }
                case Piece.Float f -> {}
            }
        }
        if (runs.isEmpty()) runs.add(new Run(container, null, primaryFace(container)));
        return new Paragraph(cps.toArray(), kinds.toArray(), runIdx.toArray(), runs, boxes.toArray(), container);
    }

    private static void add(IntList cps, ByteList kinds, IntList runs, List<Object> boxes, int c, byte kind, int run, Object box) {
        cps.add(c);
        kinds.add(kind);
        runs.add(run);
        boxes.add(box);
    }

    /** the number of visible (non white space) characters in [start, end) */
    public int visibleChars(int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) if (kind[i] == TEXT && !Character.isWhitespace(cp[i])) count++;
        return count;
    }

    static final class IntList {
        int[] a = new int[64];
        int size;

        void add(int v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }

    static final class ByteList {
        byte[] a = new byte[64];
        int size;

        void add(byte v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        byte[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }
}
