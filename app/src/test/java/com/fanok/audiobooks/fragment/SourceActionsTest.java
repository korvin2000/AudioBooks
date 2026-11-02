package com.fanok.audiobooks.fragment;

import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceSnapshot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The snackbar's text after a change: what else crossed the line, and a catalog source that moved. */
public class SourceActionsTest {

    private static final SourceActions.Words WORDS = new SourceActions.Words("«%1$s» стал активным",
            "«%1$s» стал неактивным", "Источник каталога — %1$s");

    private static SourceSnapshot setup(List<Provider> order, int active, Provider catalog) {
        return new SourceSnapshot(order, active, catalog.prefValue, Collections.<String>emptySet());
    }

    @Test
    public void switchedOnAtTheLimitTheLastActiveLeaves() {
        SourceSnapshot before = setup(Arrays.asList(Provider.AKNIGA, Provider.AUDIOKNIGA_LIFE, Provider.GOLOSOM), 2,
                Provider.AKNIGA);
        SourceSnapshot after = setup(Arrays.asList(Provider.AKNIGA, Provider.GOLOSOM, Provider.AUDIOKNIGA_LIFE), 2,
                Provider.AKNIGA);
        assertEquals("«Голосом» включён · «Аудиокнига Life» стал неактивным",
                SourceActions.describe("«Голосом» включён", before, after, Provider.GOLOSOM, WORDS));
    }

    @Test
    public void theCatalogSourceSwitchedOffMovesToTheFirstActive() {
        SourceSnapshot before = setup(Arrays.asList(Provider.AKNIGA, Provider.BIBLUS, Provider.OTRUB), 2,
                Provider.BIBLUS);
        SourceSnapshot after = setup(Arrays.asList(Provider.AKNIGA, Provider.BIBLUS, Provider.OTRUB), 1,
                Provider.AKNIGA);
        assertEquals("«Библус» выключен · Источник каталога — Akniga",
                SourceActions.describe("«Библус» выключен", before, after, Provider.BIBLUS, WORDS));
    }

    @Test
    public void theSubjectsOwnChangesAreItsHeadsAlone() {
        SourceSnapshot before = setup(Arrays.asList(Provider.AKNIGA, Provider.BIBLUS), 1, Provider.AKNIGA);
        SourceSnapshot after = setup(Arrays.asList(Provider.BIBLUS, Provider.AKNIGA), 1, Provider.BIBLUS);
        assertEquals("Источник каталога — Библус · «Akniga» стал неактивным",
                SourceActions.describe("Источник каталога — Библус", before, after, Provider.BIBLUS, WORDS));
        assertEquals("nothing else changed", "«Akniga»: напрямую",
                SourceActions.describe("«Akniga»: напрямую", before, before, Provider.AKNIGA, WORDS));
    }
}
