package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Playerjs {@code #2} with the player's {@code "//" + base64(key)} pieces, as audiomir.xyz and aukniga.com serve it. */
public class PlayerjsKeyedTest {

    private static final String[] AUDIOMIR = {"//TTNrRzhyVDZ5Sjl4QnY=", "//QjdkSDRqUDFxVDlzVnc=",
            "//TDduWDJ3WjV0UTFZcA==", "//UjV2UThtTmM3WnAyTHhB", "//RjJnNlQ5Ylk0blgzSGo="};

    @Test
    public void piecesAreCutOutEvenWhenOneSitsInsideAnother() {
        // the last two pieces are nested: "/" + "//UjV2…" + "/RjJn…" (live page, 2026-11-21)
        String file = "#2aHR0cH//TTNrRzhyVDZ5Sjl4QnY=M6Ly9rMjAuYXVia3Mub3JnL2Jvb2tzL2ZhbnRhc3//QjdkSDRqUDFxVDlzVnc="
                + "Rpa2Evbmlrb2xheS1za2liYS1lZ2VyL//TDduWDJ3WjV0UTFZcA==XNlcmRjZS1zdGFpLWtuaWdhLTUvbmlrb2xheS1za2liYS1l"
                + "Z2VyLXNlcmRjZS1zdGFpLWtua///UjV2UThtTmM3WnAyTHhB/RjJnNlQ5Ylk0blgzSGo=WdhLTUudHh0";
        assertEquals("https://k20.aubks.org/books/fantastika/nikolay-skiba-eger-serdce-stai-kniga-5/"
                + "nikolay-skiba-eger-serdce-stai-kniga-5.txt", Players.playerjsKeyed(file, AUDIOMIR));
    }

    @Test
    public void unknownKeysLeaveTextThatIsNoUrl() {
        String file = "#2aHR0cH//TTNrRzhyVDZ5Sjl4QnY=M6Ly9rMjAuYXVia3Mub3Jn";
        assertEquals("https://k20.aubks.org", Players.playerjsKeyed(file, AUDIOMIR));
        assertTrue(!Players.playerjsKeyed(file, "//QjdkSDRqUDFxVDlzVnc=").startsWith("https://"));
    }
}
