package com.example.voltahome.itsme;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * README: "Confirmation of an operation". One row of itsme_confirmation_request: who started the confirmation, what
 * is to be approved, and the protocol values (state, nonce, code_verifier).
 * A row is inserted once and never changed: isNew() is always true, so that save() is an INSERT and never replaces
 * an existing row (see StoredAuthorizationRequest).
 */
@Entity
@Table(name = "itsme_confirmation_request")
public class StoredConfirmationRequest implements Persistable<String> {

    @Id
    private String state;
    private String customerId;
    private String operationType;
    private String operationReference;
    private String approvalText;
    private String nonce;
    private String codeVerifier;
    private Instant createdAt;
    private Instant expiresAt;

    protected StoredConfirmationRequest() {
        // for JPA
    }

    StoredConfirmationRequest(String state, String customerId, String operationType, String operationReference,
                              String approvalText, String nonce, String codeVerifier,
                              Instant createdAt, Instant expiresAt) {
        this.state = state;
        this.customerId = customerId;
        this.operationType = operationType;
        this.operationReference = operationReference;
        this.approvalText = approvalText;
        this.nonce = nonce;
        this.codeVerifier = codeVerifier;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() {
        return state;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    boolean isValidAt(Instant now) {
        return expiresAt.isAfter(now);
    }

    String state() {
        return state;
    }

    String customerId() {
        return customerId;
    }

    String operationType() {
        return operationType;
    }

    String operationReference() {
        return operationReference;
    }

    String approvalText() {
        return approvalText;
    }

    String nonce() {
        return nonce;
    }

    String codeVerifier() {
        return codeVerifier;
    }
}
