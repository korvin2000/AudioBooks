package com.fanok.audiobooks.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The search form of query and card text, one for matching and grouping alike: NFC, lower case, ё → е, a Latin
 * look-alike letter inside a Cyrillic word turned Cyrillic (a mistyped "Мастeр"), and every run of anything but letters
 * and digits (punctuation, dashes, quotes, symbols, any space) one word break. Numbers and short words stay: "том 2"
 * must match and "Мы" is a title. On top of that: a title's recording edition ("(полная версия)", "спектакль") apart
 * from the title, and people's names as sets of words.
 */
public final class TextNorm {

    /** Conjunctions and prepositions: they never count towards a query's coverage. */
    static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "и", "в", "на", "с", "о", "а", "у", "к", "по", "из", "за", "не", "от", "для", "the", "of", "a", "and"));
    /** Latin letters that look Cyrillic, lower-cased: b, h, k, m, t stand for the capitals В, Н, К, М, Т. */
    private static final String LATIN = "aceopxybhkmt";
    private static final String CYRILLIC = "асеорхувнкмт";
    /** Markers of a recording's edition: words in order, a trailing '*' matches any ending ("сокращённая"). */
    private static final String[][] EDITION = {{"полная", "версия"}, {"полный", "вариант"}, {"сокращ*"},
            {"радиоспектакль"}, {"аудиоспектакль"}, {"спектакль"}, {"радиопостановка"}, {"инсценировка"},
            {"фрагмент"}, {"отрывок"}, {"с", "музыкой"}};
    private static final Pattern BRACKETS = Pattern.compile("[(\\[]([^()\\[\\]]*)[)\\]]");
    private static final Pattern PEOPLE_BREAKS = Pattern.compile("\\s*(?:[,;/&]|\\s\\s*и\\s+)\\s*");
    private static final Pattern AND_OTHERS = Pattern.compile("(?:\\s+и)?\\s+др\\.?\\s*$");
    private static final String[] PATRONYMIC = {"ович", "евич", "ьич", "ич", "овна", "евна", "ична", "ьевна"};

    private TextNorm() {
    }

    /** The words of {@code s} in the search form; empty for null or for text without letters and digits. */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= n.length(); i++) {
            char c = i < n.length() ? n.charAt(i) : ' ';
            if (c == '̀' || c == '́') {
                continue; // a stress mark ("за́мок"): Cyrillic vowels have no composed form to absorb it
            }
            if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK) {
                word.append(c == 'ё' ? 'е' : c);
            } else if (word.length() > 0) {
                out.add(foldLookalikes(word));
                word.setLength(0);
            }
        }
        return out;
    }

    /** {@link #tokens} joined by single spaces: equal keys mean equal text for the search. */
    public static String key(String s) {
        return join(tokens(s));
    }

    static String join(List<String> words) {
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(w);
        }
        return out.toString();
    }

    public static boolean isNumber(String word) {
        if (word.isEmpty()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a query word counts towards coverage: a number always, otherwise two letters or more and not a stop word. */
    public static boolean isSignificant(String word) {
        return isNumber(word) || word.length() >= 2 && !STOP.contains(word);
    }

    /**
     * {@code [title, edition]}: the title's key without the recording edition, and the edition's key ("" when there is
     * none). Only the words of recognised markers move ("(полная версия)", "спектакль"); "(том 1)", "(сборник)",
     * subtitles, and the rest of a bracket with a marker ("(радиоспектакль, часть 2)" keeps "часть 2") stay in the
     * title, so different volumes keep different keys. A title that is nothing but a marker stays whole.
     */
    public static String[] splitEdition(String title) {
        if (title == null) {
            return new String[]{"", ""};
        }
        List<String> edition = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        Matcher m = BRACKETS.matcher(title);
        int last = 0;
        while (m.find()) {
            List<String> inside = new ArrayList<>(tokens(m.group(1)));
            if (findMarker(inside) != null) {
                takeMarkers(inside, edition);
                rest.append(title, last, m.start()).append(' ').append(join(inside)).append(' ');
                last = m.end();
            }
        }
        rest.append(title.substring(last));
        List<String> words = new ArrayList<>(tokens(rest.toString()));
        int[] marker = findMarker(words);
        if (marker != null) {
            edition.addAll(words.subList(marker[0], marker[1]));
            words.subList(marker[0], marker[1]).clear();
        }
        if (words.isEmpty()) {
            return new String[]{key(title), ""};
        }
        return new String[]{join(words), join(edition)};
    }

    /** Moves every edition marker of {@code words} to {@code edition}. */
    private static void takeMarkers(List<String> words, List<String> edition) {
        for (int[] at = findMarker(words); at != null; at = findMarker(words)) {
            edition.addAll(words.subList(at[0], at[1]));
            words.subList(at[0], at[1]).clear();
        }
    }

    /** {@code [from, to)} of the first edition marker in {@code words}, or null. */
    private static int[] findMarker(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            for (String[] marker : EDITION) {
                if (i + marker.length <= words.size() && markerAt(words, i, marker)) {
                    return new int[]{i, i + marker.length};
                }
            }
        }
        return null;
    }

    private static boolean markerAt(List<String> words, int at, String[] marker) {
        for (int k = 0; k < marker.length; k++) {
            String w = words.get(at + k);
            String part = marker[k];
            boolean ok = part.endsWith("*") ? w.startsWith(part.substring(0, part.length() - 1)) : w.equals(part);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** One person of a card: the words of the name without initials and patronymic, and the initials. */
    public static final class Person {

        public final SortedSet<String> words;
        public final SortedSet<String> initials;

        Person(SortedSet<String> words, SortedSet<String> initials) {
            this.words = Collections.unmodifiableSortedSet(words);
            this.initials = Collections.unmodifiableSortedSet(initials);
        }

        public boolean isEmpty() {
            return words.isEmpty() && initials.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Person && words.equals(((Person) o).words) && initials.equals(((Person) o).initials);
        }

        @Override
        public int hashCode() {
            return 31 * words.hashCode() + initials.hashCode();
        }

        @Override
        public String toString() {
            return words + "" + initials;
        }
    }

    /** The people of an author or reader field ("А, Б и В", "А / Б", "и др." dropped); empty for null. */
    public static List<Person> persons(String field) {
        List<Person> out = new ArrayList<>();
        if (field == null) {
            return out;
        }
        String s = AND_OTHERS.matcher(field.trim()).replaceAll("");
        for (String part : PEOPLE_BREAKS.split(" " + s + " ")) {
            Person p = person(part);
            if (!p.isEmpty() && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * One name as a {@link Person}: words of three letters or more, the rest as initials; in a name of three words or
     * more one patronymic goes (the middle word for "Имя Отчество Фамилия", else the last for "Фамилия Имя Отчество"),
     * so "Генрик Сенкевич" keeps its surname. Words count by the spaces of the name: a hyphenated surname is one of
     * them ("Немирович-Данченко" is never a patronymic) and also counts as one joined word.
     */
    public static Person person(String name) {
        List<List<String>> pieces = new ArrayList<>();
        SortedSet<String> initials = new TreeSet<>();
        SortedSet<String> joined = new TreeSet<>();
        for (String piece : name == null ? new String[0] : name.trim().split("\\s+")) {
            List<String> parts = tokens(piece);
            List<String> words = new ArrayList<>();
            for (String w : parts) {
                if (w.length() > 2) {
                    words.add(w);
                } else if (!isNumber(w)) {
                    initials.add(w);
                }
            }
            if (!words.isEmpty()) {
                pieces.add(words);
            }
            if (parts.size() >= 2 && piece.indexOf('-') > 0) {
                joined.add(join(parts).replace(" ", ""));
            }
        }
        if (pieces.size() >= 3) {
            if (isPatronymic(pieces.get(1))) {
                pieces.remove(1);
            } else if (isPatronymic(pieces.get(pieces.size() - 1))) {
                pieces.remove(pieces.size() - 1);
            }
        }
        SortedSet<String> set = new TreeSet<>(joined);
        for (List<String> p : pieces) {
            set.addAll(p);
        }
        return new Person(set, initials);
    }

    private static boolean isPatronymic(List<String> piece) {
        return piece.size() == 1 && isPatronymic(piece.get(0));
    }

    static boolean isPatronymic(String w) {
        if (w.length() < 6) {
            return false;
        }
        for (String end : PATRONYMIC) {
            if (w.endsWith(end)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same person on two cards: the words of one name are all in the other ("Булгаков" and "Михаил Булгаков",
     * "Кинг Стивен" and "Стивен Кинг"); names without words compare their initials. Endings are never forgiven:
     * "Котов" and "Котова" are two people.
     */
    public static boolean samePerson(Person a, Person b) {
        if (a.words.isEmpty() || b.words.isEmpty()) {
            return a.words.isEmpty() && b.words.isEmpty() && a.initials.equals(b.initials);
        }
        Person small = a.words.size() <= b.words.size() ? a : b;
        Person big = small == a ? b : a;
        return big.words.containsAll(small.words);
    }

    /** Every word of every person, sorted: the author part of a group key. */
    public static SortedSet<String> words(List<Person> people) {
        SortedSet<String> out = new TreeSet<>();
        for (Person p : people) {
            out.addAll(p.words);
        }
        return out;
    }

    /** Only a Cyrillic word whose every Latin letter has a Cyrillic twin: "St\u0435phen" with one Cyrillic "\u0435" stays Latin. */
    private static String foldLookalikes(StringBuilder word) {
        boolean cyrillic = false;
        boolean latin = false;
        boolean other = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            cyrillic |= c >= '\u0400' && c <= '\u04ff';
            latin |= LATIN.indexOf(c) >= 0;
            other |= c >= 'a' && c <= 'z' && LATIN.indexOf(c) < 0;
        }
        if (cyrillic && latin && !other) {
            for (int i = 0; i < word.length(); i++) {
                int at = LATIN.indexOf(word.charAt(i));
                if (at >= 0) {
                    word.setCharAt(i, CYRILLIC.charAt(at));
                }
            }
        }
        return word.toString();
    }
}
