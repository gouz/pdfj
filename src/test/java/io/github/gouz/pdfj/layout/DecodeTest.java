package io.github.gouz.pdfj.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecodeTest {
    @Test
    void svgReferencesItsOwnElements() {
        assertNotNull(Decode.svg("<svg width='48' height='7' viewBox='0 0 48 7' xmlns='http://www.w3.org/2000/svg'>"
            + "<clipPath id='a'><path d='m0 0h48v7h-48z'/></clipPath><g clip-path='url(#a)'><rect width='48' height='7'/></g></svg>"));
        assertNotNull(Decode.svg("<svg width='10' height='10' xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'>"
            + "<defs><rect id='r' width='10' height='10'/></defs><use xlink:href='#r'/></svg>"));
    }

    @Test
    void svgLoadsNothingElse() {
        assertNull(Decode.svg("<svg width='10' height='10' xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'>"
            + "<use xlink:href='file:///etc/passwd#x'/></svg>"));
    }
}
