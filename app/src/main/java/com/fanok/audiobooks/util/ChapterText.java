package com.fanok.audiobooks.util;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;

/**
 * The player's chapter texts: a chapter's name in three colours (letters, digits, other symbols), its length, and
 * the lines that say where the book stands. Only on screen: stored names, files and the notification keep the name
 * as it is. All but {@link #colored} is pure.
 */
public final class ChapterText {

    /** The kinds of {@link #runs}: digits, and what is neither a letter, a digit nor a space. */
    public static final int DIGIT = 1;
    public static final int SYMBOL = 2;

    private static final int LETTER = 0;
    private static final int SPACE = -1;

    private ChapterText() {
    }

    /** The name as shown: a file name's "_" reads as a space. */
    @NonNull
    public static String shown(@Nullable String name) {
        if (name == null) {
            return "";
        }
        return name.indexOf('_') < 0 ? name : name.replace('_', ' ');
    }

    /**
     * The runs of digits and of symbols in {@code text}, in order, as {start, end, kind} triples; letters and spaces
     * are in none. One pass over the characters.
     */
    @NonNull
    public static int[] runs(@NonNull CharSequence text) {
        int[] runs = new int[12];
        int count = 0;
        int kind = LETTER;
        int start = 0;
        int length = text.length();
        for (int i = 0; i <= length; i++) {
            int next = i == length ? SPACE : kindOf(text.charAt(i), kind);
            if (next == kind) {
                continue;
            }
            if (kind == DIGIT || kind == SYMBOL) {
                if (count == runs.length) {
                    runs = Arrays.copyOf(runs, count * 2);
                }
                runs[count++] = start;
                runs[count++] = i;
                runs[count++] = kind;
            }
            kind = next;
            start = i;
        }
        return count == runs.length ? runs : Arrays.copyOf(runs, count);
    }

    /** The kind of {@code c}; a combining mark is of its letter's ({@code previous}). */
    private static int kindOf(char c, int previous) {
        if (Character.isDigit(c)) {
            return DIGIT;
        }
        if (Character.isLetter(c)) {
            return LETTER;
        }
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
            return SPACE;
        }
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
            return previous;
        }
        return SYMBOL;
    }

    /**
     * {@code name} as shown, its digits in {@code digit} and its other symbols in {@code symbol}; the letters keep
     * the colour of the view they are shown in.
     */
    @NonNull
    public static CharSequence colored(@Nullable String name, @ColorInt int digit, @ColorInt int symbol) {
        String shown = shown(name);
        int[] runs = runs(shown);
        if (runs.length == 0) {
            return shown;
        }
        SpannableString text = new SpannableString(shown);
        for (int i = 0; i < runs.length; i += 3) {
            text.setSpan(new ForegroundColorSpan(runs[i + 2] == DIGIT ? digit : symbol), runs[i], runs[i + 1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    /** A chapter's length: "10:09", the hours only when it has them ("1:02:45"). */
    @NonNull
    public static String duration(int seconds) {
        int total = Math.max(0, seconds);
        int hours = total / 3600;
        StringBuilder text = new StringBuilder(8);
        if (hours > 0) {
            text.append(hours).append(':');
        }
        twoDigits(text, total % 3600 / 60).append(':');
        return twoDigits(text, total % 60).toString();
    }

    private static StringBuilder twoDigits(StringBuilder text, int value) {
        return (value < 10 ? text.append('0') : text).append(value);
    }

    /**
     * The line under the playing chapter's name: "Глава 3 из 16 · осталось 1 ч. 32 мин." at the speed of
     * {@code line}; the chapter alone when a chapter's length is unknown; the book's size ({@code total} seconds)
     * until it is started; "" without chapters.
     */
    @NonNull
    public static String subtitle(@NonNull RemainingTime.Line line, long total) {
        if (line.chapters <= 0) {
            return "";
        }
        String chapter = "Глава " + line.chapter + " из " + line.chapters;
        switch (line.state) {
            case RemainingTime.LEFT:
                return chapter + " · осталось " + RemainingTime.format(line.left);
            case RemainingTime.DONE:
                return chapter + " · прослушано";
            case RemainingTime.CHAPTER:
                return chapter;
            default:
                return chapters(line.chapters) + (total > 0 ? " · " + RemainingTime.format(total) : "");
        }
    }

    /**
     * The line under the whole book's progress: "Прослушано 15 % из 2 ч. 41 мин.", without the length when a
     * chapter's is unknown; "" until the book is started: no progress to show yet.
     */
    @NonNull
    public static String progress(@NonNull RemainingTime.Line line, long total) {
        switch (line.state) {
            case RemainingTime.LEFT:
            case RemainingTime.CHAPTER:
                return "Прослушано " + line.permille / 10 + " %"
                        + (total > 0 ? " из " + RemainingTime.format(total) : "");
            case RemainingTime.DONE:
                return "Прослушано";
            default:
                return "";
        }
    }

    /** "1 глава", "3 главы", "16 глав". */
    @NonNull
    static String chapters(int count) {
        int tens = count % 100;
        int ones = count % 10;
        boolean many = tens >= 11 && tens <= 14 || ones == 0 || ones >= 5;
        return count + (many ? " глав" : ones == 1 ? " глава" : " главы");
    }
}
