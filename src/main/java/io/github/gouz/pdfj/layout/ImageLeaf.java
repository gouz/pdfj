package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Style.ObjectFit;

/** An {@code <img>} or inline {@code <svg>}: its image, natural size (pt), object-fit and corner radii. */
public record ImageLeaf(ImageKind kind, double naturalWidth, double naturalHeight, ObjectFit fit, Len[] radius) {}
