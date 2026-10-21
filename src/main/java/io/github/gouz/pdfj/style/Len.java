package io.github.gouz.pdfj.style;

/**
 * A CSS length in pt: auto, an absolute length, a percentage, or {@code calc()} mixing both
 * ({@code px + pct%} of the containing block).
 */
public record Len(Kind kind, double px, double pct) {
    public enum Kind { AUTO, PX, PCT, CALC }

    public static final Len AUTO = new Len(Kind.AUTO, 0, 0);
    public static final Len ZERO = new Len(Kind.PX, 0, 0);

    public static Len px(double v) {
        return new Len(Kind.PX, v, 0);
    }

    public static Len pct(double v) {
        return new Len(Kind.PCT, 0, v);
    }

    public static Len calc(double px, double pct) {
        return new Len(Kind.CALC, px, pct);
    }

    public boolean isAuto() {
        return kind == Kind.AUTO;
    }

    public boolean isPx() {
        return kind == Kind.PX;
    }

    public boolean isPct() {
        return kind == Kind.PCT;
    }

    /** the absolute value, or null when relative or auto */
    public Double pxValue() {
        return kind == Kind.PX ? px : null;
    }

    /** against a basis (percentages, calc); auto is 0 */
    public double resolve(double basis) {
        return switch (kind) {
            case PX -> px;
            case PCT -> basis * pct / 100.0;
            case CALC -> px + basis * pct / 100.0;
            case AUTO -> 0.0;
        };
    }

    /** against a basis that may be unknown (NaN): auto, or a percentage of an unknown basis, is NaN */
    public double resolveOrNaN(double basis) {
        return switch (kind) {
            case PX -> px;
            case PCT -> Double.isNaN(basis) ? Double.NaN : basis * pct / 100.0;
            case CALC -> Double.isNaN(basis) ? (pct == 0 ? px : Double.NaN) : px + basis * pct / 100.0;
            case AUTO -> Double.NaN;
        };
    }
}
