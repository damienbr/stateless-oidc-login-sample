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
-- protocol values (state, nonce, code_verifier). Single use, 5 minutes. The operation executed at the callback is
-- never read from the callback URL: its type and reference (README: "Several operations, one callback") are read
-- from here. The reference is the id of the pending operation (pending_operation, below).
CREATE TABLE IF NOT EXISTS itsme_confirmation_request (
    state               VARCHAR(128)   PRIMARY KEY,
    customer_id         VARCHAR(64)    NOT NULL,
    operation_type      VARCHAR(32)    NOT NULL,
    operation_reference VARCHAR(128)   NOT NULL,   -- pending_operation.operation_id
    approval_text       VARCHAR(7500)  NOT NULL,
    nonce               VARCHAR(128)   NOT NULL,
    code_verifier       VARCHAR(128)   NOT NULL,
    created_at          TIMESTAMP      NOT NULL,
    expires_at          TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_itsme_confreq_expires ON itsme_confirmation_request (expires_at);

-- Hand-over to the SPA (README: "Hand-over"), one row per itsme flow, for the login and the confirmation alike:
-- 1. begin:    the SPA's browser code challenge (S256 of its browser code verifier), stored under the flow's state;
-- 2. complete: once the callback has checked everything, the result (JSON) and a one-time code;
-- 3. redeem:   the SPA posts the code and its browser code verifier; the row is deleted (single use).
-- Not to be confused with PKCE's code_challenge and code_verifier, between the backend and itsme.
CREATE TABLE IF NOT EXISTS itsme_handover (
    state       VARCHAR(128)   PRIMARY KEY,
    flow        VARCHAR(16)    NOT NULL,          -- LOGIN or CONFIRMATION
    browser_code_challenge VARCHAR(64) NOT NULL,
    code        VARCHAR(64)    UNIQUE,            -- set by complete
    result      VARCHAR(2000),                    -- set by complete (JSON); our own data, no itsme claim
    expires_at  TIMESTAMP      NOT NULL           -- 5 minutes to complete, then 60 seconds to redeem
);
CREATE INDEX IF NOT EXISTS ix_itsme_handover_expires ON itsme_handover (expires_at);

-- Operations waiting for their confirmation in itsme (README: "Several operations, one callback"). The record to
-- create may not exist yet (an order is only created once confirmed): everything needed to execute the operation is
-- persisted here when the confirmation starts, and reloaded, for the same customer and type, once itsme has confirmed.
-- The data is a JSON document, written and read by the operation of that type. Stored as text: no database-specific
-- JSON type. Single use (deleted when executed), 10 minutes.
CREATE TABLE IF NOT EXISTS pending_operation (
    operation_id    VARCHAR(64)    PRIMARY KEY,
    customer_id     VARCHAR(64)    NOT NULL,
    operation_type  VARCHAR(32)    NOT NULL,
    data            VARCHAR(4000)  NOT NULL,     -- JSON
    expires_at      TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_pending_operation_expires ON pending_operation (expires_at);

-- DEMO ONLY (README: "Several operations, one callback"). Products, orders and offers of the fictitious energy
-- supplier. In a real application these tables already exist.
-- An order does not exist before its confirmation: a row is inserted in customer_order, from the data of its
-- pending operation, once itsme has confirmed. An offer exists before, and is accepted by a confirmation.
CREATE TABLE IF NOT EXISTS product (
    product_code  VARCHAR(64)    PRIMARY KEY,
    description   VARCHAR(256)   NOT NULL,
    price         DECIMAL(10, 2) NOT NULL      -- EUR
);

CREATE TABLE IF NOT EXISTS customer_order (
    order_id     VARCHAR(64)    PRIMARY KEY,
    customer_id  VARCHAR(64)    NOT NULL,
    description  VARCHAR(256)   NOT NULL,
    amount       DECIMAL(10, 2) NOT NULL,      -- EUR
    status       VARCHAR(16)    NOT NULL       -- CONFIRMED
);

CREATE TABLE IF NOT EXISTS offer (
    offer_id       VARCHAR(64)    PRIMARY KEY,
    customer_id    VARCHAR(64)    NOT NULL,
    description    VARCHAR(256)   NOT NULL,
    price_per_kwh  DECIMAL(10, 4) NOT NULL,    -- EUR
    status         VARCHAR(16)    NOT NULL     -- OPEN, ACCEPTED
);
