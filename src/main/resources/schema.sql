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

-- Login in progress (README: "Login in progress"): state, nonce and code_verifier, single use, 5 minutes, and the
-- hand-over verifier sent by the SPA that started it (README: "Hand-over").
CREATE TABLE IF NOT EXISTS itsme_authorization_request (
    state             VARCHAR(128)  PRIMARY KEY,
    registration_id   VARCHAR(32)   NOT NULL,
    authorization_uri VARCHAR(512)  NOT NULL,
    client_id         VARCHAR(128)  NOT NULL,
    redirect_uri      VARCHAR(512)  NOT NULL,
    scopes            VARCHAR(256)  NOT NULL,
    nonce             VARCHAR(128)  NOT NULL,
    code_verifier     VARCHAR(128)  NOT NULL,
    handover_verifier VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP     NOT NULL,
    expires_at        TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_authreq_expires ON itsme_authorization_request (expires_at);

-- Login hand-over (README: "Hand-over"): the result of a successful itsme login, waiting for the SPA to redeem the
-- one-time code with its verifier. Single use, 60 seconds. No itsme claim.
CREATE TABLE IF NOT EXISTS itsme_login_handover (
    code                  VARCHAR(64)  PRIMARY KEY,
    handover_verifier    VARCHAR(64)  NOT NULL,
    customer_id           VARCHAR(64)  NOT NULL,
    authentication_method VARCHAR(32)  NOT NULL,
    authentication_level  VARCHAR(128) NOT NULL,
    authentication_time   TIMESTAMP    NOT NULL,
    expires_at            TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_login_handover_expires ON itsme_login_handover (expires_at);

-- Confirmation in progress (README: "Confirmation of an operation"): who started it, what is to be approved, and the
-- protocol values (state, nonce, code_verifier). Single use, 5 minutes. The operation is never read from the callback:
-- its type and reference (README: "Several operations, one callback") are read from here.
CREATE TABLE IF NOT EXISTS itsme_confirmation_request (
    state               VARCHAR(128)   PRIMARY KEY,
    customer_id         VARCHAR(64)    NOT NULL,
    operation_type      VARCHAR(32)    NOT NULL,
    operation_reference VARCHAR(128)   NOT NULL,
    approval_text       VARCHAR(7500)  NOT NULL,
    nonce               VARCHAR(128)   NOT NULL,
    code_verifier       VARCHAR(128)   NOT NULL,
    handover_verifier  VARCHAR(64)    NOT NULL,
    created_at          TIMESTAMP      NOT NULL,
    expires_at          TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_confreq_expires ON itsme_confirmation_request (expires_at);

-- Confirmation hand-over (README: "Hand-over"): a confirmation approved and checked at the callback, not executed yet.
-- The SPA redeems the one-time code with its verifier and the customer's token. Single use, 60 seconds.
CREATE TABLE IF NOT EXISTS itsme_confirmation_handover (
    code                 VARCHAR(64)    PRIMARY KEY,
    handover_verifier   VARCHAR(64)    NOT NULL,
    customer_id          VARCHAR(64)    NOT NULL,
    operation_type       VARCHAR(32)    NOT NULL,
    operation_reference  VARCHAR(128)   NOT NULL,
    approval_text        VARCHAR(7500)  NOT NULL,
    authentication_level VARCHAR(128)   NOT NULL,
    confirmed_at         TIMESTAMP      NOT NULL,
    expires_at           TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_confirmation_handover_expires ON itsme_confirmation_handover (expires_at);

-- DEMO ONLY (README: "Several operations, one callback"). Orders and offers of the fictitious energy supplier, which a
-- customer confirms with itsme. In a real application these tables already exist.
CREATE TABLE IF NOT EXISTS customer_order (
    order_id     VARCHAR(64)    PRIMARY KEY,
    customer_id  VARCHAR(64)    NOT NULL,
    description  VARCHAR(256)   NOT NULL,
    amount       DECIMAL(10, 2) NOT NULL,      -- EUR
    status       VARCHAR(16)    NOT NULL       -- OPEN, CONFIRMED
);

CREATE TABLE IF NOT EXISTS offer (
    offer_id       VARCHAR(64)    PRIMARY KEY,
    customer_id    VARCHAR(64)    NOT NULL,
    description    VARCHAR(256)   NOT NULL,
    price_per_kwh  DECIMAL(10, 4) NOT NULL,    -- EUR
    status         VARCHAR(16)    NOT NULL     -- OPEN, ACCEPTED
);
