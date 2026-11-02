package com.fanok.audiobooks.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;

/** Times and dates the Russian way, whatever the device's language. */
public class RussianDatesTest {

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, hour, minute);
        return calendar.getTimeInMillis();
    }

    @Test
    public void aTimeHasTwentyFourHoursAndADateIsDayMonthYear() {
        long evening = at(2026, Calendar.OCTOBER, 31, 21, 35);
        assertEquals("21:35", RussianDates.time(evening));
        assertEquals("31.10.2026", RussianDates.date(evening));
        assertEquals("31 окт., 21:35", RussianDates.dayAndTime(evening));
        assertEquals("02:48", RussianDates.time(at(2026, Calendar.NOVEMBER, 2, 2, 48)));
    }
}
