package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.AudioPOJO;
import com.google.gson.JsonPrimitive;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.util.List;

/** akniga's encrypted stream URL ({@code hres} of {@code /ajax/b/<bid>}) and the passphrases the site moves between. */
public class AknigaHresTest {

    private static final String STREAM = "https://r1.akniga.club/b/1/pl.m3u8?res=x&expires=1";

    @Test
    public void liveAnswerOfTheDayTheSiteChangedThePassphrase() {
        // /ajax/b/114125 on 2026-11-23, when the passphrase of the years before stopped decrypting
        String hres = "{\"ct\":\"CfrpDZokrD8IhKAqR\\/AXZo\\/CPibSmUAtbXahehf895CrNTF3\\/rB9p0kkuXjP5FAqiBiiKTucvJyVud0HAILg"
                + "lQ5xdbA+4eoJOfHu448PHxzpi0SuP+66jPPx3mn760ji\",\"iv\":\"df3356d0aabdccbfefaf61fe96dba9cb\","
                + "\"s\":\"feae7960aae2235b\"}";
        String url = Akniga.decryptHres(hres);
        assertTrue(url, url.startsWith("https://r1.akniga.club/b/114125/pl.m3u8?res="));
    }

    @Test
    public void everyPassphraseOfThePlayerDecrypts() {
        // STREAM encrypted in the site's page by its own CryptoJS with plh.assets3(), assets2() and assets()
        String[] byThePlayer = {
                "{\"ct\":\"GxFXf2GjmMM/ezZrN83PRML2K4nAegROIM5ney/E1A23mjhr+5GVjsXKCr5Ap5KKgaqXRIvmm96OCIyBbPzxaA==\","
                        + "\"iv\":\"b8f68efb9740f999eea53662977d6640\",\"s\":\"987b3f8a758800d9\"}",
                "{\"ct\":\"uzLuuvmDAuHzdekDW/j5tFfTkufdB1LLU/W5uY5Ql2082umhTozpLEyci7yI7PxyY0nS7ppnM4YU8DztViXcbA==\","
                        + "\"iv\":\"de13729e00d509336df94064997c3072\",\"s\":\"fe8ce8dc28c545ea\"}",
                "{\"ct\":\"n/+c8vemgt3BREgBg6TyQp0amfgdkswVobxgm9tJlU4rd3kLxaBaEaSC6OpdkFEoEWPdAv9p9EVywJxRUHVG8A==\","
                        + "\"iv\":\"54576c3106f6daaf251b4e1bc3de80b6\",\"s\":\"81bbeb55dbad29f2\"}"};
        assertEquals(byThePlayer.length, Akniga.PASSPHRASES.length);
        for (String hres : byThePlayer) {
            assertEquals(STREAM, Akniga.decryptHres(hres));
        }
        assertEquals(STREAM, Akniga.decryptHres(
                CryptoJs.encrypt('"' + STREAM.substring("https:".length()) + '"', Akniga.PASSPHRASES[2])));
    }

    @Test
    public void passphraseThePlayerWasSeenToUseOpensTheBooksAfter() {
        String hres = CryptoJs.encrypt('"' + STREAM + '"', "the player's new one");
        assertEquals("", Akniga.decryptHres(hres));
        // evaluateJavascript hands the answer over as a JSON string
        String answer = new JsonPrimitive("{\"url\":\"" + STREAM + "\",\"keys\":[\"" + Akniga.PASSPHRASES[1]
                + "\",\"the player's new one\"]}").toString();
        assertEquals(STREAM, Akniga.learn(answer));
        assertEquals(STREAM, Akniga.decryptHres(hres));
        // a player that made no stream of it and used nothing leaves what was learned
        assertEquals("", Akniga.learn(new JsonPrimitive("{\"url\":null,\"keys\":[]}").toString()));
        assertEquals(STREAM, Akniga.decryptHres(hres));
    }

    @Test
    public void streamOfThePlayerServesWhenNoPassphraseHereOpensTheAnswer() {
        String book = "{\"hres\":" + new JsonPrimitive(CryptoJs.encrypt('"' + STREAM + '"', "known to nobody here"))
                + ",\"items\":\"[{\\\"title\\\":\\\"1\\\",\\\"time_from_start\\\":0,\\\"time_finish\\\":60}]\"}";
        List<AudioPOJO> chapters = Akniga.parseTracks(Jsoup.parse(""), book, "https://akniga.org/b", STREAM);
        assertEquals(1, chapters.size());
        assertEquals(STREAM, chapters.get(0).getUrl());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Akniga.parseTracks(Jsoup.parse(""), book, "https://akniga.org/b"));
        assertTrue(e.getMessage(), e.getMessage().contains("hres not decryptable"));
    }

    @Test
    public void anotherPassphraseOrSomethingElseThanAUrlIsNoStream() {
        assertEquals("", Akniga.decryptHres(CryptoJs.encrypt('"' + STREAM + '"', "a passphrase of tomorrow")));
        assertEquals("", Akniga.decryptHres(CryptoJs.encrypt("\"Pls try again.\"", Akniga.PASSPHRASES[0])));
        assertEquals("", Akniga.decryptHres(CryptoJs.encrypt("\"https://:bad\"", Akniga.PASSPHRASES[0])));
        assertEquals("", Akniga.decryptHres("not json"));
    }
}
