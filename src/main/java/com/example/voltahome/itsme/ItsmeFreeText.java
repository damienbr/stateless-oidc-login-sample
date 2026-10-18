package com.example.voltahome.itsme;

import java.nio.charset.Charset;

/**
 * README: "Confirmation of an operation". The text of the itsme "free_text" WYSIWYS template: what the customer reads
 * in the itsme app before approving. itsme renders the tags b, i, u and br, accepts up to 7500 characters, and supports
 * the ISO-8859-15 character set only.
 * Built only through the builder, which emits those tags and nothing else. Text containing '<', '>' or '&' is refused
 * instead of escaped: the itsme documentation does not say whether entities such as {@code &amp;} are decoded, and the
 * customer must read exactly what the application means.
 */
public final class ItsmeFreeText {

    public static final int MAX_LENGTH = 7500;
    private static final Charset ITSME_CHARSET = Charset.forName("ISO-8859-15");

    private final String value;

    private ItsmeFreeText(String value) {
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("itsme free text must have 1 to " + MAX_LENGTH + " characters");
        }
        if (!ITSME_CHARSET.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("itsme free text must use the ISO-8859-15 character set");
        }
        this.value = value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Rebuilds a text that this class produced earlier (stored with the confirmation in progress). */
    static ItsmeFreeText stored(String value) {
        return new ItsmeFreeText(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static final class Builder {

        private final StringBuilder html = new StringBuilder();

        private Builder() {
        }

        public Builder text(String text) {
            html.append(plain(text));
            return this;
        }

        public Builder bold(String text) {
            html.append("<b>").append(plain(text)).append("</b>");
            return this;
        }

        public Builder lineBreak() {
            html.append("<br>");
            return this;
        }

        public ItsmeFreeText build() {
            return new ItsmeFreeText(html.toString());
        }

        private static String plain(String text) {
            if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0 || text.indexOf('&') >= 0) {
                throw new IllegalArgumentException("itsme free text cannot contain '<', '>' or '&'");
            }
            return text;
        }
    }
}
