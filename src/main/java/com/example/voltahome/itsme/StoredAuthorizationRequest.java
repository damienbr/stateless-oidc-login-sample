package com.example.voltahome.itsme;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * README: "Login in progress". One row of itsme_authorization_request: what Spring Security needs at the callback
 * (state, nonce, code_verifier). Created by schema.sql; Hibernate only checks that this class matches the table.
 * A row is inserted once and never changed: isNew() is always true, so that save() is an INSERT. Without it, Spring
 * Data would merge (select, then insert or update) because the id is assigned here, and a second login with the same
 * state would silently replace the first instead of failing.
 */
@Entity
@Table(name = "itsme_authorization_request")
public class StoredAuthorizationRequest implements Persistable<String> {

    @Id
    private String state;
    private String registrationId;
    private String authorizationUri;
    private String clientId;
    private String redirectUri;
    private String scopes;
    private String nonce;
    private String codeVerifier;
    private Instant createdAt;
    private Instant expiresAt;

    protected StoredAuthorizationRequest() {
        // for JPA
    }

    StoredAuthorizationRequest(String state, String registrationId, String authorizationUri, String clientId,
                               String redirectUri, String scopes, String nonce, String codeVerifier,
                               Instant createdAt, Instant expiresAt) {
        this.state = state;
        this.registrationId = registrationId;
        this.authorizationUri = authorizationUri;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.scopes = scopes;
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

    String registrationId() {
        return registrationId;
    }

    String authorizationUri() {
        return authorizationUri;
    }

    String clientId() {
        return clientId;
    }

    String redirectUri() {
        return redirectUri;
    }

    String scopes() {
        return scopes;
    }

    String nonce() {
        return nonce;
    }

    String codeVerifier() {
        return codeVerifier;
    }
}
