package com.fanok.audiobooks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.R;
import org.junit.Test;

public class HistoryFilterTest {
    private static final long DAY = 86_400_000L;
    /** 2 November 2026, 12:00 UTC. */
    private static final long NOW = 1_793_620_800_000L;

    @Test
    public void statusFollowsThePercent() {
        assertEquals(HistoryFilter.STATUS_NOT_STARTED, HistoryFilter.statusOf(-1));
        assertEquals(HistoryFilter.STATUS_NOT_STARTED, HistoryFilter.statusOf(0));
        assertEquals(HistoryFilter.STATUS_LISTENING, HistoryFilter.statusOf(1));
        assertEquals(HistoryFilter.STATUS_LISTENING, HistoryFilter.statusOf(99));
        assertEquals(HistoryFilter.STATUS_FINISHED, HistoryFilter.statusOf(100));
        assertEquals(HistoryFilter.STATUS_FINISHED, HistoryFilter.statusOf(101));
    }

    @Test
    public void periodsAreSlidingWindows() {
        assertEquals(NOW - DAY, HistoryFilter.since(HistoryFilter.PERIOD_DAY, NOW));
        assertEquals(NOW - 7 * DAY, HistoryFilter.since(HistoryFilter.PERIOD_WEEK, NOW));
        assertEquals(NOW - 30 * DAY, HistoryFilter.since(HistoryFilter.PERIOD_MONTH, NOW));
        assertEquals(NOW - 365 * DAY, HistoryFilter.since(HistoryFilter.PERIOD_YEAR, NOW));
    }

    @Test
    public void undatedRowsOnlyInAllTheTime() {
        // rows from before the update and restored ones have opened_at = 0
        assertTrue(HistoryFilter.since(HistoryFilter.PERIOD_ALL, NOW) <= 0);
        assertTrue(HistoryFilter.since(HistoryFilter.PERIOD_YEAR, NOW) > 0);
        assertTrue(HistoryFilter.since(HistoryFilter.PERIOD_DAY, NOW) > 0);
    }

    @Test
    public void menuItemsMapToPeriods() {
        assertEquals(HistoryFilter.PERIOD_TITLES.length, HistoryFilter.PERIOD_ITEMS.length);
        assertEquals(4, HistoryFilter.STATUS_TITLES.length);
        assertEquals(HistoryFilter.PERIOD_ALL, HistoryFilter.periodOf(R.id.history_period_all));
        assertEquals(HistoryFilter.PERIOD_WEEK, HistoryFilter.periodOf(R.id.history_period_week));
        assertEquals(HistoryFilter.PERIOD_DAY, HistoryFilter.periodOf(R.id.history_period_day));
        assertEquals(-1, HistoryFilter.periodOf(R.id.clear_list));
    }
}
