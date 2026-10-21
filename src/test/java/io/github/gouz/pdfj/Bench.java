package io.github.gouz.pdfj;

import io.github.gouz.pdfj.font.Fonts;
import io.github.gouz.pdfj.net.ImagePolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Render the benchmark documents in a loop: time per document, no HTTP.
 * {@code java -cp target/pdfj.jar:target/test-classes io.github.gouz.pdfj.Bench [iterations]}
 */
public final class Bench {
    public static void main(String[] args) throws Exception {
        Fonts.init(List.of(Path.of(System.getenv().getOrDefault("FONTS_DIR", "/usr/share/fonts"))));
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        Options o = Options.defaults().withImages(new ImagePolicy(Path.of("demo"), null));
        for (String name : List.of("example", "flex", "images")) {
            String html = Files.readString(Path.of("demo/" + name + ".html"));
            // warm up: the JIT compiles the hot paths
            for (int i = 0; i < 500; i++) Pdf.htmlToPdf(html, o);
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) Pdf.htmlToPdf(html, o);
            System.out.printf("%s: %.0f µs%n", name, (System.nanoTime() - t) / 1e3 / n);
        }
    }
}
