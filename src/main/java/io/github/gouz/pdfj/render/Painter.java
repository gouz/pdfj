package io.github.gouz.pdfj.render;

import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.layout.Box;
import io.github.gouz.pdfj.layout.Deco;
import io.github.gouz.pdfj.layout.Deco.BgLayer;
import io.github.gouz.pdfj.layout.Deco.LayerPaint;
import io.github.gouz.pdfj.layout.ImageKind;
import io.github.gouz.pdfj.layout.ImageLeaf;
import io.github.gouz.pdfj.layout.TextLeaf;
import io.github.gouz.pdfj.layout.Tree;
import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Rgba;
import io.github.gouz.pdfj.style.Style.BgSize;
import io.github.gouz.pdfj.style.Style.BorderStyle;
import io.github.gouz.pdfj.style.Style.BoxArea;
import io.github.gouz.pdfj.style.Style.Gradient;
import io.github.gouz.pdfj.style.Style.GradientKind;
import io.github.gouz.pdfj.style.Style.Shadow;
import io.github.gouz.pdfj.style.Values;
import io.github.gouz.pdfj.text.Brush;
import io.github.gouz.pdfj.text.TextLayout;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Drawing the laid out boxes: backgrounds, shadows, borders, text, images, SVG, links. */
public final class Painter {
    private Painter() {}

    /** A link found while drawing: its rectangle (drawing coordinates) and 1-based index in the tree's links. */
    public record Link(double x, double y, double w, double h, int href) {}

    // ---- gradients

    /** stop colors and offsets along a gradient line of {@code length}: missing positions spread evenly, never decreasing */
    static List<Object[]> stopOffsets(List<io.github.gouz.pdfj.style.Style.Stop> stops, double length) {
        int n = stops.size();
        Double[] pos = new Double[n];
        for (int i = 0; i < n; i++) {
            Len p = stops.get(i).position();
            pos[i] = p == null ? null : p.resolve(length) / Math.max(length, 0.001);
        }
        if (pos[0] == null) pos[0] = 0.0;
        if (pos[n - 1] == null) pos[n - 1] = 1.0;
        for (int i = 0; i < n; i++) {
            if (pos[i] != null) continue;
            int a = i - 1;
            int b = i;
            while (pos[b] == null) b++;
            for (int k = i; k < b; k++) pos[k] = pos[a] + (pos[b] - pos[a]) * (k - a) / (b - a);
            i = b;
        }
        List<Object[]> out = new ArrayList<>(n);
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            last = Math.max(pos[i], last);
            out.add(new Object[] {stops.get(i).color(), last});
        }
        return out;
    }

    /** a shading and how to paint it: an ellipse matrix (or null), the alpha (uniform, or a gray mask shading) */
    record Shade(PDShading color, PDShading alpha, double uniformAlpha, double[] matrix) {}

    private static COSArray floats(double... v) {
        COSArray a = new COSArray();
        for (double d : v) a.add(new COSFloat((float) d));
        return a;
    }

    /** a function of t in [0, 1] through the stops (offsets in [0, 1], increasing), color or alpha */
    private static PDFunction function(List<Object[]> stops, boolean alpha) {
        List<double[]> pts = new ArrayList<>();
        for (Object[] s : stops) {
            Rgba c = (Rgba) s[0];
            double o = Math.max(0, Math.min(1, (double) s[1]));
            double[] v = alpha ? new double[] {o, c.a() / 255.0} : new double[] {o, c.r() / 255.0, c.g() / 255.0, c.b() / 255.0};
            pts.add(v);
        }
        if (pts.get(0)[0] > 0) {
            double[] f = pts.get(0).clone();
            f[0] = 0;
            pts.add(0, f);
        }
        if (pts.get(pts.size() - 1)[0] < 1) {
            double[] l = pts.get(pts.size() - 1).clone();
            l[0] = 1;
            pts.add(l);
        }
        List<COSDictionary> fns = new ArrayList<>();
        List<Double> bounds = new ArrayList<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            if (b[0] - a[0] < 1e-6 && i + 2 < pts.size()) continue; // a hard stop: the color jumps here
            COSDictionary f = new COSDictionary();
            f.setInt(COSName.FUNCTION_TYPE, 2);
            f.setItem(COSName.DOMAIN, floats(0, 1));
            f.setItem(COSName.C0, floats(java.util.Arrays.copyOfRange(a, 1, a.length)));
            f.setItem(COSName.C1, floats(java.util.Arrays.copyOfRange(b, 1, b.length)));
            f.setItem(COSName.N, COSInteger.ONE);
            if (!fns.isEmpty()) bounds.add(a[0]);
            fns.add(f);
        }
        if (fns.isEmpty()) {
            double[] a = pts.get(0);
            COSDictionary f = new COSDictionary();
            f.setInt(COSName.FUNCTION_TYPE, 2);
            f.setItem(COSName.DOMAIN, floats(0, 1));
            f.setItem(COSName.C0, floats(java.util.Arrays.copyOfRange(a, 1, a.length)));
            f.setItem(COSName.C1, floats(java.util.Arrays.copyOfRange(a, 1, a.length)));
            f.setItem(COSName.N, COSInteger.ONE);
            fns.add(f);
        }
        try {
            if (fns.size() == 1) return PDFunction.create(fns.get(0));
            COSDictionary st = new COSDictionary();
            st.setInt(COSName.FUNCTION_TYPE, 3);
            st.setItem(COSName.DOMAIN, floats(0, 1));
            COSArray fa = new COSArray();
            fns.forEach(fa::add);
            st.setItem(COSName.FUNCTIONS, fa);
            double prev = 0;
            double[] bs = new double[bounds.size()];
            for (int i = 0; i < bs.length; i++) {
                prev = Math.max(bounds.get(i), prev + 1e-6);
                bs[i] = Math.min(prev, 1 - 1e-6);
            }
            st.setItem(COSName.BOUNDS, floats(bs));
            double[] enc = new double[fns.size() * 2];
            for (int i = 0; i < fns.size(); i++) enc[2 * i + 1] = 1;
            st.setItem(COSName.ENCODE, floats(enc));
            return PDFunction.create(st);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** a repeating gradient: its cycle [first, last] laid end to end over [0, 1] */
    private static List<Object[]> unroll(List<Object[]> stops) {
        double first = (double) stops.get(0)[1], last = (double) stops.get(stops.size() - 1)[1];
        double period = last - first;
        if (period <= 0.0001) return stops;
        int k0 = (int) Math.floor((0 - first) / period), k1 = (int) Math.ceil((1 - first) / period);
        if (k1 - k0 > 400) k1 = k0 + 400;
        List<Object[]> out = new ArrayList<>();
        for (int k = k0; k <= k1; k++) {
            for (Object[] s : stops) {
                double o = (double) s[1] + k * period;
                out.add(new Object[] {s[0], o});
            }
        }
        // cut to [0, 1], the colors at the ends interpolated
        List<Object[]> cut = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            double o = (double) out.get(i)[1];
            if (o < 0) {
                if (i + 1 < out.size() && (double) out.get(i + 1)[1] > 0) cut.add(new Object[] {mix(out.get(i), out.get(i + 1), 0), 0.0});
                continue;
            }
            if (o > 1) {
                if (i > 0 && (double) out.get(i - 1)[1] < 1) cut.add(new Object[] {mix(out.get(i - 1), out.get(i), 1), 1.0});
                break;
            }
            cut.add(out.get(i));
        }
        return cut.size() >= 2 ? cut : stops;
    }

    private static Rgba mix(Object[] a, Object[] b, double at) {
        double oa = (double) a[1], ob = (double) b[1];
        double t = ob - oa <= 0 ? 0 : (at - oa) / (ob - oa);
        Rgba ca = (Rgba) a[0], cb = (Rgba) b[0];
        return new Rgba((int) Math.round(ca.r() + (cb.r() - ca.r()) * t), (int) Math.round(ca.g() + (cb.g() - ca.g()) * t),
            (int) Math.round(ca.b() + (cb.b() - ca.b()) * t), (int) Math.round(ca.a() + (cb.a() - ca.a()) * t));
    }

    /** the gradient over the box (x, y, w, h); null if degenerate */
    static Shade gradient(Gradient g, double x, double y, double w, double h) {
        List<Object[]> stops;
        PDShading color, alpha = null;
        double[] matrix = null;
        COSArray coords;
        if (g.kind() instanceof GradientKind.Radial r) {
            double cx = x + r.atX().resolve(w), cy = y + r.atY().resolve(h);
            // farthest-corner
            double fx = Math.max(cx - x, x + w - cx), fy = Math.max(cy - y, y + h - cy);
            double rx, ry;
            if (r.circle()) {
                rx = ry = Math.hypot(fx, fy);
            } else {
                rx = fx * Math.sqrt(2);
                ry = fy * Math.sqrt(2);
            }
            if (rx <= 0 || ry <= 0) return null;
            stops = stopOffsets(g.stops(), rx);
            double k = ry / rx;
            // ellipse: the circle squashed vertically around the center
            if (Math.abs(k - 1) > 1e-6) matrix = new double[] {1, 0, 0, k, 0, cy * (1 - k)};
            coords = floats(cx, cy, 0, cx, cy, rx);
        } else {
            double a;
            if (g.kind() instanceof GradientKind.Linear l) a = Math.toRadians(l.angle());
            else {
                GradientKind.LinearCorner c = (GradientKind.LinearCorner) g.kind();
                // the 50% line goes through the two other corners
                a = Math.atan2(c.x() * h, -c.y() * w);
            }
            double dx = Math.sin(a), dy = -Math.cos(a);
            double len = Math.abs(w * Math.sin(a)) + Math.abs(h * Math.cos(a));
            double cx = x + w / 2, cy = y + h / 2;
            stops = stopOffsets(g.stops(), len);
            coords = floats(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2);
        }
        if (g.repeating()) stops = unroll(stops);
        boolean radial = g.kind() instanceof GradientKind.Radial;
        color = shading(radial, coords, function(stops, false), false);
        int a0 = ((Rgba) stops.get(0)[0]).a();
        boolean uniform = true;
        for (Object[] s : stops) uniform &= ((Rgba) s[0]).a() == a0;
        if (!uniform) alpha = shading(radial, coords, function(stops, true), true);
        return new Shade(color, alpha, uniform ? a0 / 255.0 : 1, matrix);
    }

    private static PDShading shading(boolean radial, COSArray coords, PDFunction fn, boolean gray) {
        COSDictionary d = new COSDictionary();
        PDShading sh = radial ? new PDShadingType3(d) : new PDShadingType2(d);
        sh.setShadingType(radial ? 3 : 2);
        sh.setColorSpace(gray ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE);
        d.setItem(COSName.COORDS, coords);
        d.setItem(COSName.FUNCTION, fn);
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        d.setItem(COSName.EXTEND, extend);
        return sh;
    }

    /** paint a shade in the current clip (caller saves / restores) */
    static void paintShade(Canvas s, Shade sh) {
        if (sh.matrix != null) s.transform(sh.matrix[0], sh.matrix[1], sh.matrix[2], sh.matrix[3], sh.matrix[4], sh.matrix[5]);
        if (sh.alpha != null) {
            Canvas mask = s.group();
            mask.shading(sh.alpha);
            try {
                PDExtendedGraphicsState gs = s.softMask(mask);
                s.graphicsState(gs);
            } catch (IOException e) {
                // painted opaque
            }
        } else if (sh.uniformAlpha < 1) {
            s.alpha(sh.uniformAlpha);
        }
        s.shading(sh.color);
    }

    // ---- shapes

    static void rectPath(Canvas s, double x, double y, double w, double h) {
        s.rect(x, y, Math.max(0, w), Math.max(0, h));
    }

    /** Rounded rectangle, radii clockwise from top-left, scaled down when they overlap (like CSS). */
    static void roundedPath(Canvas s, double x, double y, double w, double h, double[] r) {
        if (r[0] <= 0 && r[1] <= 0 && r[2] <= 0 && r[3] <= 0) {
            rectPath(s, x, y, w, h);
            return;
        }
        double scale = 1;
        double[] ratios = {w / (r[0] + r[1]), h / (r[1] + r[2]), w / (r[2] + r[3]), h / (r[3] + r[0])};
        for (double v : ratios) if (Double.isFinite(v)) scale = Math.min(scale, v);
        double[] q = new double[4];
        for (int i = 0; i < 4; i++) q[i] = Math.max(0, r[i] * scale);
        final double k = 0.5522848; // cubic approximation of a quarter circle
        s.moveTo(x + q[0], y);
        s.lineTo(x + w - q[1], y);
        s.curveTo(x + w - q[1] * (1 - k), y, x + w, y + q[1] * (1 - k), x + w, y + q[1]);
        s.lineTo(x + w, y + h - q[2]);
        s.curveTo(x + w, y + h - q[2] * (1 - k), x + w - q[2] * (1 - k), y + h, x + w - q[2], y + h);
        s.lineTo(x + q[3], y + h);
        s.curveTo(x + q[3] * (1 - k), y + h, x, y + h - q[3] * (1 - k), x, y + h - q[3]);
        s.lineTo(x, y + q[0]);
        s.curveTo(x, y + q[0] * (1 - k), x + q[0] * (1 - k), y, x + q[0], y);
        s.close();
    }

    private static void stroke(Canvas s, Rgba c, double w, BorderStyle style) {
        s.strokeColor(c);
        s.lineWidth(w);
        s.lineCap(style == BorderStyle.DOTTED ? 1 : 0);
        if (style == BorderStyle.DASHED) s.dash(new double[] {w * 3, w * 3});
        else if (style == BorderStyle.DOTTED) s.dash(new double[] {0, w * 2});
        else s.dash(new double[0]);
    }

    /** Coverage (0..1) of a rounded rectangle at a point, antialiased over 1/k pt. */
    static double rrectCoverage(double px, double py, double[] rect, double[] r, double k) {
        double x = rect[0], y = rect[1], w = rect[2], h = rect[3];
        if (w <= 0 || h <= 0) return 0;
        double hw = w / 2, hh = h / 2;
        double dx = px - x - hw, dy = py - y - hh;
        double rr = dx < 0 ? (dy < 0 ? r[0] : r[3]) : (dy < 0 ? r[1] : r[2]);
        rr = Math.max(0, Math.min(Math.min(rr, hw), hh));
        double qx = Math.abs(dx) - (hw - rr), qy = Math.abs(dy) - (hh - rr);
        double d = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - rr;
        return Math.max(0, Math.min(1, 0.5 - d * k));
    }

    interface Coverage {
        double at(double px, double py, double k);
    }

    /**
     * A blurred shape as an ARGB bitmap covering (x0, y0, w, h) in pt: {@code coverage} gives the shape, the
     * CSS blur radius is two standard deviations (three box blurs approximate the gaussian).
     */
    static Object[] blurred(double x0, double y0, double w, double h, double blur, Rgba c, Coverage coverage) {
        // ~2 px per pt, at most ~1.5 M pixels
        double k = Math.max(0.05, Math.min(2.0, Math.sqrt(1_500_000.0 / Math.max(w * h, 1))));
        int pw = (int) Math.ceil(w * k), ph = (int) Math.ceil(h * k);
        if (pw <= 0 || ph <= 0 || pw > 8192 || ph > 8192) return null;
        float[] a = new float[pw * ph];
        for (int j = 0; j < ph; j++) {
            double py = y0 + (j + 0.5) / k;
            for (int i = 0; i < pw; i++) a[j * pw + i] = (float) coverage.at(x0 + (i + 0.5) / k, py, k);
        }
        if (blur > 0) {
            // box size per pass for a gaussian of sigma (in px)
            double sp = blur / 2 * k;
            int boxR = (int) Math.max(1, Math.round((Math.sqrt(12 * sp * sp / 3 + 1) - 1) / 2));
            float[] tmp = new float[pw * ph];
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(a, tmp, pw, ph, boxR, true);
                boxBlur(tmp, a, pw, ph, boxR, false);
            }
        }
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[pw * ph];
        int rgb = c.r() << 16 | c.g() << 8 | c.b();
        for (int i = 0; i < px.length; i++) {
            int al = Math.max(0, Math.min(255, Math.round(a[i] * c.a())));
            px[i] = al << 24 | rgb;
        }
        img.setRGB(0, 0, pw, ph, px, 0, pw);
        return new Object[] {img, pw / k, ph / k};
    }

    /** One running-sum box blur pass of radius {@code r}, along rows or columns (edges clamped to 0). */
    private static void boxBlur(float[] src, float[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        float norm = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            float sum = 0;
            for (int i = 0; i < Math.min(r, len); i++) sum += src[horizontal ? line * w + i : i * w + line];
            for (int i = 0; i < len; i++) {
                if (i + r < len) sum += src[horizontal ? line * w + i + r : (i + r) * w + line];
                if (i > r) sum -= src[horizontal ? line * w + i - r - 1 : (i - r - 1) * w + line];
                dst[horizontal ? line * w + i : i * w + line] = sum * norm;
            }
        }
    }

    /** Outer box shadows, the last one at the bottom (as in CSS). */
    private static void drawShadows(Canvas s, List<Shadow> shadows, double[] radius, double x, double y, double w, double h) {
        for (int i = shadows.size() - 1; i >= 0; i--) {
            Shadow sh = shadows.get(i);
            if (sh.inset() || sh.color().a() == 0) continue;
            double sx = x + sh.dx() - sh.spread(), sy = y + sh.dy() - sh.spread(), sw = w + 2 * sh.spread(), shh = h + 2 * sh.spread();
            if (sw <= 0 || shh <= 0) continue;
            double[] r = new double[4];
            for (int k = 0; k < 4; k++) r[k] = radius[k] > 0 ? Math.max(0, radius[k] + sh.spread()) : 0;
            if (sh.blur() <= 0) {
                s.save();
                s.fillColor(sh.color());
                roundedPath(s, sx, sy, sw, shh, r);
                s.fill();
                s.restore();
            } else {
                double pad = 1.5 * sh.blur();
                double[] shape = {sx, sy, sw, shh};
                Object[] bmp = blurred(sx - pad, sy - pad, sw + 2 * pad, shh + 2 * pad, sh.blur(), sh.color(), (px, py, k) -> rrectCoverage(px, py, shape, r, k));
                if (bmp != null) s.bitmap((BufferedImage) bmp[0], sx - pad, sy - pad, (double) bmp[1], (double) bmp[2]);
            }
        }
    }

    /** Inset shadows, inside the padding box: everything but the (offset, shrunk) box casts the shadow. */
    private static void drawInsetShadows(Canvas s, List<Shadow> shadows, double[] rect, double[] radius) {
        double x = rect[0], y = rect[1], w = rect[2], h = rect[3];
        s.save();
        roundedPath(s, x, y, w, h, radius);
        s.clip();
        for (int i = shadows.size() - 1; i >= 0; i--) {
            Shadow sh = shadows.get(i);
            if (!sh.inset() || sh.color().a() == 0) continue;
            double[] hole = {x + sh.dx() + sh.spread(), y + sh.dy() + sh.spread(), Math.max(0, w - 2 * sh.spread()), Math.max(0, h - 2 * sh.spread())};
            double[] r = new double[4];
            for (int k = 0; k < 4; k++) r[k] = Math.max(0, radius[k] - sh.spread());
            double pad = 1.5 * sh.blur() + 1;
            Object[] bmp = blurred(x - pad, y - pad, w + 2 * pad, h + 2 * pad, sh.blur(), sh.color(), (px, py, k) -> 1 - rrectCoverage(px, py, hole, r, k));
            if (bmp != null) s.bitmap((BufferedImage) bmp[0], x - pad, y - pad, (double) bmp[1], (double) bmp[2]);
        }
        s.restore();
    }

    /** The border / padding / content box of a box, with its corner radii: {x, y, w, h, r0..r3}. */
    static double[] area(BoxArea which, double[] rect, double[] radius, double[] bw, double[] pad) {
        double[] in = switch (which) {
            case BORDER, TEXT -> new double[4];
            case PADDING -> bw;
            case CONTENT -> new double[] {bw[0] + pad[0], bw[1] + pad[1], bw[2] + pad[2], bw[3] + pad[3]};
        };
        double t = in[0], r = in[1], b = in[2], l = in[3];
        return new double[] {
            rect[0] + l, rect[1] + t, Math.max(0, rect[2] - l - r), Math.max(0, rect[3] - t - b),
            Math.max(0, radius[0] - Math.max(t, l)), Math.max(0, radius[1] - Math.max(t, r)),
            Math.max(0, radius[2] - Math.max(b, r)), Math.max(0, radius[3] - Math.max(b, l)),
        };
    }

    private static double[] radii(double[] a) {
        return new double[] {a[4], a[5], a[6], a[7]};
    }

    /** One background layer: tiles over its origin box, clipped to its clip box. */
    private static void drawLayer(Canvas s, BgLayer layer, Deco d, double[] rect, double[] radius, double[] pad, double[] window, double[] page) {
        if (layer.clip() == BoxArea.TEXT) return; // paints the text instead (drawText)
        // fixed: positioned against the page (repeated on every page), as browsers print it
        double[] a = layer.fixed() && page != null ? new double[] {page[0], page[1], page[2], page[3], 0, 0, 0, 0} : area(layer.origin(), rect, radius, d.borderWidth, pad);
        double[] c = area(layer.clip(), rect, radius, d.borderWidth, pad);
        double ax = a[0], ay = a[1], aw = a[2], ah = a[3];
        if (aw <= 0 || ah <= 0 || c[2] <= 0 || c[3] <= 0) return;
        // a gradient has no natural size: it fills the positioning area
        double nw, nh;
        boolean grad = layer.paint() instanceof LayerPaint.GradientPaint;
        if (layer.paint() instanceof LayerPaint.ImagePaint ip) {
            nw = ip.width();
            nh = ip.height();
        } else {
            nw = aw;
            nh = ah;
        }
        if (nw <= 0 || nh <= 0) return;
        double tw, th;
        BgSize size = layer.size();
        switch (size.kind()) {
            case AUTO -> {
                tw = nw;
                th = nh;
            }
            case COVER -> {
                double k = Math.max(aw / nw, ah / nh);
                tw = nw * k;
                th = nh * k;
            }
            case CONTAIN -> {
                double k = Math.min(aw / nw, ah / nh);
                tw = nw * k;
                th = nh * k;
            }
            default -> {
                Len sw = size.w(), sh = size.h();
                if (sw.isAuto() && sh.isAuto()) {
                    tw = nw;
                    th = nh;
                } else if (sh.isAuto()) {
                    tw = sw.resolve(aw);
                    th = grad ? ah : tw * nh / nw;
                } else if (sw.isAuto()) {
                    th = sh.resolve(ah);
                    tw = grad ? aw : th * nw / nh;
                } else {
                    tw = sw.resolve(aw);
                    th = sh.resolve(ah);
                }
            }
        }
        if (tw < 0.5 || th < 0.5) return;
        // percentages position the image's point at the area's point (CSS)
        double px = ax + layer.position()[0].resolve(aw - tw);
        double py = ay + layer.position()[1].resolve(ah - th);
        List<Double> xs = tiles(px, tw, c[0], c[0] + c[2], layer.repeat()[0]);
        List<Double> ys = tiles(py, th, c[1], c[1] + c[3], layer.repeat()[1]);
        s.save();
        roundedPath(s, c[0], c[1], c[2], c[3], radii(c));
        s.clip();
        int drawn = 0;
        for (double ty : ys) {
            if (!(ty < window[1] && ty + th > window[0])) continue;
            for (double tx : xs) {
                if (drawn++ >= 4096) break;
                if (layer.paint() instanceof LayerPaint.GradientPaint gp) {
                    Shade sh = gradient(gp.gradient(), tx, ty, tw, th);
                    if (sh == null) continue;
                    s.save();
                    rectPath(s, tx, ty, tw, th);
                    s.clip();
                    paintShade(s, sh);
                    s.restore();
                } else if (layer.paint() instanceof LayerPaint.ImagePaint ip) {
                    drawImageKind(s, ip.image(), tx, ty, tw, th);
                }
            }
        }
        s.restore();
    }

    private static List<Double> tiles(double p, double t, double from, double to, boolean repeat) {
        List<Double> out = new ArrayList<>();
        if (!repeat) {
            out.add(p);
            return out;
        }
        double first = p - Math.ceil((p - from) / t) * t;
        for (int i = 0; i < 512; i++) {
            double v = first + i * t;
            if (v >= to) break;
            out.add(v);
        }
        return out;
    }

    private static void drawImageKind(Canvas s, ImageKind k, double x, double y, double w, double h) {
        if (k instanceof ImageKind.Raster r) s.image(r, x, y, w, h);
        else if (k instanceof ImageKind.Svg svg) s.svg(svg, x, y, w, h);
    }

    /** Shadows, background (color, then layers bottom to top), inset shadows, borders. */
    private static void drawBox(Canvas s, Deco d, double x, double y, double w, double h, double[] pad, double[] window, double[] page) {
        double[] rect = {x, y, w, h};
        double[] radius = Values.resolveRadius(d.radius, w, h);
        if (d.shadows != null) drawShadows(s, d.shadows, radius, x, y, w, h);
        if (d.background != null && d.colorClip != BoxArea.TEXT) {
            double[] c = area(d.colorClip, rect, radius, d.borderWidth, pad);
            s.save();
            s.fillColor(d.background);
            roundedPath(s, c[0], c[1], c[2], c[3], radii(c));
            s.fill();
            s.restore();
        }
        for (int i = d.layers.size() - 1; i >= 0; i--) drawLayer(s, d.layers.get(i), d, rect, radius, pad, window, page);
        if (d.shadows != null && d.shadows.stream().anyMatch(Shadow::inset)) {
            double[] inner = area(BoxArea.PADDING, rect, radius, d.borderWidth, pad);
            drawInsetShadows(s, d.shadows, inner, radii(inner));
        }
        double[] bw = d.borderWidth;
        if (bw[0] <= 0 && bw[1] <= 0 && bw[2] <= 0 && bw[3] <= 0) return;
        boolean uniform = true;
        for (int i = 1; i < 4; i++) uniform &= bw[i] == bw[0] && d.borderColor[i].equals(d.borderColor[0]) && d.borderStyle[i] == d.borderStyle[0];
        if (uniform && d.borderStyle[0] != BorderStyle.DOUBLE) {
            // one stroke along the middle of the border
            double w0 = bw[0];
            BorderStyle st = d.borderStyle[0] == null ? BorderStyle.SOLID : d.borderStyle[0];
            double[] r = new double[4];
            for (int i = 0; i < 4; i++) r[i] = Math.max(0, radius[i] - w0 / 2);
            s.save();
            stroke(s, d.borderColor[0], w0, st);
            roundedPath(s, x + w0 / 2, y + w0 / 2, w - w0, h - w0, r);
            s.stroke();
            s.restore();
            return;
        }
        // per side: solid sides are filled trapezoids (mitred corners), others stroked lines
        double t = bw[0], r = bw[1], b = bw[2], l = bw[3];
        double[][] outer = {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
        double[][] inner = {{x + l, y + t}, {x + w - r, y + t}, {x + w - r, y + h - b}, {x + l, y + h - b}};
        for (int side = 0; side < 4; side++) {
            BorderStyle st = d.borderStyle[side];
            double width = bw[side];
            if (st == null || width <= 0) continue;
            int a = side, bb = (side + 1) % 4;
            s.save();
            if (st == BorderStyle.SOLID || st == BorderStyle.DOUBLE) {
                s.fillColor(d.borderColor[side]);
                s.moveTo(outer[a][0], outer[a][1]);
                s.lineTo(outer[bb][0], outer[bb][1]);
                s.lineTo(inner[bb][0], inner[bb][1]);
                s.lineTo(inner[a][0], inner[a][1]);
                s.close();
                s.fill();
            } else {
                stroke(s, d.borderColor[side], width, st);
                s.moveTo((outer[a][0] + inner[a][0]) / 2, (outer[a][1] + inner[a][1]) / 2);
                s.lineTo((outer[bb][0] + inner[bb][0]) / 2, (outer[bb][1] + inner[bb][1]) / 2);
                s.stroke();
            }
            s.restore();
        }
    }

    /** Position of a node's border box from the root of its (sub)tree. */
    private static double[] treePos(Box n) {
        double x = 0, y = 0;
        for (Box b = n; b != null; b = b.parent) {
            x += b.x;
            y += b.y;
        }
        return new double[] {x, y};
    }

    /**
     * The gradient of a {@code background-clip: text} element, laid over that element's box, for the
     * text of {@code leaf} drawn with its content box at (x, y).
     */
    private static Shade textFillPaint(Tree tree, int fill, Box leaf, double x, double y) {
        if (fill < 1 || fill > tree.textFills.size()) return null;
        Box el = tree.textFills.get(fill - 1);
        Deco d = el.deco;
        if (d == null) return null;
        BgLayer layer = null;
        for (BgLayer l : d.layers) if (l.clip() == BoxArea.TEXT && l.paint() instanceof LayerPaint.GradientPaint) layer = layer == null ? l : layer;
        if (layer == null) return null;
        // from tree coordinates to drawing coordinates, through the leaf's known position
        double[] lp = treePos(leaf);
        double dx = x - lp[0] - leaf.lpad[3] - leaf.lbor[3], dy = y - lp[1] - leaf.lpad[0] - leaf.lbor[0];
        double[] ep = treePos(el);
        double[] rect = {ep[0] + dx, ep[1] + dy, el.w, el.h};
        double[] pad = {el.lpad[0], el.lpad[1], el.lpad[2], el.lpad[3]};
        double[] a = area(layer.origin(), rect, Values.resolveRadius(d.radius, el.w, el.h), d.borderWidth, pad);
        return gradient(((LayerPaint.GradientPaint) layer.paint()).gradient(), a[0], a[1], a[2], a[3]);
    }

    private static void drawText(Canvas s, Tree tree, Box leaf, TextLeaf t, double x, double y, double[] window, List<Link> links) {
        for (TextLayout.Line line : t.lines.lines()) {
            if (y + line.bottom() <= window[0] || y + line.top() >= window[1]) continue;
            for (TextLayout.Item item : line.items()) {
                if (item instanceof TextLayout.BoxItem bi) {
                    // inline-block / inline image: its own subtree, drawn at its place in the line
                    if (bi.node() instanceof Box node) draw(s, tree, node, x - leaf.lpad[3] - leaf.lbor[3], y - leaf.lpad[0] - leaf.lbor[0], new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}, links);
                    continue;
                }
                TextLayout.GlyphRun gr = (TextLayout.GlyphRun) item;
                Brush brush = gr.run().brush();
                if (brush == null) continue;
                double size = gr.run().style().fontSize;
                double gx = x + gr.x(), gy = y + gr.baseline();
                if (brush.background() != null) {
                    s.save();
                    s.fillColor(brush.background());
                    rectPath(s, gx, y + line.top(), gr.width(), line.bottom() - line.top());
                    s.fill();
                    s.restore();
                }
                if (brush.link() > 0) links.add(new Link(gx, y + line.top(), gr.width(), line.bottom() - line.top(), brush.link()));
                FontFace face = gr.face();
                if (face == null || size <= 0) continue;
                Shade fill = brush.textFill() > 0 ? textFillPaint(tree, brush.textFill(), leaf, x, y) : null;
                s.save();
                if (fill != null) {
                    s.glyphs(face, size, gx, gy, gr.glyphs(), gr.advances(), gr.codes(), true);
                    paintShade(s, fill);
                } else {
                    s.fillColor(brush.color());
                    s.glyphs(face, size, gx, gy, gr.glyphs(), gr.advances(), gr.codes(), false);
                }
                s.restore();
                var st = gr.run().style();
                if (st.underline || st.strike) {
                    s.save();
                    s.fillColor(brush.color());
                    double k = size / face.unitsPerEm;
                    if (st.underline) {
                        double th = Math.max(0.5, face.underlineThickness * k);
                        rectPath(s, gx, gy - face.underlinePosition * k, gr.width(), th);
                        s.fill();
                    }
                    if (st.strike) {
                        double th = Math.max(0.5, face.strikeoutSize * k);
                        rectPath(s, gx, gy - face.strikeoutPosition * k, gr.width(), th);
                        s.fill();
                    }
                    s.restore();
                }
            }
        }
    }

    private static void drawImage(Canvas s, ImageLeaf img, double x, double y, double w, double h) {
        double nw = img.naturalWidth(), nh = img.naturalHeight();
        if (w <= 0 || h <= 0 || nw <= 0 || nh <= 0) return;
        // object-fit: the drawn size inside the content box, centered
        double dw, dh;
        switch (img.fit()) {
            case FILL -> {
                dw = w;
                dh = h;
            }
            case CONTAIN -> {
                double k = Math.min(w / nw, h / nh);
                dw = nw * k;
                dh = nh * k;
            }
            case COVER -> {
                double k = Math.max(w / nw, h / nh);
                dw = nw * k;
                dh = nh * k;
            }
            default -> {
                dw = nw;
                dh = nh;
            }
        }
        double[] radius = Values.resolveRadius(img.radius(), w, h);
        boolean clip = radius[0] > 0 || radius[1] > 0 || radius[2] > 0 || radius[3] > 0 || dw > w + 0.01 || dh > h + 0.01;
        s.save();
        if (clip) {
            roundedPath(s, x, y, w, h, radius);
            s.clip();
        }
        drawImageKind(s, img.kind(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
        s.restore();
    }

    /** The root background (html / body), painted on the whole page like a browser's canvas. */
    public static void drawCanvas(Canvas s, Deco canvas, double pw, double ph) {
        drawBox(s, canvas, 0, 0, pw, ph, new double[4], new double[] {0, ph}, new double[] {0, 0, pw, ph});
    }

    /** Draw the subtree of {@code n} whose parent's border box is at (ox, oy); only what crosses {@code window} (y range). */
    public static void draw(Canvas s, Tree tree, Box n, double ox, double oy, double[] window, List<Link> links) {
        Deco d = n.deco;
        if (d != null && d.opacity < 1) {
            if (d.opacity <= 0) return;
            Canvas g = s.group();
            drawInner(g, tree, n, ox, oy, window, links);
            s.paintGroup(g, d.opacity);
            return;
        }
        drawInner(s, tree, n, ox, oy, window, links);
    }

    private static void drawInner(Canvas s, Tree tree, Box n, double ox, double oy, double[] window, List<Link> links) {
        double x = ox + n.x, y = oy + n.y, w = n.w, h = n.h;
        Deco d = n.deco;
        int pushed = 0;
        if (d != null) {
            // transform around transform-origin (default: the center of the border box)
            if (d.transform != null) {
                double[] m = Values.transformMatrix(d.transform, w, h, d.transformEm);
                if (m != null) {
                    Len[] o = d.transformOrigin == null ? new Len[] {Len.pct(50), Len.pct(50)} : Values.position(d.transformOrigin, d.transformEm, d.transformEm);
                    double tx = x + o[0].resolve(w), ty = y + o[1].resolve(h);
                    s.save();
                    pushed++;
                    s.translate(tx, ty);
                    s.transform(m[0], m[1], m[2], m[3], m[4], m[5]);
                    s.translate(-tx, -ty);
                }
            }
            // shadows reach outside the box
            double reach = 0;
            if (d.shadows != null) for (Shadow sh : d.shadows) reach = Math.max(reach, Math.abs(sh.dy()) + Math.max(sh.spread(), 0) + 1.5 * sh.blur());
            if (y - reach < window[1] && y + h + reach > window[0]) {
                // the page area in column coordinates, for fixed backgrounds (main column only)
                Box top = n;
                while (top.parent != null) top = top.parent;
                double[] page = window[1] - window[0] < 1e6 ? new double[] {0, window[0], top.w, window[1] - window[0]} : null;
                drawBox(s, d, x, y, w, h, n.lpad.clone(), window, page);
            }
            if (d.clip) {
                s.save();
                pushed++;
                roundedPath(s, x, y, w, h, Values.resolveRadius(d.radius, w, h));
                s.clip();
            }
        }
        double cx = x + n.lpad[3] + n.lbor[3], cy = y + n.lpad[0] + n.lbor[0];
        if (n.text != null) {
            for (TextLeaf.FloatBox f : n.text.floats) draw(s, tree, f.node, x, y, window, links);
            drawText(s, tree, n, n.text, cx, cy, window, links);
        } else if (n.image != null && y < window[1] && y + h > window[0]) {
            drawImage(s, n.image, cx, cy, n.contentWidth(), n.contentHeight());
        }
        for (Box c : n.children) draw(s, tree, c, x, y, window, links);
        for (int i = 0; i < pushed; i++) s.restore();
    }
}
