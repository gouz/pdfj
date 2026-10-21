package io.github.gouz.pdfj.render;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encoded images, shared across documents: the same logo in every invoice is compressed once.
 * An entry is the image's stream (already filtered) and its dictionary, and its soft mask's; bounded
 * by size, least recently used dropped first.
 */
final class ImageCache {
    private ImageCache() {}

    private record Stream(Map<COSName, COSBase> dict, byte[] data) {}

    private record Entry(Stream image, Stream mask) {
        long size() {
            return image.data.length + (mask == null ? 0 : mask.data.length) + 512;
        }
    }

    private static final long MAX = 64L * 1024 * 1024;
    private static long used;
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);

    static String key(byte[] file) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(file);
            return java.util.HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the cached image as a new XObject of {@code doc}, or null */
    static PDImageXObject get(String key, PDDocument doc) {
        Entry e;
        synchronized (CACHE) {
            e = CACHE.get(key);
        }
        if (e == null) return null;
        try {
            COSStream image = write(doc, e.image);
            if (e.mask != null) image.setItem(COSName.SMASK, write(doc, e.mask));
            return new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(image), null);
        } catch (IOException ex) {
            return null;
        }
    }

    /** remember an image just made (only plain ones: names, numbers and arrays of them in its dictionary) */
    static void put(String key, PDImageXObject img) {
        try {
            COSStream s = img.getCOSObject();
            Stream image = read(s);
            if (image == null) return;
            Stream mask = null;
            COSBase m = s.getDictionaryObject(COSName.SMASK);
            if (m instanceof COSStream ms) {
                mask = read(ms);
                if (mask == null) return;
            } else if (m != null) {
                return;
            }
            Entry e = new Entry(image, mask);
            if (e.size() > MAX / 8) return;
            synchronized (CACHE) {
                if (CACHE.containsKey(key)) return;
                CACHE.put(key, e);
                used += e.size();
                var it = CACHE.entrySet().iterator();
                while (used > MAX && it.hasNext()) {
                    used -= it.next().getValue().size();
                    it.remove();
                }
            }
        } catch (IOException ex) {
            // not cached
        }
    }

    private static Stream read(COSStream s) throws IOException {
        Map<COSName, COSBase> dict = new HashMap<>();
        for (Map.Entry<COSName, COSBase> e : s.entrySet()) {
            if (e.getKey().equals(COSName.LENGTH) || e.getKey().equals(COSName.SMASK)) continue;
            if (!plain(e.getValue())) return null;
            dict.put(e.getKey(), e.getValue());
        }
        try (InputStream in = s.createRawInputStream()) {
            return new Stream(dict, in.readAllBytes());
        }
    }

    private static boolean plain(COSBase v) {
        if (v instanceof org.apache.pdfbox.cos.COSObject o) v = o.getObject();
        if (v instanceof COSName || v instanceof org.apache.pdfbox.cos.COSNumber || v instanceof org.apache.pdfbox.cos.COSBoolean) return true;
        if (v instanceof org.apache.pdfbox.cos.COSArray a) {
            for (COSBase x : a) if (!plain(x)) return false;
            return true;
        }
        if (v instanceof COSDictionary d && !(v instanceof COSStream)) {
            for (COSBase x : d.getValues()) if (!plain(x)) return false;
            return true;
        }
        return false;
    }

    private static COSStream write(PDDocument doc, Stream s) throws IOException {
        COSStream out = doc.getDocument().createCOSStream();
        for (Map.Entry<COSName, COSBase> e : s.dict.entrySet()) out.setItem(e.getKey(), copy(e.getValue()));
        try (OutputStream o = out.createRawOutputStream()) {
            o.write(s.data);
        }
        return out;
    }

    /** a copy of a plain value: the arrays and dictionaries of one document are not shared with another */
    private static COSBase copy(COSBase v) {
        if (v instanceof org.apache.pdfbox.cos.COSObject o) v = o.getObject();
        if (v instanceof org.apache.pdfbox.cos.COSArray a) {
            org.apache.pdfbox.cos.COSArray c = new org.apache.pdfbox.cos.COSArray();
            for (COSBase x : a) c.add(copy(x));
            return c;
        }
        if (v instanceof COSDictionary d) {
            COSDictionary c = new COSDictionary();
            for (Map.Entry<COSName, COSBase> e : d.entrySet()) c.setItem(e.getKey(), copy(e.getValue()));
            return c;
        }
        return v;
    }
}
