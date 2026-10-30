package com.example.voltahome.itsme;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Hand-over". Stores the result of a successful itsme login behind a one-time code, until the SPA redeems it.
 * In the database, so that the SPA's call can reach any instance. Read + checked delete makes each code single-use,
 * as for the login in progress.
 */
@Component
public class JdbcLoginHandoverRepository {

    /** A stored hand-over: the verifier it is bound to, and the login result it carries. */
    record StoredLogin(String handoverVerifier, ItsmeLoginResult result) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcLoginHandoverRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Returns the one-time code. */
    public String save(String handoverVerifier, ItsmeLoginResult result) {
        String code = ItsmeHandover.newCode();
        jdbc.sql("""
                INSERT INTO itsme_login_handover
                  (code, handover_verifier, customer_id, authentication_method, authentication_level,
                   authentication_time, expires_at)
                VALUES (:code, :verifier, :customerId, :method, :level, :time, :expiresAt)
                """)
                .param("code", code)
                .param("verifier", handoverVerifier)
                .param("customerId", result.customerId())
                .param("method", result.authenticationMethod())
                .param("level", result.authenticationLevel())
                .param("time", Timestamp.from(result.authenticationTime()))
                .param("expiresAt", Timestamp.from(clock.instant().plus(ItsmeHandover.TTL)))
                .update();
        return code;
    }

    /** The hand-over stored with this code, if it exists, has not expired and was not redeemed yet. */
    @Transactional
    Optional<StoredLogin> remove(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Optional<StoredLogin> found = jdbc.sql("""
                SELECT * FROM itsme_login_handover WHERE code = :code AND expires_at > :now
                """)
                .param("code", code)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new StoredLogin(rs.getString("handover_verifier"), new ItsmeLoginResult(
                        rs.getString("customer_id"), rs.getString("authentication_method"),
                        rs.getString("authentication_level"), rs.getTimestamp("authentication_time").toInstant())))
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int deleted = jdbc.sql("DELETE FROM itsme_login_handover WHERE code = :code").param("code", code).update();
        return deleted == 1 ? found : Optional.empty();   // 0 = already redeemed by a concurrent call
    }

    /** Removes hand-overs that were never redeemed. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        jdbc.sql("DELETE FROM itsme_login_handover WHERE expires_at < :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }
}
