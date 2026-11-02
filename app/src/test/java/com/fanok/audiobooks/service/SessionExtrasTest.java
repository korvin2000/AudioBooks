package com.fanok.audiobooks.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SessionExtrasTest {

    private static final String URL = "https://knigavuhe.org/book/master-i-margarita/";

    @Test
    public void bookKey_isStableAndDiffersBetweenBooks() {
        //the same key across app versions: the launcher keeps the book's chapter lengths under it
        assertEquals("e6cfb749-2192-3189-8417-631a64e90b70", SessionExtras.bookKey(URL));
        assertNotEquals(SessionExtras.bookKey(URL), SessionExtras.bookKey(URL + "?page=2"));
    }

    @Test
    public void bookKey_hidesTheUrl() {
        assertFalse(SessionExtras.bookKey(URL).contains("knigavuhe"));
    }

    @Test
    public void bookKey_noUrlNoKey() {
        assertNull(SessionExtras.bookKey(null));
        assertNull(SessionExtras.bookKey(""));
    }
}
