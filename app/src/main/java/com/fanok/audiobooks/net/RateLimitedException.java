package com.fanok.audiobooks.net;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import org.jsoup.HttpStatusException;

/**
 * HTTP 429 "Too Many Requests": the site limits how often it may be asked. It is no block and no fault of the page: the
 * same request goes through later, at {@link #retryAfterSeconds} when the site said when (its {@code Retry-After}).
 */
public final class RateLimitedException extends HttpStatusException {

    public static final int STATUS = 429;
    /** A longer wait the site asks for is cut to this: a mistaken header must not shut a site off for a day. */
    static final int MAX_RETRY_AFTER_SECONDS = 3600;

    /** The seconds the site asked to wait; 0 when it did not say. */
    public final int retryAfterSeconds;

    public RateLimitedException(String url, int retryAfterSeconds) {
        super("Too many requests" + (retryAfterSeconds > 0 ? ", retry after " + retryAfterSeconds + " s" : ""),
                STATUS, url);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    /**
     * Pure: the seconds of a {@code Retry-After} value at {@code nowMs}, either a number of seconds or an HTTP date
     * (RFC 9110 §10.2.3), cut to {@link #MAX_RETRY_AFTER_SECONDS}; 0 when absent, past or unreadable.
     */
    public static int retryAfterSeconds(String header, long nowMs) {
        if (header == null || header.trim().isEmpty()) {
            return 0;
        }
        String value = header.trim();
        long seconds;
        try {
            seconds = Long.parseLong(value);
        } catch (NumberFormatException notSeconds) {
            SimpleDateFormat http = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            http.setTimeZone(TimeZone.getTimeZone("GMT"));
            try {
                seconds = (http.parse(value).getTime() - nowMs + 999) / 1000;
            } catch (ParseException unreadable) {
                return 0;
            }
        }
        return (int) Math.max(0, Math.min(MAX_RETRY_AFTER_SECONDS, seconds));
    }
}
