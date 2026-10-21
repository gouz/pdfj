package io.github.gouz.pdfj.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A laid out document (or header / footer band): its root box, links and text fills. */
public final class Tree {
    public Box root;
    /** hrefs of the links (Brush.link - 1) */
    public final List<String> links;
    /** elements with {@code background-clip: text} (Brush.textFill - 1) */
    public final List<Box> textFills;

    Tree(Box root, List<String> links, List<Box> textFills) {
        this.root = root;
        this.links = links;
        this.textFills = textFills;
    }

    /** A section: consecutive top-level boxes with the same {@code page} name, under a copy of the root. */
    public record Section(Box root, String name) {}

    /**
     * Split the root into sections of consecutive top-level boxes with the same {@code page} name, each
     * its own root (a copy of the body box) to lay out at its page's width. One section without a
     * name: the root as is.
     */
    public List<Section> sections() {
        List<String> names = new ArrayList<>();
        List<List<Box>> groups = new ArrayList<>();
        for (Box c : root.children) {
            String name = c.page;
            if (!groups.isEmpty() && Objects.equals(names.get(names.size() - 1), name)) {
                groups.get(groups.size() - 1).add(c);
            } else {
                names.add(name);
                List<Box> g = new ArrayList<>();
                g.add(c);
                groups.add(g);
            }
        }
        if (groups.size() <= 1 && (names.isEmpty() || names.get(0) == null)) {
            return List.of(new Section(root, names.isEmpty() ? null : names.get(0)));
        }
        List<Section> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Box r;
            if (i == 0) {
                r = root;
                r.setChildren(groups.get(0));
            } else {
                r = root.copyStyle();
                r.deco = root.deco == null ? null : root.deco.copy();
                r.setChildren(groups.get(i));
            }
            out.add(new Section(r, names.get(i)));
        }
        return out;
    }

    /** Lay a section root out at {@code width}. */
    public static void layout(Box root, double width) {
        root.size = new io.github.gouz.pdfj.style.Len[] {io.github.gouz.pdfj.style.Len.px(width), root.size[1]};
        Layout.layoutRoot(root, width);
    }
}
