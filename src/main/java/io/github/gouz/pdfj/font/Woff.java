package io.github.gouz.pdfj.font;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Font file bytes as sfnt: TrueType / OpenType as is, WOFF / WOFF2 decompressed. */
public final class Woff {
    private Woff() {}

    /** sfnt data (TrueType / OpenType / collection)? */
    public static boolean isSfnt(byte[] d) {
        if (d == null || d.length < 4) return false;
        int tag = (d[0] & 255) << 24 | (d[1] & 255) << 16 | (d[2] & 255) << 8 | (d[3] & 255);
        return tag == 0x00010000 || tag == tag("OTTO") || tag == tag("true") || tag == tag("ttcf");
    }

    /** sfnt bytes, or null if the data is neither sfnt, WOFF nor WOFF2 (or is broken). */
    public static byte[] toSfnt(byte[] data) {
        if (data == null || data.length < 4) return null;
        byte[] out;
        try {
            String sig = new String(data, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            out = switch (sig) {
                case "wOFF" -> woff1(data);
                case "wOF2" -> woff2(data);
                default -> data;
            };
        } catch (IOException | RuntimeException | DataFormatException e) {
            return null;
        }
        return isSfnt(out) ? out : null;
    }

    static int tag(String s) {
        return s.charAt(0) << 24 | s.charAt(1) << 16 | s.charAt(2) << 8 | s.charAt(3);
    }

    private record Table(int tag, byte[] data) {}

    /** An sfnt file from its tables (sorted by tag, 4-byte aligned, checksums computed). */
    private static byte[] sfnt(int flavor, List<Table> tables) {
        tables.sort(Comparator.comparingLong(t -> t.tag & 0xffffffffL));
        int n = tables.size();
        int entrySelector = 0;
        while ((1 << (entrySelector + 1)) <= n) entrySelector++;
        int searchRange = (1 << entrySelector) * 16;
        int size = 12 + 16 * n;
        for (Table t : tables) size += (t.data.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(flavor).putShort((short) n).putShort((short) searchRange).putShort((short) entrySelector).putShort((short) (n * 16 - searchRange));
        int offset = 12 + 16 * n;
        for (Table t : tables) {
            b.putInt(t.tag).putInt(checksum(t.data)).putInt(offset).putInt(t.data.length);
            offset += (t.data.length + 3) & ~3;
        }
        for (Table t : tables) {
            b.put(t.data);
            for (int i = t.data.length; i % 4 != 0; i++) b.put((byte) 0);
        }
        return b.array();
    }

    private static int checksum(byte[] d) {
        int sum = 0;
        for (int i = 0; i < d.length; i += 4) {
            int v = 0;
            for (int k = 0; k < 4; k++) v = v << 8 | (i + k < d.length ? d[i + k] & 255 : 0);
            sum += v;
        }
        return sum;
    }

    /** WOFF 1.0: zlib per table. */
    static byte[] woff1(byte[] data) throws DataFormatException {
        ByteBuffer b = ByteBuffer.wrap(data);
        int flavor = b.getInt(4);
        int numTables = b.getShort(12) & 0xffff;
        List<Table> tables = new ArrayList<>();
        for (int i = 0; i < numTables; i++) {
            int e = 44 + 20 * i;
            int tag = b.getInt(e), offset = b.getInt(e + 4), compLength = b.getInt(e + 8), origLength = b.getInt(e + 12);
            if (origLength < 0 || origLength > 64 << 20) throw new DataFormatException("table too large");
            byte[] raw = new byte[origLength];
            if (compLength == origLength) {
                System.arraycopy(data, offset, raw, 0, origLength);
            } else {
                Inflater inf = new Inflater();
                inf.setInput(data, offset, compLength);
                int got = inf.inflate(raw);
                inf.end();
                if (got != origLength) throw new DataFormatException("bad table");
            }
            tables.add(new Table(tag, raw));
        }
        return sfnt(flavor, tables);
    }

    private static final String[] KNOWN = {
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT",
        "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH",
        "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar",
        "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    };

    private static int base128(ByteBuffer b) throws IOException {
        long acc = 0;
        for (int i = 0; i < 5; i++) {
            int x = b.get() & 255;
            if (i == 0 && x == 0x80) throw new IOException("leading zero");
            acc = acc << 7 | (x & 0x7f);
            if (acc > 0xffffffffL) throw new IOException("overflow");
            if ((x & 0x80) == 0) return (int) acc;
        }
        throw new IOException("base128 too long");
    }

    private static int u255(ByteBuffer b) {
        int code = b.get() & 255;
        return switch (code) {
            case 253 -> b.getShort() & 0xffff;
            case 255 -> (b.get() & 255) + 253;
            case 254 -> (b.get() & 255) + 506;
            default -> code;
        };
    }

    private record Entry(int tag, int transform, int origLength, int length) {}

    /** WOFF 2.0: one brotli stream, glyf / loca and hmtx possibly transformed. */
    static byte[] woff2(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data);
        int flavor = b.getInt(4);
        if (flavor == tag("ttcf")) throw new IOException("WOFF2 collections are not supported");
        int numTables = b.getShort(12) & 0xffff;
        int compressedSize = b.getInt(20);
        b.position(48);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < numTables; i++) {
            int flags = b.get() & 255;
            int idx = flags & 0x3f;
            int tag = idx == 63 ? b.getInt() : tag(KNOWN[idx]);
            int version = flags >> 6 & 3;
            int orig = base128(b);
            boolean glyfLoca = tag == tag("glyf") || tag == tag("loca");
            boolean transformed = glyfLoca ? version == 0 : version != 0;
            int length = transformed ? base128(b) : orig;
            entries.add(new Entry(tag, transformed ? (glyfLoca ? 0 : version) : -1, orig, length));
        }
        byte[] stream;
        try (BrotliInputStream in = new BrotliInputStream(new ByteArrayInputStream(data, b.position(), compressedSize))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            stream = out.toByteArray();
        }
        int pos = 0;
        java.util.Map<Integer, byte[]> raw = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Entry> byTag = new java.util.HashMap<>();
        for (Entry e : entries) {
            byte[] t = new byte[e.length];
            System.arraycopy(stream, pos, t, 0, e.length);
            pos += e.length;
            raw.put(e.tag, t);
            byTag.put(e.tag, e);
        }
        List<Table> tables = new ArrayList<>();
        int glyf = tag("glyf"), loca = tag("loca"), hmtx = tag("hmtx");
        short[] xMins = null;
        Entry glyfEntry = byTag.get(glyf);
        if (glyfEntry != null && glyfEntry.transform == 0) {
            Glyf g = reconstructGlyf(raw.get(glyf));
            tables.add(new Table(glyf, g.glyf));
            tables.add(new Table(loca, g.loca));
            xMins = g.xMins;
        } else if (glyfEntry != null) {
            tables.add(new Table(glyf, raw.get(glyf)));
            if (raw.containsKey(loca)) tables.add(new Table(loca, raw.get(loca)));
        }
        for (Entry e : entries) {
            if (e.tag == glyf || e.tag == loca) continue;
            if (e.tag == hmtx && e.transform == 1) {
                tables.add(new Table(hmtx, reconstructHmtx(raw.get(hmtx), raw.get(tag("hhea")), raw.get(tag("maxp")), xMins)));
            } else {
                tables.add(new Table(e.tag, raw.get(e.tag)));
            }
        }
        return sfnt(flavor, tables);
    }

    private record Glyf(byte[] glyf, byte[] loca, short[] xMins) {}

    private static ByteBuffer slice(byte[] d, int off, int len) {
        return ByteBuffer.wrap(d, off, len).slice();
    }

    private static Glyf reconstructGlyf(byte[] t) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(t);
        h.getShort(); // version
        int options = h.getShort() & 0xffff;
        int numGlyphs = h.getShort() & 0xffff;
        int indexFormat = h.getShort() & 0xffff;
        int[] sizes = new int[7];
        for (int i = 0; i < 7; i++) sizes[i] = h.getInt();
        int off = h.position();
        ByteBuffer[] s = new ByteBuffer[7];
        for (int i = 0; i < 7; i++) {
            s[i] = slice(t, off, sizes[i]);
            off += sizes[i];
        }
        ByteBuffer nContours = s[0], nPoints = s[1], flags = s[2], glyphs = s[3], composite = s[4], bbox = s[5], instructions = s[6];
        int bitmapLen = ((numGlyphs + 31) >> 5) << 2;
        byte[] bboxBitmap = new byte[bitmapLen];
        bbox.get(bboxBitmap);
        boolean overlapBitmap = (options & 1) != 0;
        byte[] overlap = null;
        if (overlapBitmap && off + ((numGlyphs + 7) >> 3) <= t.length) {
            overlap = new byte[(numGlyphs + 7) >> 3];
            System.arraycopy(t, off, overlap, 0, overlap.length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[numGlyphs + 1];
        short[] xMins = new short[numGlyphs];
        for (int g = 0; g < numGlyphs; g++) {
            offsets[g] = out.size();
            int nc = nContours.getShort();
            boolean hasBbox = (bboxBitmap[g >> 3] & (0x80 >> (g & 7))) != 0;
            ByteBuffer glyph = ByteBuffer.allocate(0);
            if (nc == 0) {
                if (hasBbox) throw new IOException("empty glyph with a bbox");
            } else if (nc > 0) {
                int[] endPts = new int[nc];
                int total = 0;
                for (int c = 0; c < nc; c++) {
                    total += u255(nPoints);
                    endPts[c] = total - 1;
                }
                int[] xs = new int[total], ys = new int[total];
                boolean[] on = new boolean[total];
                int x = 0, y = 0;
                for (int p = 0; p < total; p++) {
                    int flag = flags.get() & 255;
                    on[p] = (flag >> 7) == 0;
                    flag &= 0x7f;
                    int dx, dy;
                    if (flag < 10) {
                        dx = 0;
                        dy = sign(flag, ((flag & 14) << 7) + (glyphs.get() & 255));
                    } else if (flag < 20) {
                        dx = sign(flag, (((flag - 10) & 14) << 7) + (glyphs.get() & 255));
                        dy = 0;
                    } else if (flag < 84) {
                        int b0 = flag - 20, b1 = glyphs.get() & 255;
                        dx = sign(flag, 1 + (b0 & 0x30) + (b1 >> 4));
                        dy = sign(flag >> 1, 1 + ((b0 & 0x0c) << 2) + (b1 & 0x0f));
                    } else if (flag < 120) {
                        int b0 = flag - 84, b1 = glyphs.get() & 255, b2 = glyphs.get() & 255;
                        dx = sign(flag, 1 + ((b0 / 12) << 8) + b1);
                        dy = sign(flag >> 1, 1 + (((b0 % 12) >> 2) << 8) + b2);
                    } else if (flag < 124) {
                        int b1 = glyphs.get() & 255, b2 = glyphs.get() & 255, b3 = glyphs.get() & 255;
                        dx = sign(flag, (b1 << 4) + (b2 >> 4));
                        dy = sign(flag >> 1, ((b2 & 0x0f) << 8) + b3);
                    } else {
                        int b1 = glyphs.get() & 255, b2 = glyphs.get() & 255, b3 = glyphs.get() & 255, b4 = glyphs.get() & 255;
                        dx = sign(flag, (b1 << 8) + b2);
                        dy = sign(flag >> 1, (b3 << 8) + b4);
                    }
                    x += dx;
                    y += dy;
                    xs[p] = x;
                    ys[p] = y;
                }
                int insLen = u255(glyphs);
                byte[] ins = new byte[insLen];
                instructions.get(ins);
                int xMin, yMin, xMax, yMax;
                if (hasBbox) {
                    xMin = bbox.getShort();
                    yMin = bbox.getShort();
                    xMax = bbox.getShort();
                    yMax = bbox.getShort();
                } else {
                    xMin = yMin = Integer.MAX_VALUE;
                    xMax = yMax = Integer.MIN_VALUE;
                    for (int p = 0; p < total; p++) {
                        xMin = Math.min(xMin, xs[p]);
                        yMin = Math.min(yMin, ys[p]);
                        xMax = Math.max(xMax, xs[p]);
                        yMax = Math.max(yMax, ys[p]);
                    }
                    if (total == 0) xMin = yMin = xMax = yMax = 0;
                }
                boolean overlapFirst = overlap != null && (overlap[g >> 3] & (0x80 >> (g & 7))) != 0;
                glyph = simpleGlyph(nc, endPts, xs, ys, on, ins, xMin, yMin, xMax, yMax, overlapFirst);
                xMins[g] = (short) xMin;
            } else {
                if (!hasBbox) throw new IOException("composite glyph without a bbox");
                int xMin = bbox.getShort(), yMin = bbox.getShort(), xMax = bbox.getShort(), yMax = bbox.getShort();
                ByteArrayOutputStream comp = new ByteArrayOutputStream();
                boolean more = true, haveInstructions = false;
                while (more) {
                    int f = composite.getShort() & 0xffff;
                    int glyphIndex = composite.getShort() & 0xffff;
                    int argBytes = (f & 0x0001) != 0 ? 4 : 2;
                    int xform = (f & 0x0008) != 0 ? 2 : (f & 0x0040) != 0 ? 4 : (f & 0x0080) != 0 ? 8 : 0;
                    byte[] rest = new byte[argBytes + xform];
                    composite.get(rest);
                    comp.write(f >> 8);
                    comp.write(f);
                    comp.write(glyphIndex >> 8);
                    comp.write(glyphIndex);
                    comp.write(rest, 0, rest.length);
                    more = (f & 0x0020) != 0;
                    haveInstructions |= (f & 0x0100) != 0;
                }
                byte[] ins = new byte[0];
                if (haveInstructions) {
                    ins = new byte[u255(glyphs)];
                    instructions.get(ins);
                }
                byte[] c = comp.toByteArray();
                glyph = ByteBuffer.allocate(10 + c.length + (haveInstructions ? 2 + ins.length : 0));
                glyph.putShort((short) -1).putShort((short) xMin).putShort((short) yMin).putShort((short) xMax).putShort((short) yMax).put(c);
                if (haveInstructions) glyph.putShort((short) ins.length).put(ins);
                xMins[g] = (short) xMin;
            }
            out.write(glyph.array(), 0, glyph.position());
            while (out.size() % 4 != 0) out.write(0);
        }
        offsets[numGlyphs] = out.size();
        ByteBuffer locaBuf = ByteBuffer.allocate((numGlyphs + 1) * (indexFormat == 0 ? 2 : 4));
        for (int o : offsets) {
            if (indexFormat == 0) locaBuf.putShort((short) (o / 2));
            else locaBuf.putInt(o);
        }
        return new Glyf(out.toByteArray(), locaBuf.array(), xMins);
    }

    private static int sign(int flag, int v) {
        return (flag & 1) != 0 ? v : -v;
    }

    /** A simple glyph in the glyf table's format (flags and coordinates compressed the usual way). */
    private static ByteBuffer simpleGlyph(int nc, int[] endPts, int[] xs, int[] ys, boolean[] on, byte[] ins, int xMin, int yMin, int xMax, int yMax, boolean overlap) {
        int n = xs.length;
        ByteArrayOutputStream fl = new ByteArrayOutputStream(), xb = new ByteArrayOutputStream(), yb = new ByteArrayOutputStream();
        int px = 0, py = 0;
        int lastFlag = -1, repeat = 0;
        List<Integer> flagList = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            int f = on[p] ? 1 : 0;
            if (p == 0 && overlap) f |= 0x40;
            int dx = xs[p] - px, dy = ys[p] - py;
            if (dx == 0) f |= 0x10;
            else if (dx > -256 && dx < 256) {
                f |= 0x02 | (dx > 0 ? 0x10 : 0);
                xb.write(Math.abs(dx));
            } else {
                xb.write(dx >> 8);
                xb.write(dx);
            }
            if (dy == 0) f |= 0x20;
            else if (dy > -256 && dy < 256) {
                f |= 0x04 | (dy > 0 ? 0x20 : 0);
                yb.write(Math.abs(dy));
            } else {
                yb.write(dy >> 8);
                yb.write(dy);
            }
            flagList.add(f);
            px = xs[p];
            py = ys[p];
        }
        for (int p = 0; p < flagList.size(); p++) {
            int f = flagList.get(p);
            if (f == lastFlag && repeat < 255) {
                repeat++;
                continue;
            }
            flushRepeat(fl, lastFlag, repeat);
            lastFlag = f;
            repeat = 0;
        }
        flushRepeat(fl, lastFlag, repeat);
        byte[] flb = fl.toByteArray(), xbb = xb.toByteArray(), ybb = yb.toByteArray();
        ByteBuffer g = ByteBuffer.allocate(10 + 2 * nc + 2 + ins.length + flb.length + xbb.length + ybb.length);
        g.putShort((short) nc).putShort((short) xMin).putShort((short) yMin).putShort((short) xMax).putShort((short) yMax);
        for (int e : endPts) g.putShort((short) e);
        g.putShort((short) ins.length).put(ins).put(flb).put(xbb).put(ybb);
        return g;
    }

    private static void flushRepeat(ByteArrayOutputStream fl, int flag, int repeat) {
        if (flag < 0) return;
        if (repeat == 0) {
            fl.write(flag);
        } else if (repeat == 1) {
            fl.write(flag);
            fl.write(flag);
        } else {
            fl.write(flag | 0x08);
            fl.write(repeat);
        }
    }

    private static byte[] reconstructHmtx(byte[] t, byte[] hhea, byte[] maxp, short[] xMins) throws IOException {
        if (hhea == null || maxp == null || xMins == null) throw new IOException("hmtx transform needs glyf, hhea and maxp");
        int numHMetrics = ByteBuffer.wrap(hhea).getShort(34) & 0xffff;
        int numGlyphs = ByteBuffer.wrap(maxp).getShort(4) & 0xffff;
        ByteBuffer b = ByteBuffer.wrap(t);
        int flags = b.get() & 255;
        int[] adv = new int[numHMetrics];
        for (int i = 0; i < numHMetrics; i++) adv[i] = b.getShort() & 0xffff;
        short[] lsb = new short[numGlyphs];
        for (int i = 0; i < numHMetrics; i++) lsb[i] = (flags & 1) != 0 ? xMins[i] : b.getShort();
        for (int i = numHMetrics; i < numGlyphs; i++) lsb[i] = (flags & 2) != 0 ? xMins[i] : b.getShort();
        ByteBuffer out = ByteBuffer.allocate(4 * numHMetrics + 2 * (numGlyphs - numHMetrics));
        for (int i = 0; i < numHMetrics; i++) out.putShort((short) adv[i]).putShort(lsb[i]);
        for (int i = numHMetrics; i < numGlyphs; i++) out.putShort(lsb[i]);
        return out.array();
    }
}
