package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Test;

/** {@link Html#paragraphs}: a description keeps its paragraphs, one a line, and says what {@code text()} says. */
public class HtmlParagraphsTest {

    private static Element body(String html) {
        return Jsoup.parseBodyFragment(html).body();
    }

    @Test
    public void breaksAndBlocksEndTheLine() {
        assertEquals("Первый.\nВторой.\nТретий, с ссылкой.\nЧетвёртый.",
                Html.paragraphs(body("Первый.<br><br><b>Второй.</b><p>Третий, <a href='/x'>с ссылкой</a>.</p>"
                        + "<div>Четвёртый.</div>")));
    }

    @Test
    public void aLineBreakOfTheMarkupIsASpace() {
        assertEquals("Одна строка, записанная в две.\nДругая.",
                Html.paragraphs(body("<p>Одна строка,\n   записанная в две.</p>\n<p>Другая.</p>\n")));
    }

    @Test
    public void emptyLinesAndOuterSpacesAreDropped() {
        assertEquals("Текст.\nЕщё текст.",
                Html.paragraphs(body("<p>&nbsp;</p><p> Текст.&nbsp;</p><br> <br><p></p><div>  Ещё&nbsp;&nbsp;текст. </div>")));
    }

    @Test
    public void inlineMarkupAndScriptsAddNothing() {
        assertEquals("Жирный и курсив рядом.",
                Html.paragraphs(body("<b>Жирный</b> и <i>курсив</i> <span>рядом</span>.<script>var a = 1;</script>"
                        + "<style>p {}</style>")));
    }

    @Test
    public void theWordsAreThoseOfText() {
        Element body = body("<h3>Заголовок</h3><ul><li>раз</li><li>два</li></ul>Хвост<br>и ещё <b>хвост</b>");
        assertEquals(body.text(), Html.paragraphs(body).replace('\n', ' '));
    }

    @Test
    public void invisibleCharactersAreLeftOutAsTextDoes() {
        Element body = body("<p>достав­лена ​​в срок</p>");
        assertEquals("доставлена в срок", Html.paragraphs(body));
        assertEquals(body.text(), Html.paragraphs(body));
    }

    @Test
    public void aPreKeepsItsLinesAndDeepNestingIsNoRecursion() {
        assertEquals("первая\nвторая", Html.paragraphs(body("<pre>первая\n  вторая</pre>")));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 20000; i++) { // a walk that calls itself for every level would run out of stack
            deep.append("<span>");
        }
        assertEquals("дно", Html.paragraphs(body(deep + "дно")));
    }

    @Test
    public void ownParagraphsLeaveChildElementsOut() {
        Element about = body("<div id='a'><b>Описание:</b> Первая строка.<br>Вторая строка. <a href='/x'>Читать</a></div>")
                .getElementById("a");
        assertEquals("Первая строка.\nВторая строка.", Html.ownParagraphs(about));
        assertEquals(about.ownText(), Html.ownParagraphs(about).replace('\n', ' '));
    }

    @Test
    public void aFragmentOfAJsonFieldAndNothing() {
        assertEquals("Первый.\nВторой.", Html.paragraphs("<p>Первый.</p>\n<p>Второй.</p>"));
        assertEquals("Просто текст.", Html.paragraphs(" Просто текст. "));
        assertEquals("", Html.paragraphs((String) null));
        assertEquals("", Html.paragraphs((Element) null));
        assertEquals("", Html.ownParagraphs(null));
    }
}
