package io.github.gouz.pdfj.font;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One font face: its family, weight and style, and the metrics text layout needs, read once and
 * immutable (shared by every render thread). The font file itself is parsed again per thread for
 * embedding (see {@link Fonts#pdfFont}).
 */
public final class FontFace {
    /** the font file (a collection for .ttc) and the index of this face in it */
    public final byte[] data;
    public final int index;
    public final boolean collection;
    public final String family;
    public final String postScriptName;
    public final int weight;
    public final boolean italic;
    public final int unitsPerEm;
    /** hhea, in font units: ascender (positive), descender (negative), line gap */
    public final int ascender, descender, lineGap;
    public final int underlinePosition, underlineThickness, strikeoutPosition, strikeoutSize, xHeight;
    /** for the PDF font descriptor: head bounding box, post italic angle, OS/2 cap height, fixed pitch */
    public final int xMin, yMin, xMax, yMax, capHeight;
    public final float italicAngle;
    public final boolean fixedPitch;
    private final int[] advances;
    private final CmapLookup cmap;
    /** a face of the font directories (shared by every document), not a document's @font-face */
    public final boolean shared;

    private FontFace(byte[] data, int index, boolean collection, TrueTypeFont ttf, boolean shared) throws IOException {
        this.data = data;
        this.index = index;
        this.collection = collection;
        this.shared = shared;
        NamingTable naming = ttf.getNaming();
        String fam = naming == null ? null : naming.getName(16, 3, 1, 0x409);
        if (fam == null && naming != null) fam = naming.getFontFamily();
        this.family = fam == null ? ttf.getName() : fam;
        this.postScriptName = ttf.getName();
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        this.weight = os2 == null || os2.getWeightClass() <= 0 ? 400 : os2.getWeightClass();
        boolean it = os2 != null && (os2.getFsSelection() & 1) != 0;
        if (ttf.getHeader() != null && (ttf.getHeader().getMacStyle() & 2) != 0) it = true;
        this.italic = it;
        this.unitsPerEm = ttf.getUnitsPerEm();
        // line metrics as FreeType (and skrifa) choose them: OS/2 typo metrics if USE_TYPO_METRICS,
        // else hhea, else (hhea zero) typo, else win
        HorizontalHeaderTable hhea = ttf.getHorizontalHeader();
        int asc = hhea.getAscender(), desc = hhea.getDescender(), gap = hhea.getLineGap();
        if (os2 != null && (os2.getFsSelection() & 0x80) != 0) {
            asc = os2.getTypoAscender();
            desc = os2.getTypoDescender();
            gap = os2.getTypoLineGap();
        } else if (asc == 0 && desc == 0 && os2 != null) {
            if (os2.getTypoAscender() != 0 || os2.getTypoDescender() != 0) {
                asc = os2.getTypoAscender();
                desc = os2.getTypoDescender();
                gap = os2.getTypoLineGap();
            } else {
                asc = os2.getWinAscent();
                desc = -os2.getWinDescent();
                gap = 0;
            }
        }
        this.ascender = asc;
        this.descender = desc;
        this.lineGap = gap;
        PostScriptTable post = ttf.getPostScript();
        this.underlinePosition = post == null ? -unitsPerEm / 10 : (int) post.getUnderlinePosition();
        this.underlineThickness = post == null || post.getUnderlineThickness() <= 0 ? unitsPerEm / 20 : (int) post.getUnderlineThickness();
        this.strikeoutPosition = os2 == null || os2.getStrikeoutPosition() == 0 ? unitsPerEm * 3 / 10 : os2.getStrikeoutPosition();
        this.strikeoutSize = os2 == null || os2.getStrikeoutSize() <= 0 ? underlineThickness : os2.getStrikeoutSize();
        // 0: none (OS/2 before version 2), estimated from the ascent like browsers
        this.xHeight = os2 != null && os2.getVersion() >= 2 && os2.getHeight() > 0 ? os2.getHeight() : 0;
        var head = ttf.getHeader();
        this.xMin = head == null ? 0 : head.getXMin();
        this.yMin = head == null ? descender : head.getYMin();
        this.xMax = head == null ? unitsPerEm : head.getXMax();
        this.yMax = head == null ? ascender : head.getYMax();
        this.capHeight = os2 != null && os2.getVersion() >= 2 && os2.getCapHeight() > 0 ? os2.getCapHeight() : ascender;
        this.italicAngle = post == null ? 0 : post.getItalicAngle();
        this.fixedPitch = post != null && post.getIsFixedPitch() != 0;
        int n = ttf.getNumberOfGlyphs();
        HorizontalMetricsTable hmtx = ttf.getHorizontalMetrics();
        this.advances = new int[n];
        for (int g = 0; g < n; g++) advances[g] = hmtx == null ? 0 : hmtx.getAdvanceWidth(g);
        this.cmap = ttf.getUnicodeCmapLookup(false);
    }

    private FontFace(FontFace base, String family, Integer weight, Boolean italic) {
        this.data = base.data;
        this.index = base.index;
        this.collection = base.collection;
        this.shared = false;
        this.family = family;
        this.postScriptName = base.postScriptName;
        this.weight = weight == null ? base.weight : weight;
        this.italic = italic == null ? base.italic : italic;
        this.unitsPerEm = base.unitsPerEm;
        this.ascender = base.ascender;
        this.descender = base.descender;
        this.lineGap = base.lineGap;
        this.underlinePosition = base.underlinePosition;
        this.underlineThickness = base.underlineThickness;
        this.strikeoutPosition = base.strikeoutPosition;
        this.strikeoutSize = base.strikeoutSize;
        this.xHeight = base.xHeight;
        this.xMin = base.xMin;
        this.yMin = base.yMin;
        this.xMax = base.xMax;
        this.yMax = base.yMax;
        this.capHeight = base.capHeight;
        this.italicAngle = base.italicAngle;
        this.fixedPitch = base.fixedPitch;
        this.advances = base.advances;
        this.cmap = base.cmap;
    }

    /** The same face under another family / weight / style (a document's @font-face). */
    public FontFace as(String family, Integer weight, Boolean italic) {
        return new FontFace(this, family, weight, italic);
    }

    /**
     * Every face of an sfnt file (TrueType, OpenType with TrueType outlines, collection).
     * OpenType fonts with CFF outlines are skipped: they cannot be embedded here.
     */
    public static List<FontFace> load(byte[] data, boolean shared) throws IOException {
        List<FontFace> out = new ArrayList<>();
        if (data.length >= 4 && data[0] == 't' && data[1] == 't' && data[2] == 'c' && data[3] == 'f') {
            try (TrueTypeCollection ttc = new TrueTypeCollection(new java.io.ByteArrayInputStream(data))) {
                int[] i = {0};
                ttc.processAllFonts(ttf -> {
                    int idx = i[0]++;
                    if (embeddable(ttf)) out.add(new FontFace(data, idx, true, ttf, shared));
                });
            }
            return out;
        }
        TrueTypeFont ttf = parse(data);
        if (embeddable(ttf)) out.add(new FontFace(data, 0, false, ttf, shared));
        return out;
    }

    private static boolean embeddable(TrueTypeFont ttf) throws IOException {
        if (ttf instanceof OpenTypeFont otf && otf.isPostScript()) return false;
        return ttf.getHorizontalHeader() != null && ttf.getUnitsPerEm() > 0 && ttf.getNumberOfGlyphs() > 0;
    }

    static TrueTypeFont parse(byte[] data) throws IOException {
        boolean otto = data.length >= 4 && data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O';
        RandomAccessReadBuffer in = new RandomAccessReadBuffer(data);
        return otto ? new OTFParser().parse(in) : new TTFParser().parse(in);
    }

    /** A fresh parse of this face, for one thread (fontbox fonts are not thread-safe). */
    TrueTypeFont parseAgain() throws IOException {
        if (!collection) return parse(data);
        TrueTypeCollection ttc = new TrueTypeCollection(new java.io.ByteArrayInputStream(data));
        TrueTypeFont[] found = {null};
        int[] i = {0};
        ttc.processAllFonts(ttf -> {
            if (i[0]++ == index) found[0] = ttf;
        });
        return found[0];
    }

    /** glyph of a code point, 0 when the font has none */
    public int glyph(int codePoint) {
        return cmap == null ? 0 : cmap.getGlyphId(codePoint);
    }

    public boolean has(int codePoint) {
        return glyph(codePoint) != 0;
    }

    /** advance of a glyph, in font units */
    public int advance(int glyph) {
        return glyph >= 0 && glyph < advances.length ? advances[glyph] : 0;
    }

    public int numGlyphs() {
        return advances.length;
    }

    @Override
    public String toString() {
        return family + " " + weight + (italic ? " italic" : "");
    }
}
