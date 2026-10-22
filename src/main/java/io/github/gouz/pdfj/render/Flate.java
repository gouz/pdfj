package io.github.gouz.pdfj.render;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Flate streams written straight into a document: the same bytes as PDFBox's {@code FlateFilter}
 * (zlib, default level), without its copies of the data on the way in.
 */
final class Flate {
    private Flate() {}

    static byte[] deflate(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
        try {
            write(out, data, 0, data.length);
        } catch (IOException e) {
            throw new IllegalStateException(e); // not thrown by a ByteArrayOutputStream
        }
        return out.toByteArray();
    }

    /** a stream of {@code doc} holding data already Flate-compressed */
    static COSStream compressed(PDDocument doc, byte[] data) throws IOException {
        COSStream s = stream(doc);
        try (OutputStream o = s.createRawOutputStream()) {
            o.write(data);
        }
        return s;
    }

    /** a stream of {@code doc} holding {@code data[0, len)}, Flate-compressed */
    static COSStream compress(PDDocument doc, byte[] data, int len) throws IOException {
        COSStream s = stream(doc);
        try (OutputStream o = s.createRawOutputStream()) {
            write(o, data, 0, len);
        }
        return s;
    }

    private static COSStream stream(PDDocument doc) {
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        return s;
    }

    private static void write(OutputStream out, byte[] data, int off, int len) throws IOException {
        Deflater d = new Deflater();
        try {
            DeflaterOutputStream z = new DeflaterOutputStream(out, d, 8192);
            z.write(data, off, len);
            z.finish();
        } finally {
            d.end();
        }
    }
}
