package com.example.voltahome.operation;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

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
public class JpaPendingOperationRepository {

    /** Longer than a confirmation in progress (5 minutes): a confirmation never finds its operation gone. */
    static final Duration TTL = Duration.ofMinutes(10);

    /** Size of the data column (schema.sql). */
    static final int MAX_DATA_LENGTH = 4000;

    private final StoredPendingOperationRepository operations;
    private final Clock clock;

    public JpaPendingOperationRepository(StoredPendingOperationRepository operations, Clock clock) {
        this.operations = operations;
        this.clock = clock;
    }

    /** Persists the data of an operation for this customer. Returns its id: the reference of the confirmation. */
    public String save(String customerId, String type, String data) {
        if (data.length() > MAX_DATA_LENGTH) {
            throw new IllegalArgumentException("operation data must have at most " + MAX_DATA_LENGTH + " characters");
        }
        String id = UUID.randomUUID().toString();
        operations.save(new StoredPendingOperation(id, customerId, type, data, clock.instant().plus(TTL)));
        return id;
    }

    /**
     * Reloads the data of a pending operation and deletes it: it can be executed once. Empty if it does not exist,
     * has expired, was already taken, or is not this customer's or not of this type.
     * Call it in the transaction that executes the operation: if the execution fails, the delete is rolled back
     * with it.
     */
    public Optional<String> take(String id, String customerId, String type) {
        Optional<String> data = operations
                .findByOperationIdAndCustomerIdAndOperationType(id, customerId, type)
                .filter(stored -> stored.isValidAt(clock.instant()))
                .map(StoredPendingOperation::data);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        int deleted = operations.deleteByOperationId(id);
        return deleted == 1 ? data : Optional.empty();       // 0 = already taken by a concurrent execution
    }

    /** Removes operations never confirmed. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        operations.deleteExpired(clock.instant());
    }
}
