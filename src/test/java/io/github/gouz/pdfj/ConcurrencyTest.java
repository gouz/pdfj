package io.github.gouz.pdfj;

import io.github.gouz.pdfj.net.ImagePolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Render threads share fonts and caches: the same documents rendered in parallel come out the same. */
class ConcurrencyTest {
    @Test
    void parallelRendersMatchSequentialOnes() throws Exception {
        PdfText.init();
        Options o = Options.defaults().withImages(new ImagePolicy(Path.of("demo"), null));
        List<String> docs = new ArrayList<>();
        for (String name : List.of("example", "flex", "images", "report", "features", "print")) docs.add(Files.readString(Path.of("demo/" + name + ".html")));
        List<String> expected = new ArrayList<>();
        for (String html : docs) expected.add(PdfText.text(Pdf.htmlToPdf(html, o)));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int round = 0; round < 8; round++) {
                for (String html : docs) results.add(pool.submit(() -> PdfText.text(Pdf.htmlToPdf(html, o))));
            }
            for (int i = 0; i < results.size(); i++) assertEquals(expected.get(i % docs.size()), results.get(i).get(), "document " + i % docs.size());
        } finally {
            pool.shutdown();
        }
    }
}
