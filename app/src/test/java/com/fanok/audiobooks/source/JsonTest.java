package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.gson.JsonArray;
import org.junit.Test;

/** Json reads a player's JSON as the player does: JS-only escapes as JS reads them, nothing else "repaired". */
public class JsonTest {

    @Test
    public void jsOnlyEscapesReadAsJs() {
        // audiobook-mp3's Playerjs playlist: "Черный список_ \(01\)" is a JS string literal, not JSON
        JsonArray items = Json.array("[{\"title\":\"Черный список_ \\(01\\)\"}]");
        assertEquals("Черный список_ (01)", Json.string(items.get(0).getAsJsonObject(), "title"));
    }

    @Test
    public void escapedBackslashStays() {
        // the second item fails the strict parse; the first keeps its escaped backslash in the fallback
        JsonArray items = Json.array("[\"a\\\\(b\",\"\\(\"]");
        assertEquals("a\\(b", Json.string(items.get(0)));
        assertEquals("(", Json.string(items.get(1)));
        assertEquals("[\"a\\\\(b\",\"(\"]", Json.jsEscapes("[\"a\\\\(b\",\"\\(\"]"));
    }

    @Test
    public void validJsonIsReadAsBefore() {
        assertEquals("a/b\n\"c\"", Json.string(Json.array("[\"a\\/b\\n\\\"c\\\"\"]").get(0)));
    }

    @Test
    public void truncatedStaysMalformed() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Json.parse("[{\"title\":\"a\""));
        assertEquals("malformed JSON", e.getMessage());
        // JS escapes do not make a cut document whole either
        assertThrows(IllegalStateException.class, () -> Json.parse("[{\"title\":\"\\(01\\)\""));
    }
}
