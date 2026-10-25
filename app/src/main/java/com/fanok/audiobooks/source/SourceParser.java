package com.fanok.audiobooks.source;

import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.ComentsPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.OtherArtistPOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import io.reactivex.functions.BooleanSupplier;

import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * Everything the app asks a site for; one stateless instance per {@link com.fanok.audiobooks.search.Provider} row.
 * The models fetch a page through {@link #fetch} and hand the {@link Document} over, so a site's parsing stays in
 * pure static functions that fixtures can test; only {@link #tracks}, {@link #series}, {@link #comments} and
 * {@link #otherReaders} may fetch more themselves. A default means "the site has no such feature".
 */
public interface SourceParser {

    /** GET through {@link SiteHttp}; override to POST ({@link Dle#fetch}) or to add headers. */
    default Document fetch(String url) throws IOException {
        return SiteHttp.get(url);
    }

    /** The Referer sent with every request to the site's hosts (covers); null for none. */
    default String referer() {
        return null;
    }

    // ---------------------------------------------------------------- listings

    /** One page of the listing at {@code url}: the index, a genre, an author/reader/series page or a {@link #sortUrl}. */
    ProviderPage<BookPOJO> listing(Document page, String url, int pageNo);

    /** The URL of page {@code page} of the listing {@code url} in the site's own scheme; page 1 is the listing itself. */
    String pageUrl(String url, int page);

    /** The options menu of a book listing; its sort items are the ids {@link #sortUrl} answers. */
    int optionsMenu();

    /**
     * The site-wide listing for the sort item {@code itemId} ({@code R.id.new_data}, {@code popular*}, {@code reting*},
     * {@code coments}, {@code years}, {@code sort_title}, {@code sort_duration}, ...), as {@link #pageUrl} takes it;
     * {@code listingUrl} itself when it already is that listing; null when the item is no sort of this site.
     */
    String sortUrl(int itemId, String listingUrl);

    /** False for a listing that cannot be re-sorted (an author, reader or series page): hides the Order button. */
    default boolean hasSort(String url) {
        return true;
    }

    /** True when the cards of the listing at {@code url} carry a duration, so the duration filter can work. */
    default boolean listsDuration(String url) {
        return true;
    }

    /** The genre key of a genre listing or of a sort opened from one (what {@link #inGenre} takes); null elsewhere. */
    default String genreOf(String url) {
        return null;
    }

    /** A {@link #sortUrl} result narrowed to {@code genre}; by default the site has no per-genre sorts. */
    default String inGenre(String siteWide, String genre) {
        return siteWide;
    }

    // ---------------------------------------------------------------- sections

    /** The genre list at {@code url} ({@code Provider.sections}). */
    ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo);

    /** One page of the authors or readers list ({@code Provider.authors} / {@code performers}). */
    default ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers) {
        return ProviderPage.empty();
    }

    // ---------------------------------------------------------------- search

    /**
     * Page {@code page} of the {@code category} search for the raw {@code query} (encode it once, see
     * {@link SearchUrls#enc}); null when the site has no such search. {@code BOOKS} is never null.
     */
    String searchUrl(SearchUrls.Category category, String query, int page);

    /** One page of book search results. */
    ProviderPage<BookPOJO> searchResults(Document page, int pageNo);

    /** One page of author or reader search results. */
    default ProviderPage<GenrePOJO> searchPeople(Document page, int pageNo, boolean performers) {
        return ProviderPage.empty();
    }

    // ---------------------------------------------------------------- book page

    /** The book page; the model rejects an empty title as "no book on page". */
    DescriptionPOJO description(Document page, String bookUrl);

    /** The card of a deep-linked book; the model rejects {@link BookPOJO#isNull()}. */
    BookPOJO book(Document page, String bookUrl);

    /** The "other books" block of the page. */
    default ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return new ArrayList<>();
    }

    /**
     * The chapters; {@code base} is the page's final URL, which relative media URLs resolve against. May fetch more
     * (a playlist). Never empty: a page without a player is an {@link IllegalStateException}, a page that shows the
     * site's takedown notice a {@link com.fanok.audiobooks.BookRemovedException}.
     */
    ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException;

    /** The headers of the media requests of {@code bookUrl}; null for the default ({@code Referer}: the book page). */
    default Map<String, String> mediaHeaders(String bookUrl) {
        return null;
    }

    /** The series the book belongs to, as listed on or linked from its page. */
    default ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        return new ArrayList<>();
    }

    /** The comments of the book; {@code cancelled} is polled between pages. */
    default ArrayList<ComentsPOJO> comments(String bookUrl, BooleanSupplier cancelled) throws Exception {
        return new ArrayList<>();
    }

    // ---------------------------------------------------------------- other readers, other sources

    /** True when {@link #otherReaders} can find anything: the description probes it and the tab is offered. */
    default boolean findsOtherReaders() {
        return false;
    }

    /** Other narrations of the same book on this site. */
    default ArrayList<OtherArtistPOJO> otherReaders(String bookUrl, String title, String author, String reader)
            throws IOException {
        return new ArrayList<>();
    }

    /** The words searched on this site for a book of another site. */
    default String otherSourceTerms(BookPOJO book) {
        return OtherSourceModel.query(book.getName(), book.getAutor(), book.getArtist());
    }

    /** The search page for {@link #otherSourceTerms}; by default the book search. */
    default String otherSourceSearchUrl(String terms, int page) {
        return searchUrl(SearchUrls.Category.BOOKS, terms, page);
    }

    /** The results of {@link #otherSourceSearchUrl}; by default the book search results. */
    default ArrayList<OtherSourceModel.Hit> otherSourceHits(Document searchPage) {
        ArrayList<OtherSourceModel.Hit> hits = new ArrayList<>();
        for (BookPOJO book : searchResults(searchPage, 1).items) {
            hits.add(new OtherSourceModel.Hit(book.getUrl(), book.getName(), book.getAutor(), book.getArtist()));
        }
        return hits;
    }
}
