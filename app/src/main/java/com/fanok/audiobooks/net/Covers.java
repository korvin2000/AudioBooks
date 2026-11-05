package com.fanok.audiobooks.net;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.search.Provider;

/**
 * Cover images of the lists: Glide fetches them with its own HTTP stack, which the shared OkHttp client's Referer
 * interceptor never sees. A site whose covers need its Referer ({@code SourceParser.referer()}, e.g. a hotlink-guarded
 * CDN in {@code SourceParser.hosts()}, or a shared one in {@code SourceParser.refererHosts()}) gets it here; any other cover stays a plain URL, with the same cache key.
 */
public final class Covers {

    private Covers() {
    }

    /** What Glide loads for the cover at {@code url}: the URL, or the URL with the owning site's Referer. */
    public static Object glide(String url) {
        String referer = Provider.refererOf(Url.hostOf(url));
        if (referer == null) {
            return url;
        }
        return new GlideUrl(url, new LazyHeaders.Builder().addHeader("Referer", referer).build());
    }
}
