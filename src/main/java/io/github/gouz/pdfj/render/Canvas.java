package io.github.gouz.pdfj.render;

import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.layout.ImageKind;
import io.github.gouz.pdfj.style.Rgba;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A PDF content stream being written (a page, or a form XObject for a transparency group), in a
 * y-down coordinate system: the page's CTM flips the axis once, text and images flip back.
 */
public final class Canvas {
    /** What a document's pages share: embedded fonts, images, graphics states. */
    static final class Shared {
        final PDDocument doc;
        final Map<FontFace, EmbeddedFont> fonts = new HashMap<>();
        final Map<EmbeddedFont, COSName> fontNames = new HashMap<>();
        final Map<Object, PDImageXObject> images = new IdentityHashMap<>();
        final Map<Object, PDFormXObject> svgs = new IdentityHashMap<>();
        final Map<Integer, PDExtendedGraphicsState> alphas = new HashMap<>();

        Shared(PDDocument doc) {
            this.doc = doc;
        }

        /** embed the fonts, subset to the glyphs drawn */
        void finish() throws IOException {
            for (EmbeddedFont f : fonts.values()) f.finish(doc);
        }
    }

    final Shared shared;
    final PDResources resources = new PDResources();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    private final StringBuilder sb = new StringBuilder(256);

    Canvas(Shared shared) {
        this.shared = shared;
    }

    // ---- output

    private void flushSb() {
        if (sb.isEmpty()) return;
        byte[] b = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
        sb.setLength(0);
    }

    byte[] bytes() {
        flushSb();
        return out.toByteArray();
    }

    private Canvas num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0;
        if (Math.abs(v) > 1e7) v = Math.signum(v) * 1e7;
        long r = Math.round(v * 10000);
        if (r % 10000 == 0) {
            sb.append(r / 10000);
        } else {
            if (r < 0) {
                sb.append('-');
                r = -r;
            }
            sb.append(r / 10000).append('.');
            String frac = String.format("%04d", r % 10000);
            int end = frac.length();
            while (end > 0 && frac.charAt(end - 1) == '0') end--;
            sb.append(frac, 0, end);
        }
        sb.append(' ');
        return this;
    }

    private void op(String o) {
        sb.append(o).append('\n');
        if (sb.length() > 4096) flushSb();
    }

    private void name(COSName n) {
        sb.append('/').append(n.getName()).append(' ');
    }

    // ---- state

    void save() {
        op("q");
    }

    void restore() {
        op("Q");
    }

    void transform(double a, double b, double c, double d, double e, double f) {
        num(a).num(b).num(c).num(d).num(e).num(f);
        op("cm");
    }

    void translate(double x, double y) {
        if (x != 0 || y != 0) transform(1, 0, 0, 1, x, y);
    }

    /** non-stroking alpha (and stroking), 0..1 */
    void alpha(double a) {
        int key = (int) Math.round(Math.max(0, Math.min(1, a)) * 1000);
        if (key >= 1000) return;
        PDExtendedGraphicsState gs = shared.alphas.computeIfAbsent(key, k -> {
            PDExtendedGraphicsState s = new PDExtendedGraphicsState();
            s.setNonStrokingAlphaConstant(k / 1000f);
            s.setStrokingAlphaConstant(k / 1000f);
            return s;
        });
        name(resources.add(gs));
        op("gs");
    }

    void graphicsState(PDExtendedGraphicsState gs) {
        name(resources.add(gs));
        op("gs");
    }

    void fillColor(Rgba c) {
        num(c.r() / 255.0).num(c.g() / 255.0).num(c.b() / 255.0);
        op("rg");
        if (c.a() < 255) alpha(c.a() / 255.0);
    }

    void strokeColor(Rgba c) {
        num(c.r() / 255.0).num(c.g() / 255.0).num(c.b() / 255.0);
        op("RG");
        if (c.a() < 255) alpha(c.a() / 255.0);
    }

    void lineWidth(double w) {
        num(w);
        op("w");
    }

    void lineCap(int cap) {
        sb.append(cap).append(' ');
        op("J");
    }

    void dash(double[] array) {
        sb.append('[');
        for (double d : array) num(d);
        sb.append("] 0 ");
        op("d");
    }

    // ---- paths

    void rect(double x, double y, double w, double h) {
        num(x).num(y).num(Math.max(0, w)).num(Math.max(0, h));
        op("re");
    }

    void moveTo(double x, double y) {
        num(x).num(y);
        op("m");
    }

    void lineTo(double x, double y) {
        num(x).num(y);
        op("l");
    }

    void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) {
        num(x1).num(y1).num(x2).num(y2).num(x3).num(y3);
        op("c");
    }

    void close() {
        op("h");
    }

    void fill() {
        op("f");
    }

    void stroke() {
        op("S");
    }

    void clip() {
        op("W n");
    }

    void shading(PDShading sh) {
        name(resources.add(sh));
        op("sh");
    }

    // ---- text

    /** the font's resource name in this canvas (one font object per document) */
    private COSName font(EmbeddedFont f) {
        COSName n = shared.fontNames.computeIfAbsent(f, k -> COSName.getPDFName("F" + (shared.fontNames.size() + 1)));
        COSDictionary dict = resources.getCOSObject().getCOSDictionary(COSName.FONT);
        if (dict == null) {
            dict = new COSDictionary();
            resources.getCOSObject().setItem(COSName.FONT, dict);
        }
        if (!dict.containsKey(n)) dict.setItem(n, f.dict);
        return n;
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * Glyphs at their advances (pt), from (x, baseline y). {@code clip}: as a clipping path (render mode 7)
     * for a gradient to fill.
     */
    void glyphs(FontFace face, double size, double x, double y, int[] glyphs, double[] advances, int[] codes, boolean clip) {
        EmbeddedFont f = shared.fonts.computeIfAbsent(face, EmbeddedFont::new);
        op("BT");
        name(font(f));
        num(size);
        op("Tf");
        if (clip) op("7 Tr");
        num(1).num(0).num(0).num(-1).num(x).num(y);
        op("Tm");
        sb.append('[');
        boolean open = false;
        double scale = 1000.0 / size;
        for (int i = 0; i < glyphs.length; i++) {
            int g = glyphs[i];
            double natural = face.advance(g) * size / face.unitsPerEm;
            if (g == 0) {
                // nothing to draw: move by its advance
                if (open) {
                    sb.append('>');
                    open = false;
                }
                num(-advances[i] * scale);
                continue;
            }
            f.use(g, codes[i]);
            if (!open) {
                sb.append('<');
                open = true;
            }
            sb.append(HEX[g >> 12 & 15]).append(HEX[g >> 8 & 15]).append(HEX[g >> 4 & 15]).append(HEX[g & 15]);
            double adj = (natural - advances[i]) * scale;
            if (Math.abs(adj) > 0.01) {
                sb.append('>');
                open = false;
                num(adj);
            }
        }
        if (open) sb.append('>');
        sb.append("] ");
        op("TJ");
        op("ET");
    }

    // ---- images

    /** an image filling the unit square transformed to (x, y, w, h) */
    void image(ImageKind.Raster r, double x, double y, double w, double h) {
        PDImageXObject img = shared.images.computeIfAbsent(r, k -> {
            try {
                if (r.file() == null) return LosslessFactory.createFromImage(shared.doc, r.image());
                byte[] f = r.file();
                if ((f[0] & 255) == 0xFF) return JPEGFactory.createFromByteArray(shared.doc, f);
                // PNG: its data as is when PDF can take it, else decoded and compressed again (once, then cached)
                String key = ImageCache.key(f);
                PDImageXObject cached = ImageCache.get(key, shared.doc);
                if (cached != null) return cached;
                PDImageXObject made = PDImageXObject.createFromByteArray(shared.doc, f, "image.png");
                ImageCache.put(key, made);
                return made;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
        if (img == null) return;
        drawImage(img, x, y, w, h);
    }

    void bitmap(BufferedImage image, double x, double y, double w, double h) {
        try {
            drawImage(LosslessFactory.createFromImage(shared.doc, image), x, y, w, h);
        } catch (IOException e) {
            // skip the bitmap
        }
    }

    private void drawImage(PDImageXObject img, double x, double y, double w, double h) {
        save();
        transform(w, 0, 0, -h, x, y + h);
        name(resources.add(img));
        op("Do");
        restore();
    }

    /** an SVG drawing scaled to (x, y, w, h) */
    void svg(ImageKind.Svg s, double x, double y, double w, double h) {
        PDFormXObject form = shared.svgs.computeIfAbsent(s, k -> Svgs.form(shared.doc, s));
        if (form == null) return;
        save();
        transform(w / s.width(), 0, 0, -h / s.height(), x, y + h);
        name(resources.add(form));
        op("Do");
        restore();
    }

    // ---- groups

    /** A canvas for a transparency group: drawn into a form, then painted with an alpha. */
    Canvas group() {
        return new Canvas(shared);
    }

    void paintGroup(Canvas g, double alpha) {
        try {
            PDFormXObject form = g.toForm(true);
            save();
            alpha(alpha);
            name(resources.add(form));
            op("Do");
            restore();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** this canvas as a form XObject (y-down coordinates of the canvas it is drawn on) */
    PDFormXObject toForm(boolean transparencyGroup) throws IOException {
        PDStream stream = new PDStream(shared.doc, new ByteArrayInputStream(bytes()), COSName.FLATE_DECODE);
        PDFormXObject form = new PDFormXObject(stream);
        form.setResources(resources);
        form.setBBox(new PDRectangle(-100000, -100000, 200000, 200000));
        if (transparencyGroup) {
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.TYPE, COSName.GROUP);
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            form.getCOSObject().setItem(COSName.GROUP, group);
        }
        return form;
    }

    /** A luminosity soft mask from a gray drawing: what the mask canvas paints white stays opaque. */
    PDExtendedGraphicsState softMask(Canvas gray) throws IOException {
        PDFormXObject form = gray.toForm(false);
        COSDictionary group = new COSDictionary();
        group.setItem(COSName.TYPE, COSName.GROUP);
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setItem(COSName.CS, COSName.DEVICEGRAY);
        form.getCOSObject().setItem(COSName.GROUP, group);
        COSDictionary mask = new COSDictionary();
        mask.setItem(COSName.TYPE, COSName.MASK);
        mask.setItem(COSName.S, COSName.LUMINOSITY);
        mask.setItem(COSName.G, form);
        COSArray bc = new COSArray();
        bc.add(org.apache.pdfbox.cos.COSInteger.ZERO);
        mask.setItem(COSName.BC, bc);
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.getCOSObject().setItem(COSName.SMASK, mask);
        return gs;
    }
}
