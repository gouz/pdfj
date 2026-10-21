package org.jsoup.select;

/**
 * Access to {@link Evaluator#reset()}, which is protected: structural evaluators ({@code ul li},
 * {@code a > b}...) memoize their matches per thread until reset, and only {@link Selector#select}
 * resets them. pdfj matches its user-agent rules (shared by every document) directly, so it resets
 * them after each document, or their memo would keep every element of every document rendered.
 */
public final class Evaluators {
    private Evaluators() {}

    public static void reset(Evaluator e) {
        e.reset();
    }
}
