package com.fanok.audiobooks.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * What every JSON a source reads needs: a player config, an XHR playlist or a JSON API page ({@code JsonPage.of}).
 * Absent or mistyped values read as "" or 0; a document of the wrong shape is an {@link IllegalStateException}, like
 * every other "page is not what the site serves" case. JS-only escapes ({@code \(}, {@code \-} ...) are read as JS
 * reads them, since players eval their JSON as JS. Pure and static.
 */
public final class Json {

    private Json() {
    }

    /**
     * {@code text} parsed; malformed text is an {@link IllegalStateException}. Only after the strict parse fails are
     * JS-only escapes read as JS reads them ({@link #jsEscapes}); a cut document is never "repaired".
     */
    public static JsonElement parse(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            String js = jsEscapes(text);
            if (!js.equals(text)) {
                try {
                    return JsonParser.parseString(js);
                } catch (JsonParseException stillBad) {
                    // not a JS literal either: report the first error
                }
            }
            throw new IllegalStateException("malformed JSON", e);
        }
    }

    /**
     * Pure: escapes a JS literal has and JSON lacks ({@code \(}, {@code \-} ...) read as JS reads them: the character
     * itself.
     */
    static String jsEscapes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                if ("\"\\/bfnrtu'\n".indexOf(next) >= 0) {
                    out.append(c); // an escape Gson reads: kept whole, so \\( stays a backslash and a '('
                }
                out.append(next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static JsonArray array(String text) {
        JsonElement e = parse(text);
        if (!e.isJsonArray()) {
            throw new IllegalStateException("JSON is not a list");
        }
        return e.getAsJsonArray();
    }

    public static JsonObject object(String text) {
        JsonElement e = parse(text);
        if (!e.isJsonObject()) {
            throw new IllegalStateException("JSON is not an object");
        }
        return e.getAsJsonObject();
    }

    /** The list under {@code key}; an {@link IllegalStateException} when there is none. */
    public static JsonArray arrayOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            throw new IllegalStateException("JSON has no '" + key + "' list");
        }
        return e.getAsJsonArray();
    }

    public static String string(JsonObject o, String key) {
        return string(o.get(key));
    }

    /** The text of a string or number; "" for anything else. */
    public static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Whole seconds of a number or numeric string ("12.5" → 12); 0 when absent, malformed or negative. */
    public static int seconds(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            double value = e.getAsDouble();
            return value > 0 && value < Integer.MAX_VALUE ? (int) value : 0;
        } catch (NumberFormatException notNumber) {
            return 0;
        }
    }
}
