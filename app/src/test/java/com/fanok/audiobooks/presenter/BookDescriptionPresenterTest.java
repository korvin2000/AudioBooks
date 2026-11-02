package com.fanok.audiobooks.presenter;

import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.R;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.jsoup.HttpStatusException;
import org.junit.Test;

public class BookDescriptionPresenterTest {

    @Test
    public void loadErrorsAreToldApart() {
        assertEquals(R.string.site_challenge,
                BookDescriptionPresenter.errorMessage(new CookesExeption("https://izib.uk/art1")));
        assertEquals(R.string.cookes_baza_knig_exeption,
                BookDescriptionPresenter.errorMessage(new CookesExeption("https://baza-knig.top/a/1.html")));
        assertEquals(R.string.error_load_data,
                BookDescriptionPresenter.errorMessage(new HttpStatusException("HTTP error", 503, "https://izib.uk")));
        // no answer in time is told apart from an HTTP error (was error_load_data), also when jsoup wraps it
        assertEquals(R.string.error_site_no_answer,
                BookDescriptionPresenter.errorMessage(new SocketTimeoutException()));
        assertEquals(R.string.error_site_no_answer,
                BookDescriptionPresenter.errorMessage(new UncheckedIOException(new SocketException("reset"))));
        assertEquals(R.string.error_site_unreachable,
                BookDescriptionPresenter.errorMessage(new UnknownHostException("akniga.org")));
        assertEquals(R.string.error_site_unreachable,
                BookDescriptionPresenter.errorMessage(new java.net.ConnectException("refused")));
        assertEquals(R.string.error_load_data,
                BookDescriptionPresenter.errorMessage(new IOException("no book on page")));
        assertEquals(R.string.worong_data_sorce,
                BookDescriptionPresenter.errorMessage(new IllegalArgumentException("unsupported host: x")));
        assertEquals(R.string.error_site_changed, BookDescriptionPresenter.errorMessage(
                new IllegalStateException("no playable chapters in akniga book (0 skipped)")));
        assertEquals(R.string.error_site_changed,
                BookDescriptionPresenter.errorMessage(new IllegalStateException("malformed JSON")));
        assertEquals(R.string.book_taken_down,
                BookDescriptionPresenter.errorMessage(new BookRemovedException("https://poleknig.com/books/1")));
        assertEquals(R.string.error_load_data,
                BookDescriptionPresenter.errorMessage(new UncheckedIOException(new IOException("cut body"))));
        // nothing was asked: the device is offline
        assertEquals(R.string.error_no_network, BookDescriptionPresenter.errorMessage(null));
        assertEquals(R.string.error_display_data,
                BookDescriptionPresenter.errorMessage(new NullPointerException()));
    }
}
