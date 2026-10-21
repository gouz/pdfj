package io.github.gouz.pdfj.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import com.hubspot.jinjava.interpret.Context;
import com.hubspot.jinjava.interpret.FatalTemplateErrorsException;
import com.hubspot.jinjava.interpret.InterpretException;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.interpret.RenderResult;
import com.hubspot.jinjava.interpret.TemplateError;
import com.hubspot.jinjava.lib.filter.AdvancedFilter;
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition;
import com.hubspot.jinjava.loader.ResourceLocator;
import com.hubspot.jinjava.loader.ResourceNotFoundException;
import com.hubspot.jinjava.objects.SafeString;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTML templates (Jinja syntax), filled with JSON data before rendering.
 *
 * <p>Strict: a variable missing from the data is an error (use {@code | default(...)} for optional
 * fields). Every output is HTML-escaped ({@code | safe} opts out). A template can only read the
 * templates of its directory ({@code {% extends %}}, {@code {% include %}}), and its run time is bounded.
 */
public final class Templates {
    private Templates() {}

    /** Where the template comes from: its text (sent with the request), or a file of the templates directory. */
    public sealed interface Source {
        record Inline(String text) implements Source {}

        record Named(String name) implements Source {}
    }

    /** A template or data error: what went wrong and where. */
    public static final class TemplateException extends Exception {
        public TemplateException(String message) {
            super(message);
        }
    }

    /** {@code range()} items a template may produce (loops): enough for thousands of table rows, not for an endless loop. */
    static final long FUEL = 10_000_000;
    private static final ThreadLocal<long[]> BUDGET = ThreadLocal.withInitial(() -> new long[1]);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** templates of a directory, by name: inside the directory only, symlinks included */
    private record DirLocator(Path root) implements ResourceLocator {
        @Override
        public String getString(String fullName, Charset encoding, JinjavaInterpreter interpreter) throws IOException {
            if (root == null) throw new ResourceNotFoundException("template not found: " + fullName);
            String name = fullName.startsWith("/") ? null : fullName;
            if (name == null || name.isEmpty()) throw new ResourceNotFoundException("template not found: " + fullName);
            try {
                Path p = root.resolve(name).normalize();
                if (!p.startsWith(root)) throw new ResourceNotFoundException("template not found: " + fullName);
                Path real = p.toRealPath();
                if (!real.startsWith(root) || !Files.isRegularFile(real)) throw new ResourceNotFoundException("template not found: " + fullName);
                return Files.readString(real, encoding);
            } catch (IOException | RuntimeException e) {
                if (e instanceof ResourceNotFoundException r) throw r;
                throw new ResourceNotFoundException("template not found: " + fullName);
            }
        }
    }

    /** one environment per templates directory */
    private static final Map<String, Jinjava> ENVS = new ConcurrentHashMap<>();

    private static Jinjava environment(Path dir) {
        Path root = null;
        if (dir != null) {
            try {
                root = dir.toRealPath();
            } catch (IOException e) {
                root = dir.toAbsolutePath().normalize();
            }
        }
        Path r = root;
        return ENVS.computeIfAbsent(String.valueOf(root), k -> {
            JinjavaConfig config = JinjavaConfig.newBuilder()
                .withFailOnUnknownTokens(true)
                .withMaxRenderDepth(100)
                .withMaxMacroRecursionDepth(100)
                .withEnableRecursiveMacroCalls(true)
                .withMaxOutputSize(64L * 1024 * 1024)
                .withNestedInterpretationEnabled(false)
                .build();
            Jinjava j = new Jinjava(config);
            j.setResourceLocator(new DirLocator(r));
            Context g = j.getGlobalContext();
            g.registerFilter(new Money());
            g.registerFilter(new NumberFilter());
            g.registerFilter(new DateFilter());
            g.registerFilter(new Nl2br());
            g.registerFunction(new ELFunctionDefinition("", "range", Templates.class, "range", Object.class, Object[].class));
            return j;
        });
    }

    /** {@code range(n)}, {@code range(a, b[, step])}, counted against the template's budget */
    public static List<Long> range(Object a, Object... rest) {
        long start = 0, stop, step = 1;
        if (rest == null || rest.length == 0) {
            stop = toLong(a);
        } else {
            start = toLong(a);
            stop = toLong(rest[0]);
            if (rest.length > 1) step = toLong(rest[1]);
        }
        if (step == 0) throw new IllegalArgumentException("range: step 0");
        long n = step > 0 ? Math.max(0, (stop - start + step - 1) / step) : Math.max(0, (start - stop - step - 1) / -step);
        long[] budget = BUDGET.get();
        budget[0] += n;
        if (budget[0] > FUEL) throw new OutOfFuel();
        List<Long> out = new ArrayList<>((int) Math.min(n, 1 << 20));
        for (long v = start, i = 0; i < n; i++, v += step) out.add(v);
        return out;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return (long) Double.parseDouble(String.valueOf(o).trim());
    }

    static final class OutOfFuel extends RuntimeException {
        OutOfFuel() {
            super("template too long to run (endless loop?)", null, false, false);
        }
    }

    /**
     * Render a template with {@code data} (a JSON object), {@code locale} for the number / money / date
     * filters. The error says what went wrong and where (template name and line).
     */
    @SuppressWarnings("unchecked")
    public static String render(Source source, JsonNode data, Path dir, String locale) throws TemplateException {
        Jinjava env = environment(dir);
        Map<String, Object> ctx;
        if (data == null || data.isNull() || data.isMissingNode()) ctx = new LinkedHashMap<>();
        else if (data.isObject()) ctx = JSON.convertValue(data, LinkedHashMap.class);
        else throw new TemplateException("data: a JSON object is expected");
        // the locale is visible to the filters, not a variable of the data
        ctx.put("_locale", locale);
        String name = null, text;
        if (source instanceof Source.Named n) {
            name = n.name();
            try {
                text = new DirLocator(rootOf(dir)).getString(name, java.nio.charset.StandardCharsets.UTF_8, null);
            } catch (IOException e) {
                throw new TemplateException("template not found: " + name);
            }
        } else {
            text = ((Source.Inline) source).text();
        }
        BUDGET.get()[0] = 0;
        RenderResult result;
        try {
            Context context = new Context(env.getGlobalContext(), ctx);
            context.setAutoEscape(true);
            JinjavaInterpreter interpreter = new JinjavaInterpreter(env, context, env.getGlobalConfig());
            JinjavaInterpreter.pushCurrent(interpreter);
            try {
                String out = interpreter.render(text);
                result = new RenderResult(out, context, interpreter.getErrorsCopy());
            } finally {
                JinjavaInterpreter.popCurrent();
            }
        } catch (OutOfFuel e) {
            throw new TemplateException(e.getMessage());
        } catch (FatalTemplateErrorsException e) {
            throw new TemplateException(describe(e.getErrors().iterator().hasNext() ? e.getErrors().iterator().next() : null, e.getMessage(), name));
        } catch (InterpretException e) {
            throw new TemplateException(describe(null, e.getMessage(), name) + (e.getLineNumber() > 0 ? lineSuffix(name, e.getLineNumber()) : ""));
        } catch (RuntimeException e) {
            Throwable c = e;
            while (c != null) {
                if (c instanceof OutOfFuel f) throw new TemplateException(f.getMessage());
                c = c.getCause();
            }
            throw new TemplateException(String.valueOf(e.getMessage()));
        }
        for (TemplateError err : result.getErrors()) {
            if (err.getSeverity() == TemplateError.ErrorType.FATAL) throw new TemplateException(describe(err, err.getMessage(), name));
        }
        return result.getOutput();
    }

    private static Path rootOf(Path dir) {
        if (dir == null) return null;
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            return dir.toAbsolutePath().normalize();
        }
    }

    private static String lineSuffix(String name, int line) {
        return " (" + (name == null ? "<string>" : name) + ", line " + line + ")";
    }

    private static String describe(TemplateError err, String message, String name) {
        String msg = message == null ? "template error" : message;
        Throwable ex = err == null ? null : err.getException();
        while (ex != null) {
            if (ex instanceof OutOfFuel) return ex.getMessage();
            ex = ex.getCause();
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("too long to run")) return "template too long to run (endless loop?)";
        if (lower.contains("unknown token") || lower.contains("undefined") || err != null && err.getReason() == TemplateError.ErrorReason.UNKNOWN) {
            msg = "undefined value (missing from the data? use `| default(...)` for optional fields): " + msg;
        } else if (lower.contains("template not found") || lower.contains("resourcenotfound") || err != null && err.getReason() == TemplateError.ErrorReason.MISSING) {
            return msg.contains("template not found") ? msg.substring(msg.indexOf("template not found")) : "template not found: " + msg;
        }
        if (err != null && err.getLineno() > 0) msg += lineSuffix(name, err.getLineno());
        return msg;
    }

    // ---- filters for business documents

    static String localeOf(JinjavaInterpreter interpreter, Map<String, Object> kwargs) {
        Object explicit = kwargs == null ? null : kwargs.get("locale");
        if (explicit != null) return explicit.toString();
        Object l = interpreter.getContext().get("_locale");
        return l == null ? "fr" : l.toString();
    }

    static double asNumber(Object v) {
        if (v == null) throw new InvalidValue("a filter got an undefined value");
        if (v instanceof Number n) return n.doubleValue();
        String s = v.toString().trim().replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new InvalidValue("not a number: \"" + v + "\"");
        }
    }

    static final class InvalidValue extends RuntimeException {
        InvalidValue(String m) {
            super(m);
        }
    }

    /**
     * Group the digits of {@code n} with {@code decimals} decimals, in the locale's style:
     * fr {@code 1 234,50}, de {@code 1.234,50}, en {@code 1,234.50}, ch {@code 1’234.50}.
     */
    public static String formatNumber(double n, int decimals, String locale) {
        String lang = locale.split("[-_]")[0].toLowerCase(Locale.ROOT);
        String group, point;
        switch (lang) {
            case "en", "ja", "zh", "ko" -> {
                group = ",";
                point = ".";
            }
            case "de", "es", "it", "nl", "pt", "da", "id" -> {
                group = ".";
                point = ",";
            }
            default -> {
                if (locale.toLowerCase(Locale.ROOT).endsWith("ch")) {
                    group = "’";
                    point = ".";
                } else {
                    group = " "; // fr and most of Europe: narrow no-break space
                    point = ",";
                }
            }
        }
        int d = Math.max(0, Math.min(12, decimals));
        // half away from zero (2.5 -> 3, 2.675 -> 2.68), not to even: what an invoice expects
        BigDecimal abs = new BigDecimal(Math.abs(n) * (1.0 + 1e-12)).setScale(d, RoundingMode.HALF_UP);
        String rounded = abs.toPlainString();
        int dot = rounded.indexOf('.');
        String in = dot < 0 ? rounded : rounded.substring(0, dot), frac = dot < 0 ? "" : rounded.substring(dot + 1);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            if (i > 0 && (in.length() - i) % 3 == 0) grouped.append(group);
            grouped.append(in.charAt(i));
        }
        boolean nonZero = rounded.chars().anyMatch(c -> c >= '1' && c <= '9');
        String sign = n < 0 && nonZero ? "-" : "";
        return frac.isEmpty() ? sign + grouped : sign + grouped + point + frac;
    }

    private static Integer intArg(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return (int) Double.parseDouble(v.toString());
    }

    /** {@code {{ total | money }}} -> {@code 1 234,50 €}; {@code money("USD")}, {@code money("EUR", locale="en")}, {@code money(decimals=0)} */
    static final class Money implements AdvancedFilter {
        @Override
        public String getName() {
            return "money";
        }

        @Override
        public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
            String loc = localeOf(interpreter, kwargs);
            Integer decimals = intArg(kwargs.get("decimals"));
            double n = asNumber(var);
            String code = (args.length > 0 && args[0] != null ? args[0].toString() : "EUR").toUpperCase(Locale.ROOT);
            String symbol = switch (code) {
                case "EUR" -> "€";
                case "USD" -> "$";
                case "GBP" -> "£";
                case "JPY" -> "¥";
                case "CHF" -> "CHF";
                case "CAD" -> "$ CA";
                default -> code;
            };
            String amount = formatNumber(n, decimals != null ? decimals : code.equals("JPY") ? 0 : 2, loc);
            String lang = loc.split("[-_]")[0].toLowerCase(Locale.ROOT);
            if (lang.equals("en")) return amount.startsWith("-") ? "-" + symbol + amount.substring(1) : symbol + amount;
            return amount + " " + symbol;
        }
    }

    /** {@code {{ qty | number }}} -> {@code 1 234}; {@code number(2)} -> {@code 1 234,50} */
    static final class NumberFilter implements AdvancedFilter {
        @Override
        public String getName() {
            return "number";
        }

        @Override
        public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
            String loc = localeOf(interpreter, kwargs);
            Integer d = args.length > 0 ? intArg(args[0]) : intArg(kwargs.get("decimals"));
            return formatNumber(asNumber(var), d == null ? 0 : d, loc);
        }
    }

    private static final String[] MONTHS_FR = {"janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"};
    private static final String[] MONTHS_EN = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    private static final String[] DAYS_FR = {"lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"};
    private static final String[] DAYS_EN = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    /** Day of the week of a date, 0 = Monday (Sakamoto's method). */
    static int weekday(int y, int m, int d) {
        int[] t = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
        if (m < 3) y -= 1;
        int sundayBased = Math.floorMod(y + y / 4 - y / 100 + y / 400 + t[m - 1] + d, 7);
        return (sundayBased + 6) % 7;
    }

    /**
     * {@code {{ "2026-10-15" | date }}} -> {@code 15/10/2026} (fr) / {@code 10/15/2026} (en);
     * {@code date("%e %B %Y")} -> {@code 15 octobre 2026}. {@code %d %e %m %B %b %Y %y %A %H %M}.
     * Takes {@code YYYY-MM-DD}, optionally followed by {@code THH:MM[:SS]...} (ISO 8601).
     */
    static final class DateFilter implements AdvancedFilter {
        @Override
        public String getName() {
            return "date";
        }

        @Override
        public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
            String loc = localeOf(interpreter, kwargs);
            if (var == null) throw new InvalidValue("a filter got an undefined value");
            String text = var.toString();
            InvalidValue bad = new InvalidValue("not a date (YYYY-MM-DD expected): \"" + text + "\"");
            String[] dt = text.split("[T ]", 2);
            String[] ymd = dt[0].split("-");
            int y, m, d;
            try {
                y = Integer.parseInt(ymd[0]);
                m = Integer.parseInt(ymd[1]);
                d = Integer.parseInt(ymd[2].length() > 2 ? ymd[2].substring(0, 2) : ymd[2]);
            } catch (RuntimeException e) {
                throw bad;
            }
            if (m < 1 || m > 12 || d < 1 || d > 31) throw bad;
            int hh = 0, mm = 0;
            if (dt.length > 1) {
                String[] t = dt[1].split(":");
                try {
                    hh = Integer.parseInt(t[0].substring(0, Math.min(2, t[0].length())));
                    if (t.length > 1) mm = Integer.parseInt(t[1].substring(0, Math.min(2, t[1].length())));
                } catch (RuntimeException ignored) {
                    // no time
                }
            }
            boolean en = loc.toLowerCase(Locale.ROOT).startsWith("en");
            String format = args.length > 0 && args[0] != null ? args[0].toString() : en ? "%m/%d/%Y" : "%d/%m/%Y";
            String[] months = en ? MONTHS_EN : MONTHS_FR, days = en ? DAYS_EN : DAYS_FR;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c != '%') {
                    out.append(c);
                    continue;
                }
                if (++i >= format.length()) {
                    out.append('%');
                    break;
                }
                switch (format.charAt(i)) {
                    case 'd' -> out.append(String.format("%02d", d));
                    case 'e' -> out.append(d);
                    case 'm' -> out.append(String.format("%02d", m));
                    case 'B' -> out.append(months[m - 1]);
                    case 'b' -> out.append(months[m - 1], 0, 3);
                    case 'Y' -> out.append(y);
                    case 'y' -> out.append(String.format("%02d", Math.floorMod(y, 100)));
                    case 'A' -> out.append(days[weekday(y, m, d)]);
                    case 'H' -> out.append(String.format("%02d", hh));
                    case 'M' -> out.append(String.format("%02d", mm));
                    case '%' -> out.append('%');
                    default -> out.append('%').append(format.charAt(i));
                }
            }
            return out.toString();
        }
    }

    /** {@code {{ address | nl2br }}}: escaped text, line breaks as {@code <br>} */
    static final class Nl2br implements AdvancedFilter {
        @Override
        public String getName() {
            return "nl2br";
        }

        @Override
        public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
            if (var == null) throw new InvalidValue("a filter got an undefined value");
            String escaped = var.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            return new SafeString(escaped.replace("\r\n", "\n").replace("\n", "<br>\n"));
        }
    }
}
