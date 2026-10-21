package io.github.gouz.pdfj;

import io.github.gouz.pdfj.font.Fonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reading back the PDFs the tests render: pages, text, words with their position. */
final class PdfText {
    private PdfText() {}

    static final Path FONTS = Path.of(System.getenv().getOrDefault("FONTS_DIR", "/usr/share/fonts"));
    private static boolean fontsLoaded;

    static synchronized void init() {
        if (!fontsLoaded) {
            if (Fonts.init(List.of(FONTS)) == 0) throw new IllegalStateException("no fonts found, set FONTS_DIR");
            fontsLoaded = true;
        }
    }

    static byte[] pdf(String html, Options o) {
        init();
        byte[] pdf = Pdf.htmlToPdf(html, o);
        if (!new String(pdf, 0, 5).equals("%PDF-")) throw new AssertionError("not a PDF");
        return pdf;
    }

    static byte[] pdf(String html) {
        return pdf(html, Options.defaults());
    }

    static int pages(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** page sizes "w h", in page order */
    static List<String> mediaBoxes(byte[] pdf) {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            for (PDPage p : d.getPages()) out.add(fmt(p.getMediaBox().getWidth()) + " " + fmt(p.getMediaBox().getHeight()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** the text, pages separated by form feeds (like pdftotext) */
    static String text(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            s.setPageEnd("\f");
            return s.getText(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** the text of each page */
    static List<String> pagesText(byte[] pdf) {
        List<String> out = new ArrayList<>();
        for (String p : text(pdf).split("\f", -1)) out.add(p);
        if (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    /** a word: its text, left edge, baseline (from the top of its page), right edge, page (0-based) */
    record Word(String text, double x, double y, double xMax, int page) {}

    static List<Word> words(byte[] pdf) {
        List<Word> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (positions.isEmpty()) return;
                    // a "word" of PDFBox may hold several: split on spaces
                    StringBuilder cur = new StringBuilder();
                    TextPosition first = null, last = null;
                    for (TextPosition t : positions) {
                        String u = t.getUnicode();
                        if (u.isBlank()) {
                            if (first != null) out.add(new Word(cur.toString(), first.getXDirAdj(), first.getYDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(), getCurrentPageNo() - 1));
                            cur.setLength(0);
                            first = null;
                            continue;
                        }
                        if (first == null) first = t;
                        last = t;
                        cur.append(u);
                    }
                    if (first != null) out.add(new Word(cur.toString(), first.getXDirAdj(), first.getYDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(), getCurrentPageNo() - 1));
                }
            };
            s.setSortByPosition(true);
            s.getText(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static Word word(List<Word> words, String text) {
        return words.stream().filter(w -> w.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError(text + " not found in " + words));
    }

    static byte[] liberationSerif() {
        for (String f : List.of("liberation/LiberationSerif-Regular.ttf", "truetype/liberation/LiberationSerif-Regular.ttf")) {
            Path p = FONTS.resolve(f);
            if (Files.exists(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return null;
    }

    /** the raw bytes of the PDF as text (for its uncompressed parts) */
    static String raw(byte[] pdf) {
        return new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** the names of the embedded fonts */
    static List<String> fontNames(byte[] pdf) {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            for (PDPage p : d.getPages()) {
                for (var n : p.getResources().getFontNames()) out.add(p.getResources().getFont(n).getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
