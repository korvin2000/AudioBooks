package com.fanok.audiobooks.search;

import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.net.RateLimitedException;

import org.jsoup.HttpStatusException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * One page of one provider: the parsed items, whether the provider has a next page, and a status.
 * A failure is a value too, so the UI never needs sentinel rows and a parser never has to signal
 * "end of list" with an exception.
 */
public final class ProviderPage<T> {

    /** {@code RATE_LIMITED}: HTTP 429, the site asks to be asked less often ({@link #retryAfterSeconds}). */
    public enum Status { OK, EMPTY, ERROR, TIMEOUT, BLOCKED, RATE_LIMITED }

    public final List<T> items;
    public final boolean hasMore;
    public final Status status;
    /** Diagnostic text for a failure; null otherwise. */
    public final String message;
    /** {@link Status#RATE_LIMITED}: the seconds the site asked to wait ({@code Retry-After}); 0 when it did not say. */
    public final int retryAfterSeconds;
    /**
     * {@link Status#BLOCKED} by a challenge page ({@link CookesExeption}), which a browser may pass; a plain 403/503
     * (an outage, a refusal) is BLOCKED without it.
     */
    public final boolean challenge;
    /** With {@link #challenge}: the page the site refused, where its check is to be passed; else null. */
    public final String challengedUrl;

    private ProviderPage(List<T> items, boolean hasMore, Status status, String message) {
        this(items, hasMore, status, message, 0, null);
    }

    private ProviderPage(List<T> items, boolean hasMore, Status status, String message, int retryAfterSeconds,
            String challengedUrl) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.hasMore = hasMore;
        this.status = status;
        this.message = message;
        this.retryAfterSeconds = retryAfterSeconds;
        this.challengedUrl = challengedUrl;
        this.challenge = challengedUrl != null;
    }

    /** A successfully parsed page; an empty page is {@link Status#EMPTY} and never has more. */
    public static <T> ProviderPage<T> of(List<T> items, boolean hasMore) {
        return of(items, hasMore, items.size());
    }

    /**
     * A successfully parsed page of {@code rawCards} cards, of which {@code items} survived the parser's
     * filters. Only a page without any card is {@link Status#EMPTY} with no more pages; a page whose cards
     * were all filtered out keeps the provider's own next-page evidence.
     */
    public static <T> ProviderPage<T> of(List<T> items, boolean hasMore, int rawCards) {
        if (rawCards <= 0 && items.isEmpty()) {
            return new ProviderPage<>(items, false, Status.EMPTY, null);
        }
        return new ProviderPage<>(items, hasMore, Status.OK, null);
    }

    public static <T> ProviderPage<T> empty() {
        return new ProviderPage<>(Collections.<T>emptyList(), false, Status.EMPTY, null);
    }

    /**
     * Classifies a failure: a challenge ({@link #challenge}) or 403/503 → BLOCKED, 429 → RATE_LIMITED (with its
     * wait), a timeout → TIMEOUT, anything else → ERROR.
     */
    public static <T> ProviderPage<T> failed(Throwable e) {
        Status status = Status.ERROR;
        int retryAfter = 0;
        if (e instanceof CookesExeption) {
            status = Status.BLOCKED;
        } else if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
            status = Status.TIMEOUT;
        } else if (e instanceof HttpStatusException) {
            int code = ((HttpStatusException) e).getStatusCode();
            if (code == RateLimitedException.STATUS) {
                status = Status.RATE_LIMITED;
                retryAfter = e instanceof RateLimitedException ? ((RateLimitedException) e).retryAfterSeconds : 0;
            } else if (code == 403 || code == 503) {
                status = Status.BLOCKED;
            }
        }
        return new ProviderPage<>(Collections.<T>emptyList(), false, status,
                e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), retryAfter,
                e instanceof CookesExeption ? String.valueOf(e.getMessage()) : null); // SiteHttp: the page's URL
    }

    public boolean isFailure() {
        return isFailure(status);
    }

    /** This page's status, message and wait without its items, as a page of another kind (a failed step of a load). */
    public <U> ProviderPage<U> asFailure() {
        return new ProviderPage<>(Collections.<U>emptyList(), false, status, message, retryAfterSeconds, challengedUrl);
    }

    /** A failed answer: the page did not come (an error, a timeout, a block, a rate limit). */
    public static boolean isFailure(Status status) {
        return status == Status.ERROR || status == Status.TIMEOUT || status == Status.BLOCKED
                || status == Status.RATE_LIMITED;
    }
}
