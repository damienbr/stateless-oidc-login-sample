package com.example.voltahome.itsme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

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
 *   <li>{@link #begin}: when the flow starts, store the random verifier the SPA sent in the request body;</li>
 *   <li>{@link #complete}: at the callback, once every check has passed, store the result behind a one-time code,
 *       which the callback sends to the SPA in the URL fragment;</li>
 *   <li>{@link #redeem}: the SPA posts the code and its verifier; the result is returned once.</li>
 * </ol>
 * Attacks and their answer: a code is 256 random bits (not guessable), single use and valid 60 seconds (a leaked code
 * is useless); it is redeemed only with the verifier of the browser that started the flow (a callback link sent to a
 * victim, the login CSRF, gets nothing); a wrong verifier deletes the code (no second try); a login code cannot
 * complete a confirmation (the flow is checked).
 */
@Component
public class ItsmeHandover {

    public enum Flow { LOGIN, CONFIRMATION }

    /** Name of the verifier in the start request: form parameter (login) or JSON field (confirmation). */
    public static final String VERIFIER_PARAMETER = "handover_verifier";

    /** Time to complete the flow: the same as a login or confirmation in progress. */
    static final Duration FLOW_TTL = Duration.ofMinutes(5);

    /** Time to redeem the code: long enough for the SPA to load, short enough to make a leaked code useless. */
    static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** What the SPA generates: base64url of at least 32 random bytes (the alphabet and lengths of PKCE). */
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9_-]{43,128}");

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

    public static boolean isVerifier(String value) {
        return value != null && VERIFIER.matcher(value).matches();
    }

    /** Step 1, at the start of a flow. */
    public void begin(String state, Flow flow, String verifier) {
        if (!isVerifier(verifier)) {
            throw new IllegalArgumentException("not a hand-over verifier");
        }
        jdbc.sql("""
                INSERT INTO itsme_handover (state, flow, verifier, expires_at)
                VALUES (:state, :flow, :verifier, :expiresAt)
                """)
                .param("state", state)
                .param("flow", flow.name())
                .param("verifier", verifier)
                .param("expiresAt", Timestamp.from(clock.instant().plus(FLOW_TTL)))
                .update();
    }

    /**
     * Step 2, at the callback, once every check has passed. Returns the one-time code, or empty if this flow was not
     * begun with a verifier (or expired, or was already completed): then there is nothing to hand over.
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
     * comes with the verifier stored at the start. Whatever the outcome, the code is deleted: one try only.
     * Read + checked delete, as for the login in progress: two concurrent calls cannot both succeed.
     */
    @Transactional
    public <T> Optional<T> redeem(Flow flow, String code, String verifier, Class<T> type) {
        if (code == null) {
            return Optional.empty();
        }
        record Stored(String verifier, String result) {}
        Optional<Stored> stored = jdbc.sql("""
                SELECT verifier, result FROM itsme_handover
                WHERE code = :code AND flow = :flow AND expires_at > :now
                """)
                .param("code", code)
                .param("flow", flow.name())
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new Stored(rs.getString("verifier"), rs.getString("result")))
                .optional();
        int deleted = jdbc.sql("DELETE FROM itsme_handover WHERE code = :code").param("code", code).update();
        if (stored.isEmpty() || deleted != 1 || !sameVerifier(stored.get().verifier(), verifier)) {
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

    /** Constant-time comparison: the time taken does not tell how many characters matched. */
    private static boolean sameVerifier(String stored, String presented) {
        return presented != null && MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII));
    }
}
