/*
 * The two measures below follow OptimalStringAlignment and SorensenDice of java-string-similarity
 * (https://github.com/tdebatty/java-string-similarity), rewritten compactly for the search.
 *
 * The MIT License
 *
 * Copyright 2015 Thibault Debatty.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.fanok.audiobooks.search;

import java.util.HashSet;
import java.util.Set;

/**
 * Optimal string alignment distance, for a typo inside one word ("серцде" → "сердце": a swap of neighbours is one
 * edit), and Sørensen–Dice over character bigrams, for titles that differ only slightly. Both work on the
 * {@link TextNorm} form of the text.
 */
public final class StringSim {

    private StringSim() {
    }

    /**
     * Edits (insertion, deletion, substitution, swap of two neighbours) that turn {@code a} into {@code b}, or
     * {@code max + 1} when that takes more than {@code max}: words whose lengths differ by more are never compared.
     */
    public static int osa(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > max) {
            return max + 1;
        }
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return Math.min(d[n][m], max + 1);
    }

    /** The distinct pairs of neighbouring characters of {@code s} with its spaces left out; a lone character is its own. */
    public static Set<String> bigrams(String s) {
        String t = s.replace(" ", "");
        Set<String> out = new HashSet<>();
        if (t.length() == 1) {
            out.add(t);
        }
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    /** 2·|A ∩ B| / (|A| + |B|): 1 for equal sets, 0 for disjoint ones (and for two empty ones). */
    public static double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String s : a) {
            if (b.contains(s)) {
                common++;
            }
        }
        return 2.0 * common / (a.size() + b.size());
    }
}
