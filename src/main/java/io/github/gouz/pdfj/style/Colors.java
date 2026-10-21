package io.github.gouz.pdfj.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CSS colors: names, {@code #hex}, {@code rgb()} / {@code rgba()}, {@code hsl()} / {@code hsla()}, {@code hwb()}. */
public final class Colors {
    private Colors() {}

    private static final Map<String, Integer> NAMES = new HashMap<>();

    static {
        String table = """
            aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4
            black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0
            chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff
            darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9
            darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000
            darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f
            darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969
            dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc
            ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080
            honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa
            lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff
            lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1
            lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 lightslategrey 778899
            lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000
            mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371
            mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585
            midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080
            oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa
            palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f
            pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f
            royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee
            sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa
            springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0
            violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32
            """;
        String[] w = table.trim().split("\\s+");
        for (int i = 0; i + 1 < w.length; i += 2) NAMES.put(w[i], Integer.parseInt(w[i + 1], 16));
    }

    /** A color, or null if the value is not one. */
    public static Rgba parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        if (v.equals("transparent")) return new Rgba(0, 0, 0, 0);
        Integer named = NAMES.get(v);
        if (named != null) return new Rgba(named >> 16 & 255, named >> 8 & 255, named & 255, 255);
        if (v.startsWith("#")) return hex(v.substring(1));
        int open = v.indexOf('(');
        if (open < 0 || !v.endsWith(")")) return null;
        String fn = v.substring(0, open).trim();
        List<String> args = args(v.substring(open + 1, v.length() - 1));
        if (args == null) return null;
        try {
            return switch (fn) {
                case "rgb", "rgba" -> rgb(args);
                case "hsl", "hsla" -> hsl(args);
                case "hwb" -> hwb(args);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Rgba hex(String h) {
        for (int i = 0; i < h.length(); i++) if (Character.digit(h.charAt(i), 16) < 0) return null;
        return switch (h.length()) {
            case 3, 4 -> {
                int[] c = new int[4];
                c[3] = 255;
                for (int i = 0; i < h.length(); i++) c[i] = Character.digit(h.charAt(i), 16) * 17;
                yield new Rgba(c[0], c[1], c[2], c[3]);
            }
            case 6, 8 -> {
                int[] c = new int[4];
                c[3] = 255;
                for (int i = 0; i < h.length() / 2; i++) c[i] = Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
                yield new Rgba(c[0], c[1], c[2], c[3]);
            }
            default -> null;
        };
    }

    /** `1, 2, 3`, `1 2 3 / 50%`, `1,2,3,.5` -> [1, 2, 3, (alpha)] */
    private static List<String> args(String inner) {
        List<String> out = new ArrayList<>();
        String alpha = null;
        int slash = inner.indexOf('/');
        if (slash >= 0) {
            alpha = inner.substring(slash + 1).trim();
            inner = inner.substring(0, slash);
        }
        for (String p : inner.split("[,\\s]+")) if (!p.isEmpty()) out.add(p);
        if (alpha != null) out.add(alpha);
        return out.size() >= 3 && out.size() <= 4 ? out : null;
    }

    private static double num(String s, double pctScale) {
        if (s.endsWith("%")) return Double.parseDouble(s.substring(0, s.length() - 1)) * pctScale / 100.0;
        return Double.parseDouble(s);
    }

    private static int alpha(List<String> a) {
        if (a.size() < 4) return 255;
        return clamp(num(a.get(3), 1.0) * 255.0);
    }

    private static int clamp(double v) {
        return (int) Math.round(Math.max(0, Math.min(255, v)));
    }

    private static Rgba rgb(List<String> a) {
        return new Rgba(clamp(num(a.get(0), 255)), clamp(num(a.get(1), 255)), clamp(num(a.get(2), 255)), alpha(a));
    }

    private static double hue(String s) {
        double h;
        if (s.endsWith("deg")) h = Double.parseDouble(s.substring(0, s.length() - 3));
        else if (s.endsWith("turn")) h = Double.parseDouble(s.substring(0, s.length() - 4)) * 360;
        else if (s.endsWith("rad")) h = Math.toDegrees(Double.parseDouble(s.substring(0, s.length() - 3)));
        else h = Double.parseDouble(s);
        return ((h % 360) + 360) % 360;
    }

    private static Rgba hsl(List<String> a) {
        double h = hue(a.get(0)), s = num(a.get(1), 1.0), l = num(a.get(2), 1.0);
        if (!a.get(1).endsWith("%")) s /= 100.0;
        if (!a.get(2).endsWith("%")) l /= 100.0;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double[] rgb = sector(h, c, x);
        return new Rgba(clamp((rgb[0] + m) * 255), clamp((rgb[1] + m) * 255), clamp((rgb[2] + m) * 255), alpha(a));
    }

    private static Rgba hwb(List<String> a) {
        double h = hue(a.get(0)), w = num(a.get(1), 1.0), bl = num(a.get(2), 1.0);
        if (w + bl >= 1) {
            int g = clamp(w / (w + bl) * 255);
            return new Rgba(g, g, g, alpha(a));
        }
        double[] rgb = sector(h, 1, 1 - Math.abs((h / 60) % 2 - 1));
        double k = 1 - w - bl;
        return new Rgba(clamp((rgb[0] * k + w) * 255), clamp((rgb[1] * k + w) * 255), clamp((rgb[2] * k + w) * 255), alpha(a));
    }

    private static double[] sector(double h, double c, double x) {
        if (h < 60) return new double[] {c, x, 0};
        if (h < 120) return new double[] {x, c, 0};
        if (h < 180) return new double[] {0, c, x};
        if (h < 240) return new double[] {0, x, c};
        if (h < 300) return new double[] {x, 0, c};
        return new double[] {c, 0, x};
    }
}
