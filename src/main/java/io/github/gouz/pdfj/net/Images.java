package io.github.gouz.pdfj.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/** Resolving and loading images, stylesheets and fonts under an {@link ImagePolicy}. */
public final class Images {
    private Images() {}

    /** Where a resource comes from: its bytes ({@code data:}, downloaded), or a file inside the base directory. */
    public record Source(byte[] data, Path file) {}

    /** Resolve a src / href under the policy; null = blocked. */
    public static Source resolve(String src, ImagePolicy policy) {
        String s = src.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) {
            int comma = s.indexOf(',');
            if (comma < 0) return null;
            String meta = s.substring(0, comma).toLowerCase(Locale.ROOT);
            String data = s.substring(comma + 1);
            if (meta.endsWith(";base64")) {
                try {
                    return new Source(Base64.getMimeDecoder().decode(data.replaceAll("\\s", "")), null);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            return new Source(percentDecode(data).getBytes(StandardCharsets.UTF_8), null);
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (policy.remote() == null) return null;
            byte[] d = Remote.cached(s, policy.remote());
            return d == null ? null : new Source(d, null);
        }
        // any other scheme (file:, ftp:, ...) is refused
        int colon = s.indexOf(':');
        if (colon > 0) {
            String scheme = s.substring(0, colon);
            if (scheme.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || "+.-".indexOf(c) >= 0))) return null;
        }
        if (policy.baseDir() == null) return null;
        try {
            Path root = policy.baseDir().toRealPath();
            String path = percentDecode(s.split("[?#]", 2)[0]);
            if (path.isEmpty()) return null;
            Path file = root.resolve(path).toRealPath();
            if (file.startsWith(root) && !file.equals(root) && Files.isRegularFile(file)) return new Source(null, file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** The bytes of a resource, or null when blocked or missing. */
    public static byte[] load(String src, ImagePolicy policy) {
        Source s = resolve(src, policy);
        if (s == null) return null;
        if (s.data() != null) return s.data();
        try {
            return Files.readAllBytes(s.file());
        } catch (IOException e) {
            return null;
        }
    }

    static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '%' && i + 2 < b.length) {
                int hi = Character.digit(b[i + 1], 16), lo = Character.digit(b[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    public static boolean isSvg(byte[] data) {
        // "<svg" in the first 512 bytes, any case
        for (int i = 0, n = Math.min(data.length, 512) - 3; i < n; i++) {
            if (data[i] == '<' && (data[i + 1] | 0x20) == 's' && (data[i + 2] | 0x20) == 'v' && (data[i + 3] | 0x20) == 'g') return true;
        }
        return false;
    }
}
