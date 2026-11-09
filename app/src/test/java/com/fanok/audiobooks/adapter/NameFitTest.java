package com.fanok.audiobooks.adapter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.Random;
import org.junit.Test;

public class NameFitTest {

    /** Spellings of a name alone in its row, the steps that change nothing left out. */
    private static String steps(String name) {
        return steps(name, null);
    }

    /** Spellings of an author next to this reader. */
    private static String steps(String author, String reader) {
        StringBuilder out = new StringBuilder();
        for (String s : NameFit.variants(author, reader)[0]) {
            if (s != null) {
                out.append(out.length() == 0 ? "" : " | ").append(s);
            }
        }
        return out.toString();
    }

    /** 10 px a character and a 20 px icon, -1 for a missing step. */
    private static int[] widths(String[] v) {
        int[] w = new int[v.length];
        for (int k = 0; k < v.length; k++) {
            w[k] = v[k] == null ? -1 : 20 + 10 * v[k].length();
        }
        return w;
    }

    /** The two names as shown in a room, a cut one marked with "~" and the width it gets. */
    private static String fit(String author, String reader, int room) {
        String[][] v = NameFit.variants(author, reader);
        int[] c = NameFit.choose(widths(v[0]), widths(v[1]), room);
        return shown(v[0][c[0]], c[2]) + " | " + shown(v[1][c[1]], c[3]);
    }

    private static String shown(String s, int width) {
        return 20 + 10 * s.length() <= width ? s : s + "~" + width;
    }

    @Test
    public void spacesAreTidiedAndNoNameIsNull() {
        assertEquals("Иван Петров", NameFit.variants("  Иван  Петров ", null)[0][0]);
        assertNull(NameFit.variants("  ", null)[0]);
        assertNull(NameFit.variants(null, null)[0]);
    }

    @Test
    public void typographyComesFirstThenBrackets() {
        assertEquals("Э. Э. «Док» Смит | Э.Э. \"Док\" Смит", steps("Э. Э. «Док» Смит"));
        assertEquals("Денис Журавлёв (Маяк) | Денис Журавлёв | Д. Журавлёв", steps("Денис Журавлёв (Маяк)"));
    }

    @Test
    public void aMiddleNameGoesBeforeTheInitials() {
        assertEquals("Говард Филлипс Лавкрафт | Говард Лавкрафт | Г.Ф. Лавкрафт",
                steps("Говард Филлипс Лавкрафт", "Артём Мещеряков"));
        assertEquals("Булгаков Михаил Афанасьевич | Булгаков Михаил | Булгаков М.А.",
                steps("Булгаков Михаил Афанасьевич"));
        assertEquals("Фёдор Михайлович Достоевский | Фёдор Достоевский | Ф.М. Достоевский",
                steps("Фёдор Михайлович Достоевский"));
    }

    @Test
    public void theGivenNameIsFirstUnlessTheNameSaysOtherwise() {
        assertEquals("Владимир Короленко | В. Короленко", steps("Владимир Короленко"));
        assertEquals("Валентина Дмитриева | В. Дмитриева", steps("Валентина Дмитриева"));
        assertEquals("Дмитриева Валентина | Дмитриева В.", steps("Дмитриева Валентина"));
        assertEquals("ПЕТРОВ ИВАН | ПЕТРОВ И.", steps("ПЕТРОВ ИВАН"));
        assertEquals("Ги де Мопассан | Г. де Мопассан", steps("Ги де Мопассан"));
        assertEquals("Урсула Ле Гуин | У. Ле Гуин", steps("Урсула Ле Гуин"));
        assertEquals("Stephen King | S. King", steps("Stephen King"));
        assertEquals("Дж. Р. Р. Толкин | Дж.Р.Р. Толкин", steps("Дж. Р. Р. Толкин"));
    }

    @Test
    public void aNameThatDoesNotTellTakesTheOrderOfItsRow() {
        assertEquals("Кинг Стивен | Кинг С.", steps("Кинг Стивен", "Кузнецов Игорь"));
        assertEquals("Кинг Стивен | К. Стивен", steps("Кинг Стивен", "Игорь Кузнецов"));
        assertEquals("Пушкин Александр | Пушкин А.", steps("Пушкин Александр", "Кузнецов Игорь"));
        assertEquals("Валентин Пикуль | В. Пикуль", steps("Валентин Пикуль", "Игорь Князев"));
        assertEquals("Эймиас Норткот | Э. Норткот", steps("Эймиас Норткот", "Шад Ибраев (Freshman)"));
        assertEquals("Жан-Поль Сартр | Ж.-П. Сартр", steps("Жан-Поль Сартр", "Сергей Кирсанов"));
        assertEquals("Stephen King | S. King", steps("Stephen King", "Кузнецов Игорь")); // Latin: given name first
    }

    @Test
    public void withNoHintTheGivenNamesStayWhole() {
        // "Касезьян Роберт" and "Роберт Касезьян" are both written: a wrong initial is worse than a cut
        assertEquals("Касезьян Роберт", steps("Касезьян Роберт", "Пятница 13"));
        assertEquals("Валентин Пикуль", steps("Валентин Пикуль"));
        assertEquals("Ломакин Игорь", steps("Ломакин Игорь", "Мартинес Ева"));
        assertEquals("Говард Филлипс Лавкрафт", steps("Говард Филлипс Лавкрафт"));
    }

    @Test
    public void aNicknameOrAnOddFieldKeepsItsWords() {
        assertEquals("Маленький Фонарщик", steps("Маленький Фонарщик"));
        assertEquals("Аркадий и Борис Стругацкие", steps("Аркадий и Борис Стругацкие"));
        assertEquals("Коллектив авторов", steps("Коллектив авторов"));
        assertEquals("Театр у микрофона", steps("Театр у микрофона"));
        assertEquals("Пятница 13", steps("Пятница 13"));
        assertEquals("Студия «Ардис» | Студия \"Ардис\"", steps("Студия «Ардис»"));
        assertEquals("Jean--Paul Sartre | J.-P. Sartre", steps("Jean--Paul Sartre"));
    }

    @Test
    public void severalPeople() {
        assertEquals("Анастасия Шерр, Анастасия Зарубина | А. Шерр, А. Зарубина",
                steps("Анастасия Шерр, Анастасия Зарубина", "Светлана Селивёрстова"));
        assertEquals("Иван Литвинов, Кирилл Радциг, Ирина Ерисанова | Иван Литвинов и др. | И. Литвинов и др.",
                steps("Иван Литвинов, Кирилл Радциг, Ирина Ерисанова"));
        assertEquals(", Иван Петров, , Пётр Сидоров | Иван Петров, Пётр Сидоров | И. Петров, П. Сидоров",
                steps(", Иван Петров, , Пётр Сидоров"));
    }

    @Test
    public void bothWholeWhenTheyFit() {
        assertEquals("Мира Вишес | Екатерина Пушкарёва", fit("Мира Вишес", "Екатерина Пушкарёва", 400));
    }

    @Test
    public void theCheapestStepThatFitsWins() {
        // the brackets go before anyone's initials
        assertEquals("Моран Джурич | Денис Журавлёв", fit("Моран Джурич", "Денис Журавлёв (Маяк)", 340));
        // typography before the reader's initials
        assertEquals("Э.Э. \"Док\" Смит | Александр Абрамович", fit("Э. Э. «Док» Смит", "Александр Абрамович", 385));
        // a nickname has no initials: the author is shortened
        assertEquals("В. Дмитриева | Маленький Фонарщик", fit("Валентина Дмитриева", "Маленький Фонарщик", 360));
    }

    @Test
    public void theLongerNameGivesWay() {
        assertEquals("Анастасия Шерр | С. Селивёрстова", fit("Анастасия Шерр", "Светлана Селивёрстова", 360));
        assertEquals("Сергей Вишневский | А. Мирошниченко", fit("Сергей Вишневский", "Александр Мирошниченко", 380));
        assertEquals("А. Скидановский | Рэдрик Незвучный", fit("Александр Скидановский", "Рэдрик Незвучный", 360));
        // both fit either way: the author, longer in full, gives way although its initials save more
        assertArrayEquals(new int[]{4, 0, 160, 180},
                NameFit.choose(new int[]{220, -1, -1, -1, 80}, new int[]{180, -1, -1, -1, 120}, 340));
    }

    @Test
    public void aStepMadeNeedlessByALaterOneIsUndone() {
        // the reader's middle step, then the author's initials: the author's alone are enough
        assertArrayEquals(new int[]{4, 0, 160, 180},
                NameFit.choose(new int[]{220, -1, -1, 200, 80}, new int[]{180, -1, -1, 170, 120}, 340));
    }

    @Test
    public void aWiderSpellingIsNeverTaken() {
        // "J Smith" → "J. Smith" is no shorter: both are cut at half instead
        assertArrayEquals(new int[]{0, 0, 25, 25}, NameFit.choose(new int[]{30, -1, -1, -1, 40}, new int[]{30}, 50));
    }

    @Test
    public void theShorterStaysWholeWhenNothingFits() {
        assertEquals("Мира Вишес | Е. Пушкарёва-Иванова-Сидорова~180",
                fit("Мира Вишес", "Екатерина Пушкарёва-Иванова-Сидорова", 300));
    }

    @Test
    public void bothAreCutAtHalfWhenBothAreLong() {
        assertEquals("Н. Норткот-Длиннофамильнов~150 | Т. Ибраев-Длиннофамильнов~150",
                fit("Николай Норткот-Длиннофамильнов", "Тимур Ибраев-Длиннофамильнов", 300));
    }

    @Test
    public void anyFieldIsSafe() {
        Random random = new Random(1);
        String chars = "абвгдеёжзийклмнопрстуфхэюяАБВГДЕЖЗИКЛМНОПРСТУЭЮЯabcXYZ.,;/&-()[]«»\"' 0123456789и ";
        for (int t = 0; t < 20_000; t++) {
            StringBuilder field = new StringBuilder();
            for (int k = random.nextInt(40); k > 0; k--) {
                field.append(chars.charAt(random.nextInt(chars.length())));
            }
            String[][] v = NameFit.variants(field.toString(), "Иван Петров");
            if (v[0] != null) {
                for (String s : v[0]) {
                    assertFalse(field.toString(), s != null && s.trim().isEmpty());
                }
                NameFit.choose(widths(v[0]), widths(v[1]), random.nextInt(700));
            }
        }
    }

    @Test
    public void aLoneNameTakesTheRoom() {
        int[] name = widths(NameFit.variants("Иван Петров", null)[0]);
        assertArrayEquals(new int[]{0, -1, 300, 0}, NameFit.choose(name, null, 300));
        assertArrayEquals(new int[]{-1, 0, 170, 130}, NameFit.choose(null, name, 300));
        assertArrayEquals(new int[]{-1, -1, 300, 0}, NameFit.choose(null, null, 300));
    }
}
