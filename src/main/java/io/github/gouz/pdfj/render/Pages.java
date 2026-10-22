package io.github.gouz.pdfj.render;

import io.github.gouz.pdfj.Options;
import io.github.gouz.pdfj.Pdf;
import io.github.gouz.pdfj.css.Css.Media;
import io.github.gouz.pdfj.css.Css.PageRule;
import io.github.gouz.pdfj.font.FontFace;
import io.github.gouz.pdfj.layout.Box;
import io.github.gouz.pdfj.layout.Deco;
import io.github.gouz.pdfj.layout.Tree;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The PDF: every page of every section, with repeated table headers / footers, margin boxes, links, metadata. */
public final class Pages {
    /** the buffer a render thread saves its documents into, kept between documents unless it grew past {@code KEEP} */
    private static final ThreadLocal<ByteArrayOutputStream> OUT = ThreadLocal.withInitial(() -> new ByteArrayOutputStream(64 * 1024));
    private static final int KEEP = 1024 * 1024;

    private Pages() {}

    /** a section root laid out at the width of its pages: page size and margins of each kind of page */
    public record Section(Box root, String name, double pw, double ph, double[][] margins) {}

    private record Link(double x, double y, double w, double h, String href) {}

    private static final double[] EVERYWHERE = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};

    public static byte[] write(Options o, String title, Tree tree, List<Section> sections, Deco canvas, List<PageRule> rules, Media media, List<FontFace> faces) {
        // pages of every section, numbered across sections (left / right / first by global index)
        List<Integer> pageSection = new ArrayList<>();
        List<Paginate.Page> pages = new ArrayList<>();
        for (int si = 0; si < sections.size(); si++) {
            Section sec = sections.get(si);
            int offset = pages.size();
            Paginate.Breaks breaks = Paginate.collect(sec.root());
            for (Paginate.Page p : Paginate.paginate(i -> {
                double[] m = sec.margins()[Pdf.kindOf(offset + i)];
                return Math.max(1, sec.ph() - m[0] - m[2]);
            }, breaks)) {
                pages.add(p);
                pageSection.add(si);
            }
        }
        // header / footer (the request's, else the @page margin boxes) per section and kind of page
        Map<String, List<Pdf.Band>> bands = new HashMap<>();
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setCreator("pdfj");
            info.setProducer("pdfj");
            if (title != null) info.setTitle(title);
            if (o.author() != null) info.setAuthor(o.author());
            if (o.subject() != null) info.setSubject(o.subject());
            if (o.keywords() != null) info.setKeywords(o.keywords());
            Canvas.Shared shared = new Canvas.Shared(doc);
            int n = pages.size();
            for (int i = 0; i < n; i++) {
                int si = pageSection.get(i);
                Section sec = sections.get(si);
                Paginate.Page pg = pages.get(i);
                int k = Pdf.kindOf(i);
                double[] m = sec.margins()[k];
                double mt = m[0], ml = m[3];
                double pw = sec.pw(), ph = sec.ph();
                int index = i;
                List<Pdf.Band> pageBands = bands.computeIfAbsent(si + ":" + k, key -> Pdf.bandsFor(o, rules, index, sec.name(), pw, ph, m));
                PDPage page = new PDPage(new PDRectangle((float) pw, (float) ph));
                doc.addPage(page);
                Canvas s = new Canvas(shared);
                s.transform(1, 0, 0, -1, 0, ph);
                List<Link> links = new ArrayList<>();
                if (canvas != null) Painter.drawCanvas(s, canvas, pw, ph);
                // a table continued from the previous page: its <thead> again on top
                double hh = pg.headerHeight();
                if (pg.header() != null && pg.header().head() != null && hh > 0) {
                    Paginate.TableHead h = pg.header();
                    s.save();
                    s.rect(0, mt, pw, Math.max(hh, 0.01));
                    s.clip();
                    s.translate(ml, mt - h.head()[0]);
                    Painter.draw(s, tree, h.table(), h.ox(), h.oy(), h.head(), new ArrayList<>());
                    s.restore();
                }
                // content area: clipped vertically to the slice of the column this page shows
                double top = mt + hh;
                double start = pg.start(), end = pg.end();
                List<Painter.Link> found = new ArrayList<>();
                s.save();
                s.rect(0, top, pw, Math.max(end - start, 0.01));
                s.clip();
                s.translate(ml, top - start);
                Painter.draw(s, tree, sec.root(), 0, 0, new double[] {start, end}, found);
                s.restore();
                for (Painter.Link l : found) {
                    double x = l.x() + ml, y = l.y() + top - start;
                    // only links visible in this page's content area
                    if (l.href() < 1 || l.href() > tree.links.size()) continue;
                    if (y + l.h() > top && y < top + (end - start)) links.add(new Link(x, y, l.w(), l.h(), tree.links.get(l.href() - 1)));
                }
                // a table broken by this page: its <tfoot> again right after the last row
                double fh = pg.footerHeight();
                double footTop = top + (end - start);
                if (pg.footer() != null && pg.footer().foot() != null && fh > 0) {
                    Paginate.TableHead f = pg.footer();
                    s.save();
                    s.rect(0, footTop, pw, Math.max(fh, 0.01));
                    s.clip();
                    s.translate(ml, footTop - f.foot()[0]);
                    Painter.draw(s, tree, f.table(), f.ox(), f.oy(), f.foot(), new ArrayList<>());
                    s.restore();
                }
                // header / footer, vertically centered in their margin
                for (Pdf.Band b : pageBands) {
                    Tree t = Pdf.band(b.html(), i + 1, n, o, b.w(), media, faces);
                    double th = t.root.h;
                    double y = b.y() + (b.h() - th) / 2;
                    List<Painter.Link> bl = new ArrayList<>();
                    Painter.draw(s, t, t.root, b.x(), y, EVERYWHERE, bl);
                    for (Painter.Link l : bl) {
                        if (l.href() >= 1 && l.href() <= t.links.size()) links.add(new Link(l.x(), l.y(), l.w(), l.h(), t.links.get(l.href() - 1)));
                    }
                }
                page.setResources(s.resources);
                page.setContents(new PDStream(s.contents(doc)));
                for (Link l : links) {
                    PDAnnotationLink a = new PDAnnotationLink();
                    a.setRectangle(new PDRectangle((float) l.x, (float) (ph - l.y - Math.max(l.h, 0.1)), (float) Math.max(l.w, 0.1), (float) Math.max(l.h, 0.1)));
                    PDBorderStyleDictionary bs = new PDBorderStyleDictionary();
                    bs.setWidth(0);
                    a.setBorderStyle(bs);
                    PDActionURI action = new PDActionURI();
                    action.setURI(l.href);
                    a.setAction(action);
                    page.getAnnotations().add(a);
                }
            }
            shared.finish();
            ByteArrayOutputStream out = OUT.get();
            out.reset();
            // objects written as they are (no object streams): faster, and what krilla writes
            doc.save(out, CompressParameters.NO_COMPRESSION);
            byte[] pdf = out.toByteArray();
            if (pdf.length > KEEP) OUT.remove();
            return pdf;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
