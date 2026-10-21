package io.github.gouz.pdfj.render;

import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.font.Fonts;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A font face embedded in one PDF: a Type0 font (Identity-H, the glyph ids as CIDs) over a subset
 * TrueType font, with its widths and a ToUnicode map built from the text actually drawn.
 *
 * <p>Written here rather than with PDFBox's {@code PDType0Font.load}, which parses the whole font
 * again for every document: the font is parsed once per thread, and only subset per document.
 */
final class EmbeddedFont {
    final FontFace face;
    /** the Type0 font dictionary, filled in by {@link #finish} */
    final COSDictionary dict = new COSDictionary();
    /** glyph -> the character it was drawn for */
    private final Map<Integer, Integer> unicode = new TreeMap<>();

    EmbeddedFont(FontFace face) {
        this.face = face;
    }

    void use(int glyph, int codePoint) {
        unicode.putIfAbsent(glyph, codePoint);
    }

    /** fontbox fonts of the shared faces, parsed once per render thread */
    private static final ThreadLocal<Map<FontFace, TrueTypeFont>> PARSED = ThreadLocal.withInitial(HashMap::new);

    private TrueTypeFont parsed() throws IOException {
        if (!face.shared) return Fonts.pdfFont(face);
        Map<FontFace, TrueTypeFont> cache = PARSED.get();
        TrueTypeFont t = cache.get(face);
        if (t == null) {
            t = Fonts.pdfFont(face);
            cache.put(face, t);
        }
        return t;
    }

    private static final List<String> TABLES = List.of("head", "hhea", "loca", "maxp", "cvt ", "prep", "glyf", "hmtx", "fpgm", "gasp");

    /** the subset font, its descriptor, widths, CID to GID map and ToUnicode map */
    void finish(PDDocument doc) throws IOException {
        TrueTypeFont ttf = parsed();
        TTFSubsetter sub = new TTFSubsetter(ttf, TABLES);
        sub.addGlyphIds(unicode.keySet());
        String tag = tag();
        sub.setPrefix(tag);
        ByteArrayOutputStream fontFile = new ByteArrayOutputStream();
        sub.writeToStream(fontFile);
        Map<Integer, Integer> newToOld = sub.getGIDMap();
        String name = tag + "+" + (face.postScriptName == null ? "Font" : face.postScriptName.replace(' ', '-'));
        double k = 1000.0 / face.unitsPerEm;

        COSDictionary descriptor = new COSDictionary();
        descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
        descriptor.setName(COSName.FONT_NAME, name);
        int flags = 4; // symbolic: the glyphs are addressed by id
        if (face.fixedPitch) flags |= 1;
        if (face.italic) flags |= 64;
        descriptor.setInt(COSName.FLAGS, flags);
        COSArray bbox = new COSArray();
        for (int v : new int[] {face.xMin, face.yMin, face.xMax, face.yMax}) bbox.add(COSInteger.get(Math.round(v * k)));
        descriptor.setItem(COSName.FONT_BBOX, bbox);
        descriptor.setItem(COSName.ITALIC_ANGLE, new COSFloat(face.italicAngle));
        descriptor.setInt(COSName.ASCENT, (int) Math.round(face.ascender * k));
        descriptor.setInt(COSName.DESCENT, (int) Math.round(face.descender * k));
        descriptor.setInt(COSName.CAP_HEIGHT, (int) Math.round(face.capHeight * k));
        descriptor.setInt(COSName.STEM_V, face.weight >= 600 ? 120 : 80);
        byte[] fontBytes = fontFile.toByteArray();
        PDStream file = new PDStream(doc, new ByteArrayInputStream(fontBytes), COSName.FLATE_DECODE);
        file.getCOSObject().setInt(COSName.LENGTH1, fontBytes.length);
        descriptor.setItem(COSName.FONT_FILE2, file);

        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cid.setName(COSName.BASE_FONT, name);
        COSDictionary info = new COSDictionary();
        info.setItem(COSName.REGISTRY, new COSString("Adobe"));
        info.setItem(COSName.ORDERING, new COSString("Identity"));
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, descriptor);
        cid.setInt(COSName.DW, (int) Math.round(face.advance(0) * k));
        // widths of the glyphs drawn: [cid [w] ...]
        COSArray w = new COSArray();
        for (int g : unicode.keySet()) {
            w.add(COSInteger.get(g));
            COSArray one = new COSArray();
            one.add(COSInteger.get(Math.round(face.advance(g) * k)));
            w.add(one);
        }
        cid.setItem(COSName.W, w);
        // CID (the original glyph id) -> glyph id in the subset
        int maxCid = 0;
        for (int old : newToOld.values()) maxCid = Math.max(maxCid, old);
        byte[] map = new byte[(maxCid + 1) * 2];
        for (Map.Entry<Integer, Integer> e : newToOld.entrySet()) {
            int old = e.getValue(), gid = e.getKey();
            map[old * 2] = (byte) (gid >> 8);
            map[old * 2 + 1] = (byte) gid;
        }
        cid.setItem(COSName.CID_TO_GID_MAP, new PDStream(doc, new ByteArrayInputStream(map), COSName.FLATE_DECODE).getCOSObject());

        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE0);
        dict.setName(COSName.BASE_FONT, name);
        dict.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        COSArray descendants = new COSArray();
        descendants.add(cid);
        dict.setItem(COSName.DESCENDANT_FONTS, descendants);
        COSStream toUnicode = new PDStream(doc, new ByteArrayInputStream(toUnicode().getBytes(StandardCharsets.US_ASCII)), COSName.FLATE_DECODE).getCOSObject();
        dict.setItem(COSName.TO_UNICODE, toUnicode);
    }

    /** a subset tag from the glyphs: six capital letters */
    private String tag() {
        long h = face.postScriptName == null ? 7 : face.postScriptName.hashCode();
        for (int g : unicode.keySet()) h = h * 31 + g;
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('A' + Math.floorMod(h, 26)));
            h /= 26;
        }
        return sb.toString();
    }

    private String toUnicode() {
        StringBuilder sb = new StringBuilder();
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
            .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
            .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
            .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        List<Map.Entry<Integer, Integer>> entries = List.copyOf(unicode.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            List<Map.Entry<Integer, Integer>> chunk = entries.subList(i, Math.min(entries.size(), i + 100));
            sb.append(chunk.size()).append(" beginbfchar\n");
            for (Map.Entry<Integer, Integer> e : chunk) {
                sb.append(String.format("<%04X> <", e.getKey()));
                for (char c : Character.toChars(e.getValue())) sb.append(String.format("%04X", (int) c));
                sb.append(">\n");
            }
            sb.append("endbfchar\n");
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return sb.toString();
    }
}
