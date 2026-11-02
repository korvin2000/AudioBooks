package com.fanok.audiobooks.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.adapter.SourcesAdapter.Header;
import com.fanok.audiobooks.adapter.SourcesAdapter.Order;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SourceProbe;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The rows of the source list: the user's order in two sections, or one list sorted by books or by response. */
public class SourcesAdapterTest {

    private static final Provider A = Provider.AKNIGA;
    private static final Provider B = Provider.KNIGAVUHE;
    private static final Provider C = Provider.IZIBUK;
    private static final Provider D = Provider.ABMP3;
    private static final List<Provider> ORDER = Arrays.asList(A, B, C, D);

    private static SourceProbe.Result result(Provider provider, ProviderPage.Status status, long millis) {
        return new SourceProbe.Result(provider, status, millis, null);
    }

    @Test
    public void theUsersOrderHasASectionForTheActiveAndOneForTheInactive() {
        assertEquals(Arrays.<Object>asList(new Header(true, 2), A, B, new Header(false, 2), C, D),
                SourcesAdapter.rows(ORDER, 2, Order.USER, Collections.<Provider, Integer>emptyMap(),
                        Collections.<Provider, SourceProbe.Result>emptyMap()));
        assertEquals("every site active: no second section", Arrays.<Object>asList(new Header(true, 4), A, B, C, D),
                SourcesAdapter.rows(ORDER, 4, Order.USER, Collections.<Provider, Integer>emptyMap(),
                        Collections.<Provider, SourceProbe.Result>emptyMap()));
    }

    @Test
    public void byBooksTheLargestFirstTheUnknownLastTiesInTheUsersOrder() {
        Map<Provider, Integer> books = new EnumMap<>(Provider.class);
        books.put(B, 5000);
        books.put(C, 90000);
        books.put(D, 5000);
        assertEquals(Arrays.<Object>asList(C, B, D, A), SourcesAdapter.rows(ORDER, 2, Order.BOOKS, books,
                Collections.<Provider, SourceProbe.Result>emptyMap()));
    }

    @Test
    public void byResponseTheFastestFirstThenTheFailedThenTheUnknown() {
        Map<Provider, SourceProbe.Result> results = new EnumMap<>(Provider.class);
        results.put(A, result(A, ProviderPage.Status.BLOCKED, 300));
        results.put(B, result(B, ProviderPage.Status.OK, 1400));
        results.put(D, result(D, ProviderPage.Status.OK, 600));
        assertEquals(Arrays.<Object>asList(D, B, A, C), SourcesAdapter.rows(ORDER, 2, Order.RESPONSE,
                Collections.<Provider, Integer>emptyMap(), results));
    }

    @Test
    public void anAnswerWithoutTimeComesAfterTheTimedOnesAndBeforeAFailure() {
        long timed = SourcesAdapter.responseKey(result(A, ProviderPage.Status.OK, 900));
        long untimed = SourcesAdapter.responseKey(result(A, ProviderPage.Status.OK, 0));
        long failed = SourcesAdapter.responseKey(result(A, ProviderPage.Status.TIMEOUT, 15000));
        long unknown = SourcesAdapter.responseKey(null);
        assertTrue(timed < untimed);
        assertTrue(untimed < failed);
        assertTrue(failed < unknown);
    }
}
