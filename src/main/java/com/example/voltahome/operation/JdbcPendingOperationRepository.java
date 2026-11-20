package com.example.voltahome.operation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * README: "Several operations, one callback". Operations waiting for their confirmation in itsme, in the
 * pending_operation table. The record to create may not exist yet (an order is only created once confirmed), so
 * everything needed to execute the operation is persisted here when the confirmation starts, as a JSON document, and
 * reloaded when itsme has confirmed.
 * A pending operation belongs to one customer and one operation type, and is executed at most once: read + checked
 * delete, without database-specific SQL.
 */
@Component
public class JdbcPendingOperationRepository {

    /** Longer than a confirmation in progress (5 minutes): a confirmation never finds its operation gone. */
    static final Duration TTL = Duration.ofMinutes(10);

    /** Size of the data column (schema.sql). */
    static final int MAX_DATA_LENGTH = 4000;

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcPendingOperationRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Persists the data of an operation for this customer. Returns its id: the reference of the confirmation. */
    public String save(String customerId, String type, String data) {
        if (data.length() > MAX_DATA_LENGTH) {
            throw new IllegalArgumentException("operation data must have at most " + MAX_DATA_LENGTH + " characters");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO pending_operation (operation_id, customer_id, operation_type, data, expires_at)
                VALUES (:id, :customer, :type, :data, :expiresAt)
                """)
                .param("id", id)
                .param("customer", customerId)
                .param("type", type)
                .param("data", data)
                .param("expiresAt", Timestamp.from(clock.instant().plus(TTL)))
                .update();
        return id;
    }

    /**
     * Reloads the data of a pending operation and deletes it: it can be executed once. Empty if it does not exist,
     * has expired, was already taken, or is not this customer's or not of this type.
     * Call it in the transaction that executes the operation: if the execution fails, the delete is rolled back
     * with it.
     */
    public Optional<String> take(String id, String customerId, String type) {
        Optional<String> data = jdbc.sql("""
                SELECT data FROM pending_operation
                WHERE operation_id = :id AND customer_id = :customer AND operation_type = :type AND expires_at > :now
                """)
                .param("id", id)
                .param("customer", customerId)
                .param("type", type)
                .param("now", Timestamp.from(clock.instant()))
                .query(String.class)
                .optional();
        if (data.isEmpty()) {
            return Optional.empty();
        }
        int deleted = jdbc.sql("DELETE FROM pending_operation WHERE operation_id = :id").param("id", id).update();
        return deleted == 1 ? data : Optional.empty();       // 0 = already taken by a concurrent execution
    }

    /** Removes operations never confirmed. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        jdbc.sql("DELETE FROM pending_operation WHERE expires_at < :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }
}
