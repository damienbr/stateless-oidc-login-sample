-- Customers of the (fictitious) energy supplier. In a real application this table already exists.
-- The email is verified by the application itself; itsme's email claim is never used.
CREATE TABLE IF NOT EXISTS customer (
    customer_id   VARCHAR(64)  PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,     -- lower case
    display_name  VARCHAR(128) NOT NULL,
    active        BOOLEAN      NOT NULL
);

-- Link between a customer and an itsme account (README: "Customer mapping"). Read by the login, never created by it:
-- links come from outside the sample (README: "Authentication, not identification").
CREATE TABLE IF NOT EXISTS itsme_link (
    customer_id    VARCHAR(64)  PRIMARY KEY,
    itsme_sub      VARCHAR(64)  NOT NULL UNIQUE,
    linked_at      TIMESTAMP    NOT NULL,
    last_login_at  TIMESTAMP    NOT NULL
);

-- Login in progress (README: "Login in progress"): state, nonce and code_verifier, single use, 5 minutes.
CREATE TABLE IF NOT EXISTS itsme_authorization_request (
    state             VARCHAR(128)  PRIMARY KEY,
    registration_id   VARCHAR(32)   NOT NULL,
    authorization_uri VARCHAR(512)  NOT NULL,
    client_id         VARCHAR(128)  NOT NULL,
    redirect_uri      VARCHAR(512)  NOT NULL,
    scopes            VARCHAR(256)  NOT NULL,
    nonce             VARCHAR(128)  NOT NULL,
    code_verifier     VARCHAR(128)  NOT NULL,
    created_at        TIMESTAMP     NOT NULL,
    expires_at        TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_authreq_expires ON itsme_authorization_request (expires_at);

-- Confirmation in progress (README: "Confirmation of an operation"): who started it, what is to be approved, and the
-- protocol values (state, nonce, code_verifier). Single use, 5 minutes. The operation is never read from the callback.
CREATE TABLE IF NOT EXISTS itsme_confirmation_request (
    state               VARCHAR(128)   PRIMARY KEY,
    customer_id         VARCHAR(64)    NOT NULL,
    operation_reference VARCHAR(128)   NOT NULL,
    approval_text       VARCHAR(7500)  NOT NULL,
    nonce               VARCHAR(128)   NOT NULL,
    code_verifier       VARCHAR(128)   NOT NULL,
    created_at          TIMESTAMP      NOT NULL,
    expires_at          TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_confreq_expires ON itsme_confirmation_request (expires_at);

-- Hand-over to the SPA (README: "Hand-over"), one row per itsme flow, for the login and the confirmation alike:
-- 1. begin:    the SPA's verifier, stored under the flow's state when the flow starts;
-- 2. complete: once the callback has checked everything, the result (JSON) and a one-time code;
-- 3. redeem:   the SPA posts the code and the verifier; the row is deleted (single use).
CREATE TABLE IF NOT EXISTS itsme_handover (
    state       VARCHAR(128)   PRIMARY KEY,
    flow        VARCHAR(16)    NOT NULL,          -- LOGIN or CONFIRMATION
    verifier    VARCHAR(128)   NOT NULL,
    code        VARCHAR(64)    UNIQUE,            -- set by complete
    result      VARCHAR(8000),                    -- set by complete; no itsme claim
    expires_at  TIMESTAMP      NOT NULL           -- 5 minutes to complete, then 60 seconds to redeem
);
CREATE INDEX IF NOT EXISTS ix_itsme_handover_expires ON itsme_handover (expires_at);
