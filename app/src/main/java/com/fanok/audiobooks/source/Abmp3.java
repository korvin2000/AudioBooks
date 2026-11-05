package com.fanok.audiobooks.source;

import com.fanok.audiobooks.AutorsSearchABMP3;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.search.ProviderPage;

import org.jsoup.nodes.Document;

import java.util.ArrayList;

/**
 * audiobook-mp3.com, the first site of the {@link Abmp3Engine}: plain GETs, no Referer of its own. Listings (the
 * index, {@link Url#ABMP3_TOP}, {@link Url#ABMP3_RANDOM}, a genre, author or reader page) page with the {@code page}
 * query parameter alone ({@link Url#abmp3ListingPage}), with no cap; genre listings cannot be re-sorted. The authors'
 * index is an alphabet whose letters are its pages. The search {@code /search?text=Q} is one static page of every
 * hit, sliced into pages of {@link #SEARCH_PAGE_SIZE} here (no reader search; its author block is
 * {@link AutorsSearchABMP3}); a hit's cover is derived from its link. Book pages carry a Playerjs whose {@code file}
 * is a playlist URL; the media wants a desktop User-Agent and the site as Referer. No series; other narrations are
 * found by searching the title. The static parsers are the engine's, for this site.
 */
public final class Abmp3 extends Abmp3Engine {

    private static final Abmp3 SITE = new Abmp3();

    public Abmp3() {
        super(new Site(Url.SERVER_ABMP3)
                .top(Url.ABMP3_TOP)
                .random(Url.ABMP3_RANDOM)
                .hitCovers(Url.CDN_ABMP3 + "/audiobooks/", "audiobook-"));
    }

    /** The index, rankings, genre, author and reader listings. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        return SITE.listing(doc, doc.location(), page);
    }

    /** The search returns every hit at once: one request per page, sliced locally, no book-page fetches (SRCH-13). */
    public static ProviderPage<BookPOJO> parseSearch(Document doc, int page) {
        return SITE.searchResults(doc, page);
    }

    public static DescriptionPOJO parseDescription(Document document, String bookUrl) {
        return SITE.description(document, bookUrl);
    }

    /** A book opened by its link. */
    public static BookPOJO parseBook(Document document, String url) {
        return SITE.book(document, url);
    }

    /** The "similar books" block of a book page. */
    public static ArrayList<BookPOJO> parseRecommendations(Document document) {
        return SITE.recommendations(document, document.location());
    }

    /** The chapters' book name: the heading without the "Author - " prefix. */
    public static String parseBookName(Document doc) {
        return SITE.bookName(doc);
    }

    /** The other-source search: the hits of its books block, the author and reader read off their parentheses. */
    public static ArrayList<OtherSourceModel.Hit> parseSearchHits(Document doc) {
        return SITE.otherSourceHits(doc);
    }
}
