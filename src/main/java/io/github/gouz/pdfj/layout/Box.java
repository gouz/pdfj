package io.github.gouz.pdfj.layout;

import io.github.gouz.pdfj.style.GridTemplate;
import io.github.gouz.pdfj.style.Len;
import io.github.gouz.pdfj.style.Style.Align;
import io.github.gouz.pdfj.style.Style.FlexDirection;
import io.github.gouz.pdfj.style.Style.FlexWrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A box of the layout tree: its layout style (the CSS box model, flex and grid properties), its children
 * or its leaf content (text or image), what is drawn for it, and its computed layout.
 */
public final class Box {
    public enum Display { BLOCK, FLEX, GRID, NONE }

    // layout style
    public Display display = Display.BLOCK;
    public boolean absolute;
    public Len[] inset = {Len.AUTO, Len.AUTO, Len.AUTO, Len.AUTO};
    public Len[] size = {Len.AUTO, Len.AUTO};
    public Len[] minSize = {Len.AUTO, Len.AUTO};
    public Len[] maxSize = {Len.AUTO, Len.AUTO};
    /** top, right, bottom, left */
    public Len[] margin = {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
    public Len[] padding = {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
    public double[] border = new double[4];
    public boolean borderBox;
    public FlexDirection flexDirection = FlexDirection.ROW;
    public FlexWrap flexWrap = FlexWrap.NO_WRAP;
    public double grow;
    public double shrink = 1;
    public Len basis = Len.AUTO;
    public Align justifyContent, alignContent, alignItems, alignSelf, justifyItems, justifySelf;
    public Len gapRow = Len.ZERO, gapColumn = Len.ZERO;
    public Double aspectRatio;
    public boolean rtl;
    public int order;
    public List<GridTemplate.Item> columns = List.of(), rows = List.of(), autoColumns, autoRows;
    public String autoFlow;
    public GridTemplate.Placement gridColumn = GridTemplate.Placement.AUTO, gridRow = GridTemplate.Placement.AUTO;
    /** an image: sized from its natural size */
    public boolean replaced;
    public boolean overflowHidden;
    /** floated (a pseudo-element box among blocks): shrinks to fit */
    public boolean floating;
    /** a table without a width: shrinks to fit its columns */
    public boolean shrinkToFit;

    // tree
    public final List<Box> children = new ArrayList<>();
    public Box parent;
    public TextLeaf text;
    public ImageLeaf image;
    public Deco deco;

    // tables
    /** header / footer cells, repeated on the pages the table spans */
    public List<Box> headCells, footCells;
    public boolean cell;
    /** auto columns of a table with a width, sized by their content before the first layout */
    public List<Integer> tableAutoColumns;
    /** a top-level box with a {@code page} name */
    public String page;
    /** an element with {@code background-clip: text}: the 1-based index of this box in the tree's text fills */
    public int textFill;

    // computed layout: border box relative to the parent's border box (inline boxes and floats: to their text leaf)
    public double x, y, w, h;
    public final double[] lpad = new double[4], lbor = new double[4], lmar = new double[4];

    /** measurements already made (the tree does not change once laid out) */
    Map<Layout.Key, Layout.Out> cache;

    public Box() {}

    public void add(Box child) {
        child.parent = this;
        children.add(child);
    }

    public void setChildren(List<Box> list) {
        children.clear();
        for (Box c : list) add(c);
    }

    public double contentWidth() {
        return Math.max(0, w - lpad[1] - lpad[3] - lbor[1] - lbor[3]);
    }

    public double contentHeight() {
        return Math.max(0, h - lpad[0] - lpad[2] - lbor[0] - lbor[2]);
    }

    void forget() {
        cache = null;
    }

    Map<Layout.Key, Layout.Out> cache() {
        if (cache == null) cache = new HashMap<>(4);
        return cache;
    }

    /** A copy of the layout style (not the children, content or decoration). */
    public Box copyStyle() {
        Box b = new Box();
        b.display = display;
        b.absolute = absolute;
        b.inset = inset.clone();
        b.size = size.clone();
        b.minSize = minSize.clone();
        b.maxSize = maxSize.clone();
        b.margin = margin.clone();
        b.padding = padding.clone();
        b.border = border.clone();
        b.borderBox = borderBox;
        b.flexDirection = flexDirection;
        b.flexWrap = flexWrap;
        b.grow = grow;
        b.shrink = shrink;
        b.basis = basis;
        b.justifyContent = justifyContent;
        b.alignContent = alignContent;
        b.alignItems = alignItems;
        b.alignSelf = alignSelf;
        b.justifyItems = justifyItems;
        b.justifySelf = justifySelf;
        b.gapRow = gapRow;
        b.gapColumn = gapColumn;
        b.aspectRatio = aspectRatio;
        b.rtl = rtl;
        b.order = order;
        b.columns = columns;
        b.rows = rows;
        b.autoColumns = autoColumns;
        b.autoRows = autoRows;
        b.autoFlow = autoFlow;
        b.gridColumn = gridColumn;
        b.gridRow = gridRow;
        b.replaced = replaced;
        b.overflowHidden = overflowHidden;
        b.floating = floating;
        b.shrinkToFit = shrinkToFit;
        return b;
    }
}
