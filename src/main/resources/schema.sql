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
