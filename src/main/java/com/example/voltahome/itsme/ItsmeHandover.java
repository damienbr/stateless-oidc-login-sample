package com.example.voltahome.itsme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;

/**
 * README: "Hand-over". An itsme flow ends on a backend callback, a browser navigation that cannot answer with JSON
 * and carries no credential. So the callback stores its result behind a one-time code and redirects to the SPA with
 * that code in the URL fragment; the SPA then redeems the code with an AJAX call.
 * The code alone is not enough: before the flow, the SPA generates a random verifier, keeps it, and sends it in the
 * body of the request that starts the flow (never in a URL); it is stored with the flow, like the PKCE code_verifier.
 * Redeeming the code requires the same verifier, so a callback link opened in another browser (a login CSRF, for
 * example) cannot be redeemed.
 */
public final class ItsmeHandover {

    /** Form parameter (login) that carries the verifier at the start; the confirmation uses a JSON field. */
    public static final String VERIFIER_PARAMETER = "handover_verifier";

    /** Long enough for the SPA to load and redeem the code; short enough to make a leaked code useless. */
    public static final Duration TTL = Duration.ofSeconds(60);

    /** Base64url of at least 32 random bytes, as the SPA generates it; the same alphabet and lengths as PKCE. */
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9_-]{43,128}");

    private static final StringKeyGenerator CODES =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    private ItsmeHandover() {
    }

    /** A random, URL-safe one-time code (256 bits). */
    static String newCode() {
        return CODES.generateKey();
    }

    /** True if the value has the form of a verifier the SPA generates. */
    public static boolean isVerifier(String value) {
        return value != null && VERIFIER.matcher(value).matches();
    }

    /** True if the verifier presented with the code is the one stored at the start. Constant-time comparison. */
    static boolean matches(String stored, String presented) {
        return stored != null && presented != null && MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII));
    }
}
