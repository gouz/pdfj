package io.github.gouz.pdfj.font;

import org.apache.fontbox.ttf.TrueTypeFont;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Font registry: every .ttf/.otf/.ttc/.woff/.woff2 found in the font directories, loaded once at
 * startup and shared (read-only) by all render threads.
 *
 * <p>The PDF base-14 names and CSS generic families map to metric-compatible fonts:
 * Helvetica/Arial/sans-serif -> Liberation Sans, Times/serif -> Liberation Serif,
 * Courier/monospace -> Liberation Mono. DejaVu Sans is the last-resort fallback for missing glyphs.
 */
public final class Fonts {
    private Fonts() {}

    private static final List<String> SANS = List.of("Liberation Sans", "Arimo", "Helvetica", "Arial", "DejaVu Sans");
    private static final List<String> SERIF = List.of("Liberation Serif", "Tinos", "Times New Roman", "Times", "DejaVu Serif");
    private static final List<String> MONO = List.of("Liberation Mono", "Cousine", "Courier New", "Courier", "DejaVu Sans Mono");
    private static final List<String> FALLBACK = List.of("DejaVu Sans", "Noto Sans", "Arial Unicode MS");

    private static final Map<String, List<String>> GENERIC = new LinkedHashMap<>();

    static {
        for (String g : List.of("sans-serif", "system-ui", "-apple-system", "ui-sans-serif", "arial", "helvetica", "helvetica neue")) GENERIC.put(g, SANS);
        for (String g : List.of("serif", "ui-serif", "times", "times-roman", "times new roman")) GENERIC.put(g, SERIF);
        for (String g : List.of("monospace", "ui-monospace", "courier", "courier new")) GENERIC.put(g, MONO);
    }

    /** The shared fonts. */
    public static final class Registry {
        final List<FontFace> faces;
        /** lowercase family name -> its faces */
        final Map<String, List<FontFace>> families;
        /** lowercase family name -> family name as registered */
        final Map<String, String> names;
        /** generic -> registered family */
        final Map<String, String> aliases;
        public final String fallback;

        Registry(List<FontFace> faces) {
            this.faces = faces;
            this.families = new HashMap<>();
            this.names = new HashMap<>();
            for (FontFace f : faces) {
                String key = f.family.toLowerCase(Locale.ROOT);
                families.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                names.putIfAbsent(key, f.family);
            }
            this.aliases = new HashMap<>();
            for (Map.Entry<String, List<String>> g : GENERIC.entrySet()) {
                String f = pick(g.getValue());
                if (f != null) aliases.put(g.getKey(), f);
            }
            this.fallback = pick(FALLBACK);
        }

        private String pick(List<String> candidates) {
            for (String c : candidates) {
                String n = names.get(c.toLowerCase(Locale.ROOT));
                if (n != null) return n;
            }
            return null;
        }

        public List<FontFace> faces() {
            return faces;
        }
    }

    private static volatile Registry registry = new Registry(List.of());

    public static Registry registry() {
        return registry;
    }

    /** Load every font of {@code dirs} (recursively). Returns the number of font files; call once. */
    public static int init(List<Path> dirs) {
        List<Path> files = new ArrayList<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> walk = Files.walk(d)) {
                walk.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc") || n.endsWith(".woff") || n.endsWith(".woff2");
                }).forEach(files::add);
            } catch (IOException e) {
                System.err.println("pdfj: cannot read font directory " + d + ": " + e.getMessage());
            }
        }
        files.sort(null);
        List<FontFace> faces = new ArrayList<>();
        for (Path f : files) {
            try {
                byte[] data = Woff.toSfnt(Files.readAllBytes(f));
                if (data != null) faces.addAll(FontFace.load(data, true));
            } catch (IOException | RuntimeException e) {
                System.err.println("pdfj: font skipped: " + f + " (" + e.getMessage() + ")");
            }
        }
        registry = new Registry(List.copyOf(faces));
        return files.size();
    }

    /** The @font-face fonts of the document being built or drawn on this thread. */
    private static final ThreadLocal<DocFonts> DOC = new ThreadLocal<>();

    /** A document's @font-face faces, by lowercase family. */
    public static final class DocFonts {
        final Map<String, List<FontFace>> families = new HashMap<>();
        final Map<String, String> names = new HashMap<>();

        public DocFonts(List<FontFace> faces) {
            for (FontFace f : faces) {
                String key = f.family.toLowerCase(Locale.ROOT);
                families.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                names.put(key, f.family);
            }
        }

        public boolean isEmpty() {
            return families.isEmpty();
        }
    }

    /** Run {@code f} with the document's @font-face fonts visible (to this call only). */
    public static <R> R withDocFonts(DocFonts doc, Supplier<R> f) {
        DocFonts before = DOC.get();
        DOC.set(doc);
        try {
            return f.get();
        } finally {
            if (before == null) DOC.remove();
            else DOC.set(before);
        }
    }

    /**
     * First family of a CSS stack that can be drawn, as registered.
     * null when no family is usable: the declaration is then ignored (the parent's family is inherited).
     */
    public static String resolveStack(String stack) {
        Registry r = registry;
        DocFonts doc = DOC.get();
        for (String raw : stack.replace("&quot;", "").split(",")) {
            String name = raw.trim();
            int a = 0, b = name.length();
            while (a < b && (name.charAt(a) == '"' || name.charAt(a) == '\'')) a++;
            while (b > a && (name.charAt(b - 1) == '"' || name.charAt(b - 1) == '\'')) b--;
            name = name.substring(a, b).trim().toLowerCase(Locale.ROOT);
            String family = doc == null ? null : doc.names.get(name);
            if (family == null) family = r.names.get(name);
            if (family == null) family = r.aliases.get(name);
            if (family != null) return family;
        }
        return null;
    }

    /** The faces of a family (the document's @font-face first), empty if unknown. */
    static List<FontFace> facesOf(String family) {
        String key = family.toLowerCase(Locale.ROOT);
        DocFonts doc = DOC.get();
        if (doc != null) {
            List<FontFace> f = doc.families.get(key);
            if (f != null) return f;
        }
        return registry.families.getOrDefault(key, List.of());
    }

    /** CSS font matching: the style first, then the nearest weight (lighter first up to 500, heavier above). */
    public static FontFace match(String family, double weight, boolean italic) {
        List<FontFace> faces = facesOf(family);
        FontFace best = null;
        double bestScore = Double.MAX_VALUE;
        for (FontFace f : faces) {
            double score = (f.italic == italic ? 0 : 10000) + weightDistance(weight, f.weight);
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    private static double weightDistance(double want, int have) {
        if (have == want) return 0;
        if (want >= 400 && want <= 500) {
            if (have >= want && have <= 500) return have - want;
            if (have < want) return 1000 + (want - have);
            return 2000 + (have - want);
        }
        if (want < 400) return have < want ? want - have : 1000 + (have - want);
        return have > want ? have - want : 1000 + (want - have);
    }

    /** Some face that has a glyph for the code point, the closest in weight / style; null if none. */
    public static FontFace anyWith(int codePoint, double weight, boolean italic) {
        Registry r = registry;
        String fb = r.fallback;
        if (fb != null) {
            FontFace f = match(fb, weight, italic);
            if (f != null && f.has(codePoint)) return f;
        }
        FontFace best = null;
        double bestScore = Double.MAX_VALUE;
        DocFonts doc = DOC.get();
        List<List<FontFace>> pools = new ArrayList<>();
        if (doc != null) pools.addAll(doc.families.values());
        pools.add(r.faces);
        for (List<FontFace> pool : pools) {
            for (FontFace f : pool) {
                if (!f.has(codePoint)) continue;
                double score = (f.italic == italic ? 0 : 10000) + weightDistance(weight, f.weight);
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
        }
        return best;
    }

    /**
     * The face parsed again, to embed in one PDF (fontbox fonts are not thread-safe, and PDFBox closes
     * the font once it has written its subset).
     */
    public static TrueTypeFont pdfFont(FontFace face) throws IOException {
        return face.parseAgain();
    }

    /** Forget the document's fonts (nothing is kept between documents). */
    public static void endDocument() {}
}
