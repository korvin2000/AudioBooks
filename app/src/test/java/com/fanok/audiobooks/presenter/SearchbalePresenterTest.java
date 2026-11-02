package com.fanok.audiobooks.presenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.search.SearchOrder;
import com.fanok.audiobooks.search.SearchRequest.Field;
import java.util.EnumSet;
import org.junit.Test;

/** The presenter's pure parts; the screen's logic is SearchUi's (SearchUiTest). */
public class SearchbalePresenterTest {

    @Test
    public void notFoundOnlyWhenFinishedEmptyAndNoSourceFailed() {
        assertTrue(SearchbalePresenter.isNotFound(true, 0, true));
        assertFalse("a failed source may hold the results", SearchbalePresenter.isNotFound(true, 1, true));
        assertFalse(SearchbalePresenter.isNotFound(false, 0, true));
        assertFalse(SearchbalePresenter.isNotFound(true, 0, false));
    }

    @Test
    public void storedOrderAndScopeSurviveUnknownValues() {
        assertEquals(SearchOrder.Sort.TITLE, SearchbalePresenter.sortOf("TITLE"));
        assertEquals(SearchOrder.Sort.RELEVANCE, SearchbalePresenter.sortOf("FROM_A_LATER_VERSION"));
        assertEquals(SearchOrder.Sort.RELEVANCE, SearchbalePresenter.sortOf(null));
        assertEquals(EnumSet.of(Field.AUTHOR, Field.READER), SearchbalePresenter.scopeOf("AUTHOR, READER,LATER"));
        assertTrue(SearchbalePresenter.scopeOf("").isEmpty());
        assertTrue(SearchbalePresenter.scopeOf(null).isEmpty());
    }
    @Test
    public void storedAnswersReadBackAndBrokenOnesAreSkipped() {
        java.util.Map<String, long[]> a = SearchbalePresenter.answers("AKNIGA:1:5, BAD,IZIBUK:0:7,X:y:1");
        assertEquals(2, a.size());
        assertEquals(1, a.get("AKNIGA")[0]);
        assertEquals(7, a.get("IZIBUK")[1]);
        assertTrue(SearchbalePresenter.answers(null).isEmpty());
    }
}
