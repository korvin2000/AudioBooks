package com.fanok.audiobooks.adapter;

import com.fanok.audiobooks.search.TextNorm;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the book list fits an author and a reader on one line ({@link FairRow}). Pure Java, no Android.
 * <p>
 * A name has up to {@link #STEPS} spellings, each a step shorter than the one before and the step number its cost:
 * 0 as given; 1 typography ("Э. Э. «Док» Смит" → {@code Э.Э. "Док" Смит}); 2 without brackets ("(Маяк)");
 * 3 without a middle name ("Говард Лавкрафт") and a list of three people or more as "Первый и др.";
 * 4 given names as initials ("Г.Ф. Лавкрафт", "Кинг С.").
 * <p>
 * No list of names: which end of a name is the surname comes from its form ({@link #surname}) — initials, a particle,
 * a patronymic, a Russian surname ending — and, when the name itself does not tell, from the other names of the row:
 * a site writes its authors and readers in one order. With no hint at all the given names stay whole: a cut name is
 * better than a wrong initial (sites write both "Стивен Кинг" and "Кинг Стивен").
 */
final class NameFit {

    static final int STEPS = 5;

    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern INITIAL_GAP = Pattern.compile("(\\p{Lu}\\p{Ll}?\\.)\\s+(?=\\p{Lu}\\p{Ll}?\\.)");
    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[][^()\\[\\]]*[)\\]]");
    private static final Pattern PEOPLE_BREAKS = Pattern.compile("\\s*[,;/&]\\s*|\\s+и\\s+");
    /** Endings only Russian surnames have (not "-ин": Валентин, Марина). */
    private static final String[] SURNAME = {"ов", "ев", "ёв", "ова", "ева", "ёва", "ский", "цкий", "ская", "цкая",
            "ской", "цкой", "енко", "ук", "юк", "ич", "ых", "ын", "ына"};
    /** Adjective endings: such a word is a nickname's ("Маленький Фонарщик") or a surname, never a given name. */
    private static final String[] ADJECTIVE = {"кий", "ый", "ой", "ая", "яя"};
    private static final int[] NONE = {0};

    private NameFit() {
    }

    /**
     * The spellings of a book's author and reader: [0] the field with its spaces tidied, null for a step that changes
     * nothing; null for no name.
     */
    static String[][] variants(String author, String reader) {
        String[] a = spellings(author);
        String[] b = spellings(reader);
        int order = order(a) + order(b);
        shorten(a, order);
        shorten(b, order);
        return new String[][]{a, b};
    }

    /** Steps 0 to 2. */
    private static String[] spellings(String field) {
        String s = field == null ? "" : SPACES.matcher(field).replaceAll(" ").trim();
        if (s.isEmpty()) {
            return null;
        }
        String[] v = new String[STEPS];
        v[0] = s;
        s = INITIAL_GAP.matcher(s).replaceAll("$1")
                .replace('«', '"').replace('»', '"').replace('„', '"').replace('“', '"').replace('”', '"');
        put(v, 1, s);
        String bare = SPACES.matcher(BRACKETS.matcher(s).replaceAll("")).replaceAll(" ").trim();
        for (int k = 0; k < bare.length(); k++) {
            if (Character.isLetter(bare.charAt(k))) { // not when nothing but brackets is left
                put(v, 2, bare);
                break;
            }
        }
        return v;
    }

    /** Steps 3 and 4; {@code order} &gt; 0: the row's names are written surname first. */
    private static void shorten(String[] v, int order) {
        if (v == null) {
            return;
        }
        String s = last(v);
        String tail = s.endsWith(" и др.") ? " и др." : s.endsWith(" и др") ? " и др" : "";
        List<String> people = people(s.substring(0, s.length() - tail.length()));
        if (people.size() >= 3) {
            people.subList(1, people.size()).clear();
            tail = " и др.";
        }
        StringBuilder middle = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (String person : people) {
            String[] w = person.split(" ");
            int surname = surname(w, order);
            if (surname < 0 && people.size() > 1) {
                return; // the "Аркадий" of "Аркадий и Борис Стругацкие": the list stays as it is
            }
            String sep = middle.length() == 0 ? "" : ", ";
            middle.append(sep).append(surname < 0 ? person : withoutMiddle(w, surname, person));
            initials.append(sep).append(surname < 0 ? person : initials(w, surname));
        }
        if (middle.length() > 0) {
            put(v, 3, middle + tail);
            put(v, 4, initials + tail);
        }
    }

    /** The last spelling made so far. */
    private static String last(String[] v) {
        int k = v.length - 1;
        while (v[k] == null) {
            k--;
        }
        return v[k];
    }

    /** Keeps a spelling that differs from the last one kept. */
    private static void put(String[] v, int step, String s) {
        if (!last(v).equals(s)) {
            v[step] = s;
        }
    }

    /** The people of a field ("А, Б и В", "А / Б"), empty pieces skipped. */
    private static List<String> people(String s) {
        List<String> people = new ArrayList<>();
        for (String person : PEOPLE_BREAKS.split(s)) {
            if (!person.isEmpty()) {
                people.add(person);
            }
        }
        return people;
    }

    /** How surely a field's names are written surname first (&gt; 0) or given name first (&lt; 0). */
    private static int order(String[] v) {
        int order = 0;
        if (v != null) {
            for (String person : people(last(v))) {
                String[] w = person.split(" ");
                order += w.length < 2 ? 0 : surnameEnding(w[0]) - surnameEnding(w[w.length - 1]);
            }
        }
        return order;
    }

    /** 1 for a Russian surname ending ("Петров", "ПЕТРОВА", "Короленко"), else 0. */
    private static int surnameEnding(String word) {
        return endsWith(word, SURNAME) ? 1 : 0;
    }

    /** One of these endings, in any case, on a word of five letters or more (not "Лев", "Яков", "Ева", "Рой"). */
    private static boolean endsWith(String word, String[] ends) {
        if (word.length() < 5) {
            return false;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        for (String end : ends) {
            if (lower.endsWith(end)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the surname starts among a person's words; -1 when the name is to stay whole: one word or more than four,
     * anything but letters, a lower-case word that is no particle ("Театр у микрофона" is no person), a given name
     * that looks like an adjective. In order: a short lower-case word before a capital one, or a two-letter word
     * inside, starts it ("Антуан де Сент-Экзюпери", "Урсула Ле Гуин"); the only word that is no initial ("Дж.Р.Р. Толкин"); the patronymic's place in three words;
     * a Russian surname ending at one end only; the row's {@code order}; a Latin name: the last word. No hint: -1.
     */
    static int surname(String[] w, int order) {
        int n = w.length;
        if (n < 2 || n > 4) {
            return -1;
        }
        int full = -1;
        int fulls = 0;
        for (int k = 0; k < n; k++) {
            if (!isWord(w[k])) {
                return -1;
            }
            boolean lower = Character.isLowerCase(w[k].charAt(0));
            if (lower && (k == 0 || k == n - 1 || w[k].length() > 3 || Character.isLowerCase(w[k + 1].charAt(0)))) {
                return -1; // a phrase, not a particle before a surname
            }
            if (lower || k > 0 && k < n - 1 && w[k].length() == 2 && !isInitial(w[k])) {
                return k; // a particle starts the surname
            }
            if (!isInitial(w[k])) {
                full = k;
                fulls++;
            }
        }
        if (fulls <= 1) {
            return full;
        }
        int surname;
        if (n == 3 && TextNorm.isPatronymic(w[1].toLowerCase(Locale.ROOT))) {
            surname = 2;
        } else if (n == 3 && TextNorm.isPatronymic(w[2].toLowerCase(Locale.ROOT))) {
            surname = 0;
        } else {
            int own = surnameEnding(w[0]) - surnameEnding(w[n - 1]);
            if (own == 0) { // the name doesn't tell: the row's names may; a Latin name is given name first
                own = w[0].charAt(0) <= 'z' ? -1 : Integer.signum(order);
            }
            if (own == 0) {
                return -1; // no hint at all: better cut than a wrong initial ("Касезьян Роберт" → "К. Роберт")
            }
            surname = own > 0 ? 0 : n - 1;
        }
        for (int k = surname == 0 ? 1 : 0; k < (surname == 0 ? n : surname); k++) {
            if (endsWith(w[k], ADJECTIVE)) {
                return -1;
            }
        }
        return surname;
    }

    /** Letters, with hyphens and apostrophes inside; or initials ("Э.", "Дж.", "Г.Ф."). */
    private static boolean isWord(String w) {
        if (w.isEmpty() || !Character.isLetter(w.charAt(0))) {
            return false;
        }
        if (isInitial(w)) {
            return true;
        }
        for (int k = 1; k < w.length(); k++) {
            char c = w.charAt(k);
            if (!Character.isLetter(c) && c != '-' && c != '\'' && c != '’') {
                return false;
            }
        }
        return true;
    }

    /** "Э.", "Дж.", "Г.Ф.": capital letters, each with at most one small letter and a dot. */
    private static boolean isInitial(String w) {
        int k = 0;
        while (k < w.length()) {
            if (!Character.isUpperCase(w.charAt(k++))) {
                return false;
            }
            if (k < w.length() && Character.isLowerCase(w.charAt(k))) {
                k++;
            }
            if (k >= w.length() || w.charAt(k++) != '.') {
                return false;
            }
        }
        return k > 0;
    }

    /** "Говард Филлипс Лавкрафт" → "Говард Лавкрафт", "Булгаков Михаил Афанасьевич" → "Булгаков Михаил". */
    private static String withoutMiddle(String[] w, int surname, String person) {
        if (w.length != 3 || isInitial(w[1])) {
            return person;
        }
        return surname == 2 ? w[0] + " " + w[2] : surname == 0 ? w[0] + " " + w[1] : person;
    }

    /** "Г.Ф. Лавкрафт", "А. де Сент-Экзюпери", "Кинг С.", "Ж.-П. Сартр". */
    private static String initials(String[] w, int surname) {
        StringBuilder given = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        for (int k = 0; k < w.length; k++) {
            if (surname == 0 ? k == 0 : k >= surname) {
                rest.append(rest.length() == 0 ? "" : " ").append(w[k]);
            } else if (isInitial(w[k])) {
                given.append(w[k]);
            } else {
                String[] parts = w[k].split("-");
                for (int p = 0; p < parts.length; p++) {
                    if (!parts[p].isEmpty()) {
                        given.append(p == 0 ? "" : "-").append(Character.toUpperCase(parts[p].charAt(0))).append('.');
                    }
                }
            }
        }
        return surname == 0 ? rest + " " + given : given + " " + rest;
    }

    /**
     * Which spellings to show and how wide each half is: {author step, reader step, author width, reader width}.
     * The widths are those of the spellings with their icons, -1 for a missing step; null for an absent name, whose
     * step is then -1 and whose half is 0 wide. One pass, at most a step per spelling: while the two don't fit, the
     * name whose next narrower step is cheaper takes it; of two as cheap the longer in full, of two as long the
     * author (authors are known by surname and initials). Once they fit, each takes back the fullest spelling that
     * still fits, the shorter name first. When even the shortest don't fit, the shorter name shows its first
     * spelling within half the room whole and the other is cut; both longer than half are cut at half.
     */
    static int[] choose(int[] authorWidths, int[] readerWidths, int room) {
        int[] a = authorWidths == null ? NONE : authorWidths;
        int[] b = readerWidths == null ? NONE : readerWidths;
        boolean authorLonger = a[0] >= b[0];
        int i = 0;
        int j = 0;
        while (a[i] + b[j] > room) {
            int x = next(a, i);
            int y = next(b, j);
            if (x < 0 && y < 0) {
                break;
            }
            if (y < 0 || x >= 0 && (x < y || x == y && authorLonger)) {
                i = x;
            } else {
                j = y;
            }
        }
        if (a[i] + b[j] <= room) { // undo the steps the other name's later step made needless
            if (authorLonger) {
                j = first(b, room - a[i], j);
                i = first(a, room - b[j], i);
            } else {
                i = first(a, room - b[j], i);
                j = first(b, room - a[i], j);
            }
        } else if (a[i] <= b[j]) {
            i = first(a, room / 2, i);
        } else {
            j = first(b, room / 2, j);
        }
        int readerWidth = Math.min(b[j], Math.max(room / 2, room - a[i]));
        return new int[]{authorWidths == null ? -1 : i, readerWidths == null ? -1 : j, room - readerWidth,
                readerWidth};
    }

    /** The next spelling after step k that is narrower than it, -1 when none. */
    private static int next(int[] w, int k) {
        for (int s = k + 1; s < w.length; s++) {
            if (w[s] >= 0 && w[s] < w[k]) {
                return s;
            }
        }
        return -1;
    }

    /** The first spelling within the width, else step k. */
    private static int first(int[] w, int width, int k) {
        for (int s = 0; s < k; s++) {
            if (w[s] >= 0 && w[s] <= width) {
                return s;
            }
        }
        return k;
    }
}
