package io.github.gouz.pdfj.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Remote resources (images, stylesheets, fonts) over http(s): off unless enabled.
 *
 * <p>The HTML may be untrusted, so a fetch is the server making a request on behalf of whoever wrote it
 * (SSRF). Guards: http(s) only, addresses resolved to loopback / private / link-local / unique-local /
 * multicast are refused (checked on every resolution by {@link GuardedResolverProvider}, so redirects and
 * DNS rebinding too), optional host allow-list, timeout, size limit, at most 3 redirects.
 */
public final class Remote {
    private Remote() {}

    /**
     * @param hosts {@code example.com}, {@code *.example.com}; empty: any public host
     * @param allowPrivate allow loopback / private networks (tests, or a trusted internal network)
     */
    public record Config(List<String> hosts, boolean allowPrivate, Duration timeout, long maxBytes) {
        public static Config defaults() {
            return new Config(List.of(), false, Duration.ofSeconds(5), 10L * 1024 * 1024);
        }

        public Config withAllowPrivate(boolean allow) {
            return new Config(hosts, allow, timeout, maxBytes);
        }

        public Config withHosts(List<String> h) {
            return new Config(h, allowPrivate, timeout, maxBytes);
        }

        /** From {@code REMOTE_HOSTS} (comma separated), {@code REMOTE_ALLOW_PRIVATE=1}, {@code REMOTE_TIMEOUT_MS}, {@code REMOTE_MAX_MB}. */
        public static Config fromEnv() {
            Config d = defaults();
            List<String> hosts = new ArrayList<>();
            String h = env("REMOTE_HOSTS");
            if (h != null) for (String s : h.split(",")) if (!s.isBlank()) hosts.add(s.trim().toLowerCase(Locale.ROOT));
            String p = env("REMOTE_ALLOW_PRIVATE");
            boolean allow = p != null && (p.equals("1") || p.equals("true") || p.equals("yes"));
            Duration timeout = d.timeout;
            try {
                if (env("REMOTE_TIMEOUT_MS") != null) timeout = Duration.ofMillis(Long.parseLong(env("REMOTE_TIMEOUT_MS")));
            } catch (NumberFormatException ignored) {
                // default
            }
            long max = d.maxBytes;
            try {
                if (env("REMOTE_MAX_MB") != null) max = Long.parseLong(env("REMOTE_MAX_MB")) * 1024 * 1024;
            } catch (NumberFormatException ignored) {
                // default
            }
            return new Config(List.copyOf(hosts), allow, timeout, max);
        }

        private static String env(String k) {
            String v = System.getenv(k);
            return v == null || v.isBlank() ? null : v.trim();
        }

        public boolean hostAllowed(String host) {
            String hl = host.toLowerCase(Locale.ROOT);
            if (hosts.isEmpty()) return true;
            for (String hh : hosts) {
                if (hh.startsWith("*.")) {
                    String domain = hh.substring(2);
                    if (hl.endsWith("." + domain) || hl.equals(domain)) return true;
                } else if (hl.equals(hh)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Addresses a document must never make the server talk to. */
    public static boolean isPublic(InetAddress ip) {
        byte[] a = ip.getAddress();
        if (ip instanceof Inet4Address) {
            int o0 = a[0] & 255, o1 = a[1] & 255, o2 = a[2] & 255;
            return !(o0 == 127 || o0 == 10 || (o0 == 172 && o1 >= 16 && o1 < 32) || (o0 == 192 && o1 == 168)
                || (o0 == 169 && o1 == 254) || o0 == 0 || ip.isMulticastAddress() || o0 >= 240
                || (o0 == 192 && o1 == 0 && o2 == 2) || (o0 == 198 && o1 == 51 && o2 == 100) || (o0 == 203 && o1 == 0 && o2 == 113)
                || (o0 == 100 && o1 >= 64 && o1 < 128) // carrier-grade NAT
                || (o0 == 198 && (o1 == 18 || o1 == 19))); // benchmarking
        }
        if (ip instanceof Inet6Address) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= a[i] == 0;
            mapped &= (a[10] & 255) == 0xff && (a[11] & 255) == 0xff;
            if (mapped) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {a[12], a[13], a[14], a[15]}));
                } catch (IOException e) {
                    return false;
                }
            }
            int s0 = (a[0] & 255) << 8 | (a[1] & 255), s1 = (a[2] & 255) << 8 | (a[3] & 255);
            return !(ip.isLoopbackAddress() || ip.isAnyLocalAddress() || ip.isMulticastAddress()
                || (s0 & 0xfe00) == 0xfc00 // unique local
                || (s0 & 0xffc0) == 0xfe80 // link local
                || (s0 == 0x2001 && s1 == 0xdb8)); // documentation
        }
        return false;
    }

    /** hosts being fetched with the private-address guard on: their resolutions are filtered */
    static final Map<String, Integer> GUARDED = new ConcurrentHashMap<>();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final ExecutorService FETCHERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pdfj-fetch-", 0).factory());

    static String hostOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) return null;
        String rest = url.substring(i + 3);
        String authority = rest.split("[/?#]", 2)[0];
        int at = authority.lastIndexOf('@');
        String host = at >= 0 ? authority.substring(at + 1) : authority;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close < 0 ? host.substring(1) : host.substring(1, close);
        } else {
            host = host.split(":", 2)[0];
        }
        return host.isEmpty() ? null : host;
    }

    private static boolean isLiteral(String host) {
        return host.contains(":") || host.matches("[0-9.]+");
    }

    private static String shorten(String url) {
        return url.length() > 100 ? url.substring(0, 100) : url;
    }

    /** GET {@code url} under the config: the body, or null (blocked, error, too big, not 2xx). */
    public static byte[] fetch(String url, Config c) {
        Future<byte[]> f = FETCHERS.submit(() -> fetchNow(url, c));
        try {
            return f.get(c.timeout().toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            System.err.println("pdfj: remote resource failed: " + shorten(url) + " (" + (e.getCause() != null ? e.getCause() : e) + ")");
            return null;
        }
    }

    private static byte[] fetchNow(String start, Config c) throws IOException, InterruptedException {
        String url = start;
        for (int hop = 0; hop < 4; hop++) {
            String lower = url.toLowerCase(Locale.ROOT);
            String host = hostOf(url);
            if (!(lower.startsWith("http://") || lower.startsWith("https://")) || host == null || !c.hostAllowed(host)) {
                System.err.println("pdfj: remote resource refused: " + shorten(url));
                return null;
            }
            if (!c.allowPrivate() && isLiteral(host) && !isPublic(InetAddress.getByName(host))) {
                System.err.println("pdfj: remote resource refused (private address): " + shorten(url));
                return null;
            }
            String key = host.toLowerCase(Locale.ROOT);
            if (!c.allowPrivate()) GUARDED.merge(key, 1, Integer::sum);
            HttpResponse<InputStream> res;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(c.timeout()).header("User-Agent", "pdfj").GET().build();
                res = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IllegalArgumentException e) {
                System.err.println("pdfj: remote resource refused: " + shorten(url));
                return null;
            } finally {
                if (!c.allowPrivate()) GUARDED.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
            }
            int status = res.statusCode();
            try (InputStream body = res.body()) {
                if (status >= 300 && status < 400) {
                    Optional<String> loc = res.headers().firstValue("location");
                    if (loc.isEmpty()) return null;
                    String location = loc.get();
                    if (location.contains("://")) url = location;
                    else if (location.startsWith("/")) {
                        int i = url.indexOf("://");
                        String scheme = url.substring(0, i);
                        String rest = url.substring(i + 3);
                        url = scheme + "://" + rest.split("/", 2)[0] + location;
                    } else {
                        url = url.substring(0, url.lastIndexOf('/')) + "/" + location;
                    }
                    continue;
                }
                if (status < 200 || status >= 300) {
                    System.err.println("pdfj: remote resource " + status + ": " + shorten(url));
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = body.read(buf)) > 0) {
                    total += n;
                    if (total > c.maxBytes()) {
                        System.err.println("pdfj: remote resource too large: " + shorten(url));
                        return null;
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }
        return null;
    }

    /** fetched resources of the document being rendered on this thread (null value: failed) */
    private static final ThreadLocal<Map<String, byte[]>> CACHE = ThreadLocal.withInitial(HashMap::new);
    private static final byte[] FAILED = new byte[0];

    /** Fetch once per document: the same URL used twice is downloaded once. */
    public static byte[] cached(String url, Config c) {
        Map<String, byte[]> m = CACHE.get();
        byte[] hit = m.get(url);
        if (hit != null) return hit == FAILED ? null : hit;
        byte[] d = fetch(url, c);
        m.put(url, d == null ? FAILED : d);
        return d;
    }

    /** Download these URLs in parallel (8 at a time) into the document cache. */
    public static void prefetch(List<String> urls, Config c) {
        Map<String, byte[]> m = CACHE.get();
        List<String> todo = new ArrayList<>(new TreeSet<>(urls));
        todo.removeIf(m::containsKey);
        for (int i = 0; i < todo.size(); i += 8) {
            List<String> chunk = todo.subList(i, Math.min(todo.size(), i + 8));
            List<Future<byte[]>> fs = new ArrayList<>();
            for (String u : chunk) fs.add(FETCHERS.submit(() -> fetch(u, c)));
            for (int k = 0; k < chunk.size(); k++) {
                byte[] d;
                try {
                    d = fs.get(k).get();
                } catch (Exception e) {
                    d = null;
                }
                m.put(chunk.get(k), d == null ? FAILED : d);
            }
        }
    }

    /** Forget the document's downloads. */
    public static void endDocument() {
        CACHE.get().clear();
    }
}
