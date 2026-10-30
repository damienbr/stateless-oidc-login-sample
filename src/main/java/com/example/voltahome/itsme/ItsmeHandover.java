package com.example.voltahome.itsme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * README: "Hand-over". How the end of an itsme flow reaches the single page application (SPA) without a cookie.
 * The itsme callback is a browser navigation: it cannot answer with JSON, and it carries no credential. So every flow,
 * the login and the confirmation alike, goes through the same three steps:
 * <ol>
 *   <li>{@link #begin}: when the flow starts, store the browser code challenge the SPA sent;</li>
 *   <li>{@link #complete}: at the callback, once every check has passed, store the result behind a one-time code,
 *       which the callback sends to the SPA in the URL fragment;</li>
 *   <li>{@link #redeem}: the SPA posts the code and its browser code verifier; the result is returned once.</li>
 * </ol>
 * The browser code verifier is a random value only the SPA in that browser keeps; the browser code challenge is its
 * S256 hash, the same construction as PKCE (RFC 7636). It is NOT PKCE's code_verifier, which stays between the
 * backend and itsme: this pair binds the end of the flow to the browser that started it.
 * Attacks and their answer: a code is 256 random bits (not guessable), single use and valid 60 seconds (a leaked code
 * is useless); it is redeemed only with the browser code verifier of the browser that started the flow (a callback
 * link sent to a victim, the login CSRF, gets nothing; so does whoever saw the challenge); a wrong attempt deletes
 * the code (no second try); a login code cannot complete a confirmation (the flow is checked).
 */
@Component
public class ItsmeHandover {

    public enum Flow { LOGIN, CONFIRMATION }

    /** Name of the browser code challenge in the start request of the login (a form POST). */
    public static final String BROWSER_CODE_CHALLENGE_PARAMETER = "browser_code_challenge";

    /** Time to complete the flow: the same as a login or confirmation in progress. */
    static final Duration FLOW_TTL = Duration.ofMinutes(5);

    /** Time to redeem the code: long enough for the SPA to load, short enough to make a leaked code useless. */
    static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** Base64url of a SHA-256 hash: 43 characters, no padding. */
    private static final Pattern BROWSER_CODE_CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final StringKeyGenerator CODES =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public ItsmeHandover(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public static boolean isBrowserCodeChallenge(String value) {
        return value != null && BROWSER_CODE_CHALLENGE.matcher(value).matches();
    }

    /** Step 1, at the start of a flow. */
    public void begin(String state, Flow flow, String browserCodeChallenge) {
        if (!isBrowserCodeChallenge(browserCodeChallenge)) {
            throw new IllegalArgumentException("not a browser code challenge");
        }
        jdbc.sql("""
                INSERT INTO itsme_handover (state, flow, browser_code_challenge, expires_at)
                VALUES (:state, :flow, :browserCodeChallenge, :expiresAt)
                """)
                .param("state", state)
                .param("flow", flow.name())
                .param("browserCodeChallenge", browserCodeChallenge)
                .param("expiresAt", Timestamp.from(clock.instant().plus(FLOW_TTL)))
                .update();
    }

    /**
     * Step 2, at the callback, once every check has passed. Returns the one-time code, or empty if this flow was not
     * begun with a browser code challenge (or expired, or was already completed): then there is nothing to hand over.
     * @param state the state of the callback, already checked by Spring or by ItsmeConfirmationService
     * @param result what step 3 returns; our own data only, never an itsme claim
     */
    public Optional<String> complete(String state, Flow flow, Object result) {
        String code = CODES.generateKey();
        int updated = jdbc.sql("""
                UPDATE itsme_handover SET code = :code, result = :result, expires_at = :expiresAt
                WHERE state = :state AND flow = :flow AND code IS NULL AND expires_at > :now
                """)
                .param("code", code)
                .param("result", json.writeValueAsString(result))
                .param("expiresAt", Timestamp.from(clock.instant().plus(CODE_TTL)))
                .param("state", state)
                .param("flow", flow.name())
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return updated == 1 ? Optional.of(code) : Optional.empty();
    }

    /**
     * Step 3, when the SPA posts the code. Returns the result if the code exists for this flow, has not expired, and
     * comes with the browser code verifier whose S256 hash was stored at the start. Whatever the outcome, the code is
     * deleted: one try only. Read + checked delete, as for the login in progress: two concurrent calls cannot both
     * succeed.
     */
    @Transactional
    public <T> Optional<T> redeem(Flow flow, String code, String browserCodeVerifier, Class<T> type) {
        if (code == null) {
            return Optional.empty();
        }
        record Stored(String browserCodeChallenge, String result) {}
        Optional<Stored> stored = jdbc.sql("""
                SELECT browser_code_challenge, result FROM itsme_handover
                WHERE code = :code AND flow = :flow AND expires_at > :now
                """)
                .param("code", code)
                .param("flow", flow.name())
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new Stored(rs.getString("browser_code_challenge"), rs.getString("result")))
                .optional();
        int deleted = jdbc.sql("DELETE FROM itsme_handover WHERE code = :code").param("code", code).update();
        if (stored.isEmpty() || deleted != 1 || !matches(stored.get().browserCodeChallenge(), browserCodeVerifier)) {
            return Optional.empty();
        }
        return Optional.of(json.readValue(stored.get().result(), type));
    }

    /** Removes flows never completed and codes never redeemed. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        jdbc.sql("DELETE FROM itsme_handover WHERE expires_at < :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    /**
     * True if the challenge is the S256 hash of this verifier. Nimbus computes the hash (no hand-written
     * cryptography); an invalid verifier (length, characters) never matches. Constant-time comparison: the time taken
     * does not tell how many characters matched.
     */
    private static boolean matches(String browserCodeChallenge, String browserCodeVerifier) {
        if (browserCodeVerifier == null) {
            return false;
        }
        try {
            String computed = CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(browserCodeVerifier))
                    .getValue();
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    browserCodeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
