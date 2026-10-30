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
 * JdbcAuthorizationRequestRepository does for the login, plus who started it, what is to be approved and the SPA's
 * hand-over verifier. Then, once itsme's answer is checked, the approved confirmation behind a one-time code until
 * the SPA redeems it (README: "Hand-over").
 * Read + checked delete makes each stored row single-use, without database-specific SQL.
 */
@Component
public class JdbcConfirmationRequestRepository {

    static final Duration TTL = Duration.ofMinutes(5);

    /** A confirmation in progress. nonce is the raw value; Spring hashes it to compare with the ID token. */
    public record PendingConfirmation(String state, String customerId, ItsmeOperation operation,
                               String nonce, String codeVerifier, String handoverVerifier) {}

    /** An approved confirmation waiting for the SPA: the verifier it is bound to, and the checked result. */
    record ApprovedConfirmation(String handoverVerifier, ItsmeConfirmationResult result) {}

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
                  (state, customer_id, operation_type, operation_reference, approval_text, nonce, code_verifier,
                   handover_verifier, created_at, expires_at)
                VALUES (:state, :customerId, :type, :reference, :text, :nonce, :codeVerifier,
                        :handoverVerifier, :createdAt, :expiresAt)
                """)
                .param("state", pending.state())
                .param("customerId", pending.customerId())
                .param("type", pending.operation().type())
                .param("reference", pending.operation().reference())
                .param("text", pending.operation().text().value())
                .param("nonce", pending.nonce())
                .param("codeVerifier", pending.codeVerifier())
                .param("handoverVerifier", pending.handoverVerifier())
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

    /** Returns the one-time code under which the approved confirmation waits for the SPA. */
    String saveApproved(String handoverVerifier, ItsmeConfirmationResult result) {
        String code = ItsmeHandover.newCode();
        jdbc.sql("""
                INSERT INTO itsme_confirmation_handover
                  (code, handover_verifier, customer_id, operation_type, operation_reference, approval_text,
                   authentication_level, confirmed_at, expires_at)
                VALUES (:code, :verifier, :customerId, :type, :reference, :text, :level, :confirmedAt, :expiresAt)
                """)
                .param("code", code)
                .param("verifier", handoverVerifier)
                .param("customerId", result.customerId())
                .param("type", result.operationType())
                .param("reference", result.operationReference())
                .param("text", result.approvalText())
                .param("level", result.authenticationLevel())
                .param("confirmedAt", Timestamp.from(result.confirmedAt()))
                .param("expiresAt", Timestamp.from(clock.instant().plus(ItsmeHandover.TTL)))
                .update();
        return code;
    }

    /** The approved confirmation stored with this code, if it exists, has not expired and was not redeemed yet. */
    @Transactional
    Optional<ApprovedConfirmation> removeApproved(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Optional<ApprovedConfirmation> found = jdbc.sql("""
                SELECT * FROM itsme_confirmation_handover WHERE code = :code AND expires_at > :now
                """)
                .param("code", code)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new ApprovedConfirmation(rs.getString("handover_verifier"),
                        new ItsmeConfirmationResult(rs.getString("customer_id"), rs.getString("operation_type"),
                                rs.getString("operation_reference"), rs.getString("approval_text"),
                                rs.getString("authentication_level"), rs.getTimestamp("confirmed_at").toInstant())))
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int deleted = jdbc.sql("DELETE FROM itsme_confirmation_handover WHERE code = :code")
                .param("code", code)
                .update();
        return deleted == 1 ? found : Optional.empty();   // 0 = already redeemed by a concurrent call
    }

    /** Removes abandoned confirmations and hand-overs. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("DELETE FROM itsme_confirmation_request WHERE expires_at < :now").param("now", now).update();
        jdbc.sql("DELETE FROM itsme_confirmation_handover WHERE expires_at < :now").param("now", now).update();
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
                        new ItsmeOperation(rs.getString("operation_type"), rs.getString("operation_reference"),
                                ItsmeFreeText.stored(rs.getString("approval_text"))),
                        rs.getString("nonce"),
                        rs.getString("code_verifier"),
                        rs.getString("handover_verifier")))
                .optional();
    }
}
