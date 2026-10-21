package io.github.gouz.pdfj.layout;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.HashMap;
import java.util.Map;

/** An inline {@code <svg>} of the HTML as an SVG document (XML), names back in their SVG case. */
final class Svg {
    private Svg() {}

    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        for (String n : new String[] {
            // elements
            "altGlyph", "altGlyphDef", "altGlyphItem", "animateColor", "animateMotion", "animateTransform", "clipPath", "feBlend",
            "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap",
            "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage",
            "feMerge", "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight", "feTile",
            "feTurbulence", "foreignObject", "glyphRef", "linearGradient", "radialGradient", "textPath",
            // attributes
            "attributeName", "attributeType", "baseFrequency", "baseProfile", "calcMode", "clipPathUnits", "diffuseConstant",
            "edgeMode", "filterUnits", "glyphRef", "gradientTransform", "gradientUnits", "kernelMatrix", "kernelUnitLength",
            "keyPoints", "keySplines", "keyTimes", "lengthAdjust", "limitingConeAngle", "markerHeight", "markerUnits", "markerWidth",
            "maskContentUnits", "maskUnits", "numOctaves", "pathLength", "patternContentUnits", "patternTransform", "patternUnits",
            "pointsAtX", "pointsAtY", "pointsAtZ", "preserveAlpha", "preserveAspectRatio", "primitiveUnits", "refX", "refY",
            "repeatCount", "repeatDur", "requiredExtensions", "requiredFeatures", "specularConstant", "specularExponent",
            "spreadMethod", "startOffset", "stdDeviation", "stitchTiles", "surfaceScale", "systemLanguage", "tableValues",
            "targetX", "targetY", "textLength", "viewBox", "viewTarget", "xChannelSelector", "yChannelSelector", "zoomAndPan",
        }) {
            NAMES.put(n.toLowerCase(java.util.Locale.ROOT), n);
        }
    }

    private static String name(String n) {
        return NAMES.getOrDefault(n, n);
    }

    static String markup(Element svg) {
        StringBuilder sb = new StringBuilder();
        write(svg, sb, 0);
        return sb.toString();
    }

    private static void write(Node node, StringBuilder sb, int depth) {
        if (depth > 200) return;
        if (node instanceof TextNode t) {
            escape(t.getWholeText(), sb, false);
        } else if (node instanceof DataNode d) {
            escape(d.getWholeData(), sb, false);
        } else if (node instanceof Element e) {
            String tag = name(e.normalName());
            sb.append('<').append(tag);
            for (Attribute a : e.attributes()) {
                String key = a.getKey();
                if (key.startsWith("on")) continue; // no scripts
                sb.append(' ').append(name(key)).append("=\"");
                escape(a.getValue(), sb, true);
                sb.append('"');
            }
            if (e.childNodeSize() == 0) {
                sb.append("/>");
                return;
            }
            sb.append('>');
            for (Node c : e.childNodes()) write(c, sb, depth + 1);
            sb.append("</").append(tag).append('>');
        }
    }

    private static void escape(String s, StringBuilder sb, boolean attr) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append(attr ? "&quot;" : "\"");
                default -> {
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
                    sb.append(c);
                }
            }
        }
    }
}
