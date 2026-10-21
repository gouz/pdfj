package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.net.Images;
import io.github.gouz.pdfj.style.Style;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.DocumentLoader;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.util.ParsedURL;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Document;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.geom.Dimension2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Image decoding: PNG, JPEG, GIF, WebP (bitmaps) and SVG (Batik, no external resources). */
final class Decode {
    private Decode() {}

    /** a decoded image and its natural size in pt (1 px = 0.75 pt) */
    record Decoded(ImageKind kind, double width, double height) {}

    private static final long MAX_PIXELS = 60_000_000L;

    /** SVG, or PNG / JPEG / GIF / WebP; null if not an image we can read. */
    static Decoded image(byte[] data) {
        if (data == null || data.length < 4) return null;
        if (Images.isSvg(data)) return svg(new String(data, StandardCharsets.UTF_8));
        try {
            boolean jpeg = (data[0] & 255) == 0xFF && (data[1] & 255) == 0xD8;
            boolean png = (data[0] & 255) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
            try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) return null;
                ImageReader r = readers.next();
                try {
                    r.setInput(in);
                    int w = r.getWidth(0), h = r.getHeight(0);
                    if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) return null;
                    if (jpeg || png) return new Decoded(new ImageKind.Raster(null, data, w, h), w * Style.PX, h * Style.PX);
                    BufferedImage img = r.read(0);
                    return new Decoded(new ImageKind.Raster(img, null, w, h), w * Style.PX, h * Style.PX);
                } finally {
                    r.dispose();
                }
            }
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** No external resource, no script: an SVG may come from an untrusted document. */
    private static final class Agent extends UserAgentAdapter {
        @Override
        public void checkLoadExternalResource(ParsedURL resourceURL, ParsedURL docURL) {
            if (!"data".equals(resourceURL.getProtocol())) throw new SecurityException("external resource refused: " + resourceURL);
        }

        @Override
        public ScriptSecurity getScriptSecurity(String scriptType, ParsedURL scriptURL, ParsedURL docURL) {
            return new NoLoadScriptSecurity(scriptType);
        }

        @Override
        public void displayError(Exception e) {}

        @Override
        public void displayMessage(String message) {}
    }

    /** An SVG document (markup), drawn by Batik; its natural size from width / height / viewBox. */
    static Decoded svg(String markup) {
        if (markup.contains("<!ENTITY")) return null;
        String m = markup.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
        if (!m.contains("xmlns=\"http://www.w3.org/2000/svg\"") && !m.contains("xmlns='http://www.w3.org/2000/svg'")) {
            m = m.replaceFirst("<svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"");
        }
        if (!m.contains("xmlns:xlink")) m = m.replaceFirst("<svg", "<svg xmlns:xlink=\"http://www.w3.org/1999/xlink\"");
        try {
            SAXSVGDocumentFactory f = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName());
            f.setValidating(false);
            Document doc = f.createDocument("data:image/svg+xml,x", new StringReader(m));
            Agent ua = new Agent();
            BridgeContext ctx = new BridgeContext(ua, new DocumentLoader(ua));
            ctx.setDynamicState(BridgeContext.STATIC);
            GraphicsNode node = new GVTBuilder().build(ctx, doc);
            Dimension2D size = ctx.getDocumentSize();
            double w = size.getWidth(), h = size.getHeight();
            if (!(w > 0) || !(h > 0)) {
                var b = node.getPrimitiveBounds();
                if (b == null) return null;
                w = b.getMaxX();
                h = b.getMaxY();
                if (!(w > 0) || !(h > 0)) return null;
            }
            return new Decoded(new ImageKind.Svg(node, w, h), w * Style.PX, h * Style.PX);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
