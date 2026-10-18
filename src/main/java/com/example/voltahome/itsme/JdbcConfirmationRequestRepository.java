package com.example.voltahome.itsme;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Confirmation of an operation". Stores the confirmation in progress in the database, like
 * JdbcAuthorizationRequestRepository does for the login, plus who started it and what is to be approved.
 * Read + checked delete makes each stored confirmation single-use, without database-specific SQL.
 */
@Component
public class JdbcConfirmationRequestRepository {

    static final Duration TTL = Duration.ofMinutes(5);

    /** A confirmation in progress. nonce is the raw value; Spring hashes it to compare with the ID token. */
    public record PendingConfirmation(String state, String customerId, ItsmeOperation operation,
                               String nonce, String codeVerifier) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcConfirmationRequestRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void save(PendingConfirmation pending) {
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO itsme_confirmation_request
                  (state, customer_id, operation_reference, approval_text, nonce, code_verifier, created_at, expires_at)
                VALUES (:state, :customerId, :reference, :text, :nonce, :codeVerifier, :createdAt, :expiresAt)
                """)
                .param("state", pending.state())
                .param("customerId", pending.customerId())
                .param("reference", pending.operation().reference())
                .param("text", pending.operation().text().value())
                .param("nonce", pending.nonce())
                .param("codeVerifier", pending.codeVerifier())
                .param("createdAt", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(TTL)))
                .update();
    }

    /** The confirmation started with this state, if it exists, has not expired and was not used yet. */
    @Transactional
    public Optional<PendingConfirmation> remove(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Optional<PendingConfirmation> found = find(state);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int deleted = jdbc.sql("DELETE FROM itsme_confirmation_request WHERE state = :state")
                .param("state", state)
                .update();
        return deleted == 1 ? found : Optional.empty();   // 0 = already consumed by a concurrent callback
    }

    /** Removes abandoned confirmations. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        jdbc.sql("DELETE FROM itsme_confirmation_request WHERE expires_at < :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private Optional<PendingConfirmation> find(String state) {
        return jdbc.sql("""
                SELECT * FROM itsme_confirmation_request
                WHERE state = :state AND expires_at > :now
                """)
                .param("state", state)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new PendingConfirmation(
                        rs.getString("state"),
                        rs.getString("customer_id"),
                        new ItsmeOperation(rs.getString("operation_reference"),
                                ItsmeFreeText.stored(rs.getString("approval_text"))),
                        rs.getString("nonce"),
                        rs.getString("code_verifier")))
                .optional();
    }
}
