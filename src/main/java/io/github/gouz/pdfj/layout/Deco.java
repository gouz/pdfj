package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Rgba;
import io.github.gouz.pdfj.style.Style.BgSize;
import io.github.gouz.pdfj.style.Style.BorderStyle;
import io.github.gouz.pdfj.style.Style.BoxArea;
import io.github.gouz.pdfj.style.Style.Gradient;
import io.github.gouz.pdfj.style.Style.Shadow;

import java.util.ArrayList;
import java.util.List;

/** What is drawn for an element box besides its children. */
public final class Deco {
    public Rgba background;
    public double[] borderWidth = new double[4];
    /** null = no border on that side */
    public BorderStyle[] borderStyle = new BorderStyle[4];
    public Rgba[] borderColor = new Rgba[4];
    public Len[] radius = {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
    public double opacity = 1;
    public boolean clip;
    public boolean breakBefore, breakAfter, avoidBreakInside;
    /** background image layers, first on top */
    public List<BgLayer> layers = new ArrayList<>();
    /** the background color's clip: the last layer's background-clip */
    public BoxArea colorClip = BoxArea.BORDER;
    public List<Shadow> shadows;
    /** {@code transform}, {@code transform-origin}, font size (for em units) */
    public String transform, transformOrigin;
    public double transformEm;

    /** anything to draw or to take into account when paginating */
    boolean needed() {
        if (background != null || !layers.isEmpty() || shadows != null || opacity < 1 || clip || transform != null) return true;
        if (breakBefore || breakAfter || avoidBreakInside) return true;
        for (double w : borderWidth) if (w > 0) return true;
        return false;
    }

    public Deco copy() {
        Deco d = new Deco();
        d.background = background;
        d.borderWidth = borderWidth.clone();
        d.borderStyle = borderStyle.clone();
        d.borderColor = borderColor.clone();
        d.radius = radius.clone();
        d.opacity = opacity;
        d.clip = clip;
        d.breakBefore = breakBefore;
        d.breakAfter = breakAfter;
        d.avoidBreakInside = avoidBreakInside;
        d.layers = new ArrayList<>(layers);
        d.colorClip = colorClip;
        d.shadows = shadows;
        d.transform = transform;
        d.transformOrigin = transformOrigin;
        d.transformEm = transformEm;
        return d;
    }

    /** What a background layer paints: a gradient, or a decoded image and its natural size. */
    public sealed interface LayerPaint {
        record GradientPaint(Gradient gradient) implements LayerPaint {}

        record ImagePaint(ImageKind image, double width, double height) implements LayerPaint {}
    }

    /** A background layer, ready to draw. */
    public record BgLayer(LayerPaint paint, BgSize size, Len[] position, boolean[] repeat, BoxArea origin, BoxArea clip, boolean fixed) {}
}
