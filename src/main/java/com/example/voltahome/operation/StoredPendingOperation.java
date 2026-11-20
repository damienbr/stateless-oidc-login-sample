package com.example.voltahome.operation;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * README: "Several operations, one callback". One row of pending_operation: the data of an operation waiting for its
 * confirmation in itsme, as a JSON document, for one customer and one operation type.
 * A row is inserted once and never changed: isNew() is always true, so that save() is an INSERT. Without it, Spring
 * Data would merge (select, then insert or update) because the id is assigned here.
 */
@Entity
@Table(name = "pending_operation")
public class StoredPendingOperation implements Persistable<String> {

    @Id
    private String operationId;
    private String customerId;
    private String operationType;
    private String data;
    private Instant expiresAt;

    protected StoredPendingOperation() {
        // for JPA
    }

    StoredPendingOperation(String operationId, String customerId, String operationType, String data,
                           Instant expiresAt) {
        this.operationId = operationId;
        this.customerId = customerId;
        this.operationType = operationType;
        this.data = data;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() {
        return operationId;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    boolean isValidAt(Instant now) {
        return expiresAt.isAfter(now);
    }

    String data() {
        return data;
    }
}
