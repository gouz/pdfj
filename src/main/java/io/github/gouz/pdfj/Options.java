package io.github.gouz.pdfj;

import io.github.gouz.pdfj.net.ImagePolicy;

/**
 * Rendering options.
 *
 * @param size page size in pt (portrait); null: the document's {@code @page { size }}, else A4
 * @param landscape null: the document's {@code @page { size: landscape }}, else portrait
 * @param margin top, right, bottom, left in pt; null: the document's {@code @page { margin }}, else 40
 * @param font default family: Helvetica, Times-Roman, Courier, a CSS generic, or a family of the fonts directory
 * @param header HTML repeated in the top margin; {page} and {pages} are replaced
 * @param footer HTML repeated in the bottom margin; {page} and {pages} are replaced
 */
public record Options(double[] size, Boolean landscape, double[] margin, String font, double fontSize, String header, String footer,
                      String title, String author, String subject, String keywords, ImagePolicy images) {
    public static Options defaults() {
        return new Options(null, null, null, "Helvetica", 12, null, null, null, null, null, null, ImagePolicy.NONE);
    }

    public Options withSize(double[] v) {
        return new Options(v, landscape, margin, font, fontSize, header, footer, title, author, subject, keywords, images);
    }

    public Options withLandscape(Boolean v) {
        return new Options(size, v, margin, font, fontSize, header, footer, title, author, subject, keywords, images);
    }

    public Options withMargin(double[] v) {
        return new Options(size, landscape, v, font, fontSize, header, footer, title, author, subject, keywords, images);
    }

    public Options withFont(String v) {
        return new Options(size, landscape, margin, v, fontSize, header, footer, title, author, subject, keywords, images);
    }

    public Options withFontSize(double v) {
        return new Options(size, landscape, margin, font, v, header, footer, title, author, subject, keywords, images);
    }

    public Options withHeader(String v) {
        return new Options(size, landscape, margin, font, fontSize, v, footer, title, author, subject, keywords, images);
    }

    public Options withFooter(String v) {
        return new Options(size, landscape, margin, font, fontSize, header, v, title, author, subject, keywords, images);
    }

    public Options withTitle(String v) {
        return new Options(size, landscape, margin, font, fontSize, header, footer, v, author, subject, keywords, images);
    }

    public Options withAuthor(String v) {
        return new Options(size, landscape, margin, font, fontSize, header, footer, title, v, subject, keywords, images);
    }

    public Options withSubject(String v) {
        return new Options(size, landscape, margin, font, fontSize, header, footer, title, author, v, keywords, images);
    }

    public Options withKeywords(String v) {
        return new Options(size, landscape, margin, font, fontSize, header, footer, title, author, subject, v, images);
    }

    public Options withImages(ImagePolicy v) {
        return new Options(size, landscape, margin, font, fontSize, header, footer, title, author, subject, keywords, v);
    }

    /** {@code A4}, {@code letter}... or {@code WIDTHxHEIGHT} in pt; null if unknown */
    public static double[] pageSize(String name) {
        String n = name.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (n) {
            case "A3" -> new double[] {841.89, 1190.55};
            case "A4" -> new double[] {595.28, 841.89};
            case "A5" -> new double[] {419.53, 595.28};
            case "A6" -> new double[] {297.64, 419.53};
            case "B5" -> new double[] {498.9, 708.66};
            case "LETTER" -> new double[] {612.0, 792.0};
            case "LEGAL" -> new double[] {612.0, 1008.0};
            case "TABLOID" -> new double[] {792.0, 1224.0};
            default -> {
                int x = n.indexOf('X');
                if (x < 0) yield null;
                try {
                    double w = Double.parseDouble(n.substring(0, x).trim()), h = Double.parseDouble(n.substring(x + 1).trim());
                    yield w > 0 && h > 0 && w < 15_000 && h < 15_000 ? new double[] {w, h} : null;
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }
}
