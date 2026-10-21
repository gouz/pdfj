package io.github.gouz.pdfj.text;

import java.util.HashMap;
import java.util.Map;

/**
 * Arabic contextual shaping with the Unicode presentation forms (isolated / final / initial / medial,
 * and the mandatory lam-alef ligatures). Fonts like DejaVu Sans have these glyphs.
 */
final class Arabic {
    private Arabic() {}

    /** joining types */
    private static final int U = 0, R = 1, D = 2, C = 3, T = 4;

    /** base letter -> {isolated, final, initial, medial} (0 when the form does not exist) */
    private static final Map<Integer, int[]> FORMS = new HashMap<>();

    private static void r(int base, int iso) {
        FORMS.put(base, new int[] {iso, iso + 1, 0, 0});
    }

    private static void d(int base, int iso) {
        FORMS.put(base, new int[] {iso, iso + 1, iso + 2, iso + 3});
    }

    static {
        FORMS.put(0x0621, new int[] {0xFE80, 0, 0, 0});
        r(0x0622, 0xFE81);
        r(0x0623, 0xFE83);
        r(0x0624, 0xFE85);
        r(0x0625, 0xFE87);
        d(0x0626, 0xFE89);
        r(0x0627, 0xFE8D);
        d(0x0628, 0xFE8F);
        r(0x0629, 0xFE93);
        int[] dual = {0x062A, 0xFE95, 0x062B, 0xFE99, 0x062C, 0xFE9D, 0x062D, 0xFEA1, 0x062E, 0xFEA5};
        for (int i = 0; i < dual.length; i += 2) d(dual[i], dual[i + 1]);
        r(0x062F, 0xFEA9);
        r(0x0630, 0xFEAB);
        r(0x0631, 0xFEAD);
        r(0x0632, 0xFEAF);
        int[] dual2 = {0x0633, 0xFEB1, 0x0634, 0xFEB5, 0x0635, 0xFEB9, 0x0636, 0xFEBD, 0x0637, 0xFEC1, 0x0638, 0xFEC5, 0x0639, 0xFEC9,
            0x063A, 0xFECD, 0x0641, 0xFED1, 0x0642, 0xFED5, 0x0643, 0xFED9, 0x0644, 0xFEDD, 0x0645, 0xFEE1, 0x0646, 0xFEE5, 0x0647, 0xFEE9};
        for (int i = 0; i < dual2.length; i += 2) d(dual2[i], dual2[i + 1]);
        r(0x0648, 0xFEED);
        r(0x0649, 0xFEEF);
        d(0x064A, 0xFEF1);
        // Persian / Urdu letters (presentation forms A)
        r(0x0671, 0xFB50);
        d(0x067E, 0xFB56);
        d(0x0686, 0xFB7A);
        r(0x0698, 0xFB8A);
        d(0x06A9, 0xFB8E);
        d(0x06AF, 0xFB92);
        d(0x06CC, 0xFBFC);
    }

    private static int joining(int c) {
        if (c == 0x0640) return C;
        if (c >= 0x064B && c <= 0x065F || c == 0x0670 || c >= 0x06D6 && c <= 0x06ED) return T;
        int[] f = FORMS.get(c);
        if (f == null) return U;
        return f[2] != 0 ? D : (f[1] != 0 ? R : U);
    }

    static boolean isArabic(int c) {
        return c >= 0x0600 && c <= 0x06FF;
    }

    /**
     * Shaped code points, in place: {@code out[i]} is the presentation form of {@code cp[i]}
     * (or cp[i] itself), {@code skip[i]} marks the alef consumed by a lam-alef ligature.
     */
    static void shape(int[] cp, int[] out, boolean[] skip) {
        int n = cp.length;
        for (int i = 0; i < n; i++) {
            out[i] = cp[i];
            if (!isArabic(cp[i])) continue;
            int t = joining(cp[i]);
            if (t == U || t == T || t == C || skip[i]) continue;
            int prev = i - 1;
            while (prev >= 0 && joining(cp[prev]) == T) prev--;
            int next = i + 1;
            while (next < n && joining(cp[next]) == T) next++;
            int pt = prev >= 0 && !skip[prev] ? joining(cp[prev]) : U;
            int nt = next < n ? joining(cp[next]) : U;
            boolean joinsPrev = (pt == D || pt == C) && (t == D || t == R);
            // lam-alef: the ligature replaces both letters
            if (cp[i] == 0x0644 && next < n) {
                int lig = switch (cp[next]) {
                    case 0x0622 -> 0xFEF5;
                    case 0x0623 -> 0xFEF7;
                    case 0x0625 -> 0xFEF9;
                    case 0x0627 -> 0xFEFB;
                    default -> 0;
                };
                if (lig != 0) {
                    out[i] = joinsPrev ? lig + 1 : lig;
                    skip[next] = true;
                    continue;
                }
            }
            boolean joinsNext = (nt == D || nt == R || nt == C) && (t == D);
            int[] f = FORMS.get(cp[i]);
            int form = joinsPrev && joinsNext ? 3 : joinsPrev ? 1 : joinsNext ? 2 : 0;
            int pres = f[form];
            if (pres == 0) pres = f[joinsPrev ? 1 : 0];
            if (pres != 0) out[i] = pres;
        }
    }
}
