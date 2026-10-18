# itsme login with Spring Security — reference sample

A runnable reference implementation of a customer login with **itsme** (OpenID Connect, itsme API **v2**) in a **stateless Spring Boot 4 backend**, for a fictitious energy supplier, *VoltaHome*.

- **Both itsme client authentication methods**, with one switch (`itsme.client-authentication`):
  - **key pairs** (`private-key-jwt`, the default, recommended by itsme): the authorization request travels in a Request Object signed by us and encrypted for itsme; ID tokens are signed by itsme and encrypted with our public key;
  - **client secret** (`client-secret`): a shared secret, parameters in the URL, ID tokens encrypted with a key derived from the secret.
- **Authentication, not identification**: no personal data is requested from itsme. The customer is found through the itsme `sub` (a pseudonym) already linked to their account; an itsme account that is not linked is refused. See [Authentication, not identification](#authentication-not-identification).
- **Confirmation of an operation**: a logged-in customer approves one operation in the itsme app, from a button in the protected portal. The approval counts only if it comes from the itsme account linked to that customer. See [Confirmation of an operation](#confirmation-of-an-operation).
- **Runs with IntelliJ and a JDK only**: no database server, no itsme credentials, and no Docker needed. Docker is optional; see [Run with Docker](#run-with-docker). In the tests, itsme is replaced by **WireMock**; when the application runs locally, it serves an **itsme stub** itself (`local` profile). The database is **H2 in memory**.

> **Scope of the stubs.** WireMock and the itsme stub reproduce our reading of the public itsme v2 documentation. They prove that the code is consistent with that reading, not with itsme's real behaviour. Points marked *to be confirmed in E2E* must be verified against the itsme E2E environment.

---

## Start here

New to the project? Follow this order; it takes about an hour. For the reasoning behind each choice, read the [walkthrough](docs/walkthrough.md) next. New to Spring Security? [Spring Security classes in this sample, explained](docs/spring-security-classes.md) describes each class in plain words.

1. **Run it.** Run the tests, then the browser demo: [Quick start in IntelliJ](#quick-start-in-intellij) and [Run the login in a browser](#run-the-login-in-a-browser).
2. **See the whole login.** Read the diagram in [Login flow](#login-flow).
3. **Learn the rules.** Read [Rules you must not change](#rules-you-must-not-change) before you change any code.
4. **Read five files, in this order:**

| File | What it shows |
|---|---|
| `security/SecurityConfig.java` | Where each itsme part is plugged into Spring Security |
| `itsme/ItsmeAuthorizationRequestResolver.java` | What we send to itsme (with key pairs, through `ItsmeRequestObjectFactory`) |
| `itsme/ItsmeClientConfig.java` | How the ID token is decrypted and checked (`idTokenDecoderFactory`) |
| `itsme/ItsmeOidcUserService.java` | How the itsme `sub` becomes our customer |
| `itsme/JdbcAuthorizationRequestRepository.java` | How a login in progress survives without a session |

5. **Read the tests as a list of attacks.** Start with `ItsmeLoginFlowTest`: each test changes one thing in a valid login and states the expected answer.

### Glossary

| Term | Meaning in this sample |
|---|---|
| `sub` | The user id itsme gives us: a stable pseudonym, specific to our client. It says "same itsme account as last time", not who the person is. |
| `acr` | The authentication level. We require `acr_advanced` (itsme code); `acr_basic` also allows a fingerprint. |
| `state` | A random value that ties the callback to the login we started. Protects against a forged callback. |
| `nonce` | A random value copied into the ID token. Protects against a replayed ID token. |
| PKCE | `code_verifier` (kept by us) and `code_challenge` (its SHA-256, sent to itsme). Only the holder of the verifier can exchange the authorization code. |
| Authorization code | A short-lived (3 minutes), opaque value that itsme sends back through the browser; the backend exchanges it for the ID token. |
| ID token | A JWT from itsme saying who logged in (`sub`), at which level (`acr`), for which client and login (`aud`, `nonce`). |
| JWS / JWE | A signed JWT (proves who wrote it) / an encrypted JWT (hides the content). |
| Nested JWT | A JWS inside a JWE: signed, then encrypted. itsme ID tokens must be nested. |
| Request Object | With key pairs, the authorization parameters packed in a JWT, signed by us and encrypted for itsme. |
| Client assertion | With key pairs, a short JWT signed by us that proves our identity at the token endpoint (`private_key_jwt`). |
| JWKS | A JSON set of public keys. itsme publishes its own; we publish ours at `/.well-known/jwks.json`. |
| Discovery document | itsme's published configuration (`.well-known/openid-configuration`). Here it is only compared with our configuration, never used as a source. |
| Service code | The itsme service our client uses, sent as the `service:<code>` scope. The login and the confirmation use two different services (`service-code`, `confirmation.service-code`). |
| Hand-over | The end of the itsme login: `ItsmeLoginResult` is passed to `LoginHandover`, where the application issues its own session or token. |
| Confirmation | An itsme service type: the customer approves one operation, whose text is shown in the itsme app (a WYSIWYS template, "what you see is what you sign"). |

---

## Contents

- [Start here](#start-here)
- [Quick start in IntelliJ](#quick-start-in-intellij)
- [Run the login in a browser](#run-the-login-in-a-browser)
- [Run with Docker](#run-with-docker)
- [What the sample demonstrates](#what-the-sample-demonstrates)
- [Authentication, not identification](#authentication-not-identification)
- [Two client authentication methods](#two-client-authentication-methods)
- [Project structure](#project-structure)
- [Login flow](#login-flow)
- [Keys](#keys)
- [Configuration](#configuration)
- [Authorization request](#authorization-request)
- [Token request](#token-request)
- [ID token validation](#id-token-validation)
- [Customer mapping](#customer-mapping)
- [Login in progress](#login-in-progress)
- [Stateless security configuration](#stateless-security-configuration)
- [Hand-over](#hand-over)
- [Confirmation of an operation](#confirmation-of-an-operation)
- [Error codes](#error-codes)
- [Rules you must not change](#rules-you-must-not-change)
- [Using the code in a real application](#using-the-code-in-a-real-application)
- [Points to confirm in itsme E2E](#points-to-confirm-in-itsme-e2e)
- [Test data](#test-data)

---

## Quick start in IntelliJ

Requirements: IntelliJ IDEA (Community edition is enough) and JDK 17 or later. Maven is bundled with IntelliJ.

Stack: Spring Boot 4.0.7 (Spring Framework 7, Spring Security 7, Jackson 3), Nimbus JOSE + JWT (managed by Spring Boot), H2, WireMock 3.

1. **Open the project**: *File → Open…*, select `pom.xml`, choose *Open as Project*. IntelliJ downloads the dependencies.
2. **Check the JDK**: *File → Project Structure → Project → SDK* = 17 or later.
3. **Run the tests**: select the run configuration **All tests** (top-right of the window) and click *Run*, or right-click `src/test/java` → *Run 'All Tests'*.

`ItsmeLoginFlowTest` covers the protocol with key pairs (keys, Request Object, client assertion, ID token, state). `ClientSecretLoginFlowTest` covers what differs with a client secret. From a terminal, `./mvnw verify` (`mvnw.cmd verify` on Windows) does the same; the Maven wrapper downloads Maven 3.9.11 on first use and checks its SHA-256.

---

## Run the login in a browser

1. Select the run configuration **VoltaHome (local, itsme stub)** and click *Run*. It starts `VoltaHomeApplication` with `-Dspring.profiles.active=local` and the key pairs method. **VoltaHome (local, itsme stub, client secret)** starts the client secret method instead (`-Ditsme.client-authentication=client-secret`).
   Without the shared run configuration: *Run → Edit Configurations → + → Application*, main class `com.example.voltahome.VoltaHomeApplication`, VM options `-Dspring.profiles.active=local`.
2. Open <http://localhost:8080> and click **Log in with itsme**.
3. The **itsme stub** page shows what the application asked for, read from the decrypted Request Object, and offers test itsme accounts:

| itsme account | Expected result |
|---|---|
| Alex Martin — itsme linked | The protected portal page (customer `C-1001`) |
| Sam Peeters — customer, itsme not linked | Error page: itsme account not linked |
| Someone who is not a customer | Error page: itsme account not linked (same answer as Sam: the application cannot tell them apart) |
| Robin Dubois — contract closed | Error page: account no longer active |
| Phone call in progress | Error page: login blocked |
| Fingerprint only (acr basic) | Error page (ID token rejected) |
| Cancel | Error page: login cancelled |

**Confirm an operation** (key pairs only): on the portal, click **Confirm with itsme**. The itsme stub shows the text the itsme app would display, then the same test accounts:

| itsme account | Expected result |
|---|---|
| Alex Martin | Back to the portal; the operation is listed as confirmed |
| Sam Peeters, someone who is not a customer, Robin Dubois | Error page: not the itsme account linked to your VoltaHome account (`wrong_person`) |
| Phone call in progress | Error page: operation blocked |
| Fingerprint only (acr basic) | Error page (ID token rejected) |
| Cancel | Error page: refused in itsme, nothing changed |

**Things to try**
- Key pairs: add `&acr_values=x` to the URL of the itsme stub page and reload. The stub says it ignores it: only the signed and encrypted Request Object counts.
- Client secret: remove `acr_values` from the URL of the itsme stub page and reload, then log in as Alex. itsme falls back to the basic level, and the application rejects the ID token: without a Request Object, the `acr` check on the response is the protection.

**How the itsme stub works** (`local` package): served by the application under `/itsme-stub/v2`, with its own key pairs (`ItsmeStubCrypto`, shared with the tests). It decrypts the Request Object when there is one and verifies our signature with our public keys, read from `/.well-known/jwks.json` like itsme would; checks the redirect URI, PKCE, a single-use code valid for 3 minutes and the client authentication of the configured method; and returns ID tokens encrypted for that method. A request with the confirmation service scope is a confirmation: the stub then requires a Request Object with the `free_text` template, checks the confirmation redirect URI, and shows the text to approve. `ItsmeStubGuard` stops the application if the `local` profile is used with non-localhost endpoints.

Every class of the stub is in the `local` package and starts with `ItsmeStub`. All of them except `ItsmeStubCrypto` exist only with the `local` profile. `ItsmeStubCrypto` is not a Spring bean: it is also the itsme side of the WireMock tests, and a deliberately independent implementation of the itsme cryptography, so that the tests do not check our code against itself. That is why it is public and lives in `src/main`. Two settings outside the package also serve local development: the `itsme-stub` block and `itsme.keys.ephemeral` in `application-local.yml` (throw-away keys generated by `ItsmeKeys`, refused with non-localhost endpoints).

---

## Run with Docker

Optional, for a machine without a JDK. `compose.yaml` builds no image: the official `maven:3.9.11-eclipse-temurin-17-noble` image runs Maven on a copy of the sources. The checkout is mounted read-only, so nothing is written to it; downloaded dependencies are kept in a named volume.

| Command | What it does |
|---|---|
| `docker compose up demo` | Browser demo, key pairs, <http://localhost:8080> (as in [Run the login in a browser](#run-the-login-in-a-browser)) |
| `docker compose --profile client-secret up demo-client-secret` | Browser demo, client secret method (same port: run one demo at a time) |
| `docker compose --profile verify run --rm verify` | Build and all tests (`mvn verify`) |

Keep the `8080:8080` port mapping. The itsme stub runs only with localhost endpoints, and the configured URLs (`application-local.yml`) point at `http://localhost:8080`, both for your browser and for the stub reading our JWKS inside the container.

The containers use `mvn` rather than `./mvnw`. The wrapper's pinned checksum is for the `.zip` distribution of Maven, and on a system without `unzip`, as in this image, the wrapper downloads the `.tar.gz` instead, which fails that check.

---

## What the sample demonstrates

| Topic | Choice in the sample |
|---|---|
| Protocol | OpenID Connect Authorization Code flow, itsme API v2 |
| Client authentication | Both itsme methods, one switch: key pairs (`private_key_jwt`) or client secret — see the next section |
| Authorization request | PKCE (S256); with key pairs, a Request Object signed by us and encrypted for itsme |
| ID token | Signed (RS256) by itsme, encrypted for us (method-dependent) |
| Scope | Authentication only, no identification |
| Customer mapping | itsme `sub`, already linked to the customer; an unknown `sub` is refused |
| Data requested from itsme | No identity attribute; the `ongoing_call` fraud signal only |
| Backend | Stateless: no `HttpSession`; login in progress stored in the database |
| itsme endpoints | Static configuration (outbound proxies and firewalls opened in advance); the discovery document is only used to verify it |
| Session after login | Out of scope: the result is handed over to the application's own token management |
| Confirmation of an operation | From the portal, key pairs only: WYSIWYS `free_text` template in the Request Object; accepted only from the itsme account linked to the logged-in customer |

---

## Authentication, not identification

itsme can do two different things for an application:

- **Authentication**: itsme confirms that the person approving in the app holds a given itsme account, at the requested level (`acr_advanced`). The application receives a `sub`: a stable pseudonym, specific to the application, that says *it is the same itsme account as last time*, not *who* the person is.
- **Identification**: itsme also shares verified identity data about the person (name, birth date, address, national number, and so on), and the application uses it to decide who the person is.

**The sample does authentication only.**

- It requests no identity claim: the scope is `openid` and the itsme service, and the only claim is a fraud signal (`ongoing_call`). Tests: `authorizationRequestAsksForFraudSignalsOnlyNoIdentityClaim`, with both methods. The [confirmation](#confirmation-of-an-operation) adds the text to approve and nothing else (`confirmationRequestAsksForTheWysiwysTemplateAndFraudSignalsOnlyNoIdentityClaim`).
- Claims itsme might send anyway are not used and not handed over (`identityClaimsSentByItsmeAreNotHandedOver`). In particular, itsme's `email` is never used to find a customer: itsme does not verify it (`unknownItsmeAccountIsNotIdentifiedByTheEmailItsmeSends`).
- The customer is found by the `sub` alone, in `itsme_link`:

| itsme `sub` | Result |
|---|---|
| Linked to an active customer | Login; the hand-over carries our customer id |
| Linked to an inactive customer | Refused: `no_customer` |
| Not linked | Refused: `not_linked` |

An unknown `sub` is refused rather than handled, because without identification the application has nothing to work with: it cannot tell an existing customer who has not linked itsme from someone who is not a customer, and it does not try.

**Where links come from.** The login reads `itsme_link` and never writes a link. In the sample, links are test data (`data.sql`, and `givenLinked` in the tests). Creating them is out of scope; it is the job of a separate, deliberate step, such as the identification described below.

The same step handles a re-created itsme account. When a user deletes their itsme account and creates a new one, itsme issues a new `sub`, and its documentation asks the application to bind the new `sub` to the customer, replacing the old one. Until that happens, the login refuses the new `sub` with `not_linked`.

### If identification is added later

Identification is a separate feature, not an option of this login. Adding it means:

1. **A separate entry point**, such as "link itsme to my account" or "become a customer", with its own intent. The intent is stored with the login in progress (`itsme_authorization_request`), never read from the callback URL.
2. **Identity claims requested for that intent only.** With key pairs, the Request Object protects them; with the client secret, they travel in the URL and can be edited, so the callback must check what was actually returned.
3. **Matching the verified identity to a customer**, or creating one, and only then writing the `itsme_link` row. This is the step that establishes `sub` → customer.
4. **A separate carrier for identity data.** `ItsmeLoginResult` and the principal stay free of itsme claims.
5. **itsme onboarding and legal checks**: the itsme service must allow those claims, and some (the national number in particular) need a legal basis.
6. **A test for every new rule**, in the style of the existing ones.

The login described in this README stays as it is: the authentication of an already linked `sub`.

---

## Two client authentication methods

itsme offers two ways for the application to authenticate. The sample supports both; `itsme.client-authentication` selects one. Everything else (customer mapping, stateless setup, network) is shared.

| | Key pairs (`private-key-jwt`, default) | Client secret (`client-secret`) |
|---|---|---|
| What itsme gives you | Nothing secret: you register the URL of your public keys | A shared secret |
| Token request | Client assertion signed with our private key | `client_id` + `client_secret` in the body (`client_secret_post`) |
| Authorization parameters | Inside a Request Object, signed by us, encrypted for itsme | In the URL: visible and editable in the browser |
| ID token encryption | RSA-OAEP-256 + A128CBC-HS256, with our public key | dir + A256GCM, with a key derived from the secret (OIDC Core §10.2, *to be confirmed in E2E*) |
| ID token signature | RS256, itsme's key | RS256, itsme's key |
| Protection of `acr_values` | The Request Object, and the `acr` check on the ID token | The `acr` check on the ID token only |
| If the secret leaks | No shared secret | Intercepted codes can be exchanged, captured ID tokens decrypted |
| Infrastructure | Public `/.well-known/jwks.json` (OV/EV certificate, < 1 s), key rotation | Secret in a vault, rotation arranged with itsme (restart needed) |
| Code | `ItsmeKeys`, `JwksController`, `ItsmeRequestObjectFactory` | `ItsmeCrypto.idTokenDecryptionKey` |

Choose key pairs when you can; choose the client secret when the inbound route for the public keys is not possible. In both cases, the ID token must be signed inside the encryption (`requireNestedSignedJwt`): encryption alone proves nothing about the origin.

---

## Project structure

```
compose.yaml                                 OPTIONAL: run the demo or the tests in a container
mvnw, mvnw.cmd, .mvn/wrapper/                Maven wrapper (Maven 3.9.11, checksum verified)

src/main/java/com/example/voltahome
├── itsme/
│   ├── ItsmeProperties.java                 configuration
│   ├── ItsmeKeys.java                       our key pairs (keystore, or ephemeral for local/tests)
│   ├── JwksController.java                  /.well-known/jwks.json: our public keys, for itsme
│   ├── ItsmeClientConfig.java               client registrations (login, confirmation), token client, ID token decoder
│   ├── ItsmeAuthorizationRequestResolver    PKCE + Request Object
│   ├── ItsmeRequestObjectFactory.java       signs and encrypts the Request Object
│   ├── ItsmeClaims.java                     requested claims: ongoing_call; for a confirmation, also the free_text template
│   ├── ItsmeCrypto.java                     nested signed JWT check
│   ├── AcrValidator.java                    authentication level check
│   ├── ItsmeOidcUserService.java            customer by sub, fraud checks, principal without itsme claims
│   ├── ItsmeLinkRepository.java             itsme sub → customer (read only)
│   ├── JdbcAuthorizationRequestRepository   login in progress in the database
│   ├── NoOpAuthorizedClientRepository       the itsme access token is not stored
│   ├── ItsmeDiscoveryCheck.java             configuration vs. discovery document
│   ├── ItsmeLoginSuccessHandler.java        hand-over
│   ├── ItsmeLoginFailureHandler.java        error code redirect
│   ├── LoginHandover.java                   boundary with token management
│   ├── ItsmeLoginResult.java                what is handed over (no itsme claim)
│   ├── ItsmeConfirmationService.java        confirmation of an operation: start, then check itsme's answer and the person
│   ├── ItsmeOperation.java                  what the customer is asked to approve
│   ├── ItsmeFreeText.java                   the text shown in the itsme app (free_text template)
│   ├── JdbcConfirmationRequestRepository    confirmation in progress in the database
│   ├── ItsmeConfirmationResult.java         a confirmed operation (no itsme claim)
│   └── ItsmeConfirmationException.java      not confirmed: error code
├── security/SecurityConfig.java             stateless filter chain
├── customer/                                customer lookup (stands in for the existing customer table)
├── fraud/                                   fraud signals (demo implementation)
├── demo/                                    DEMO ONLY: home page, error page, hand-over issuing a demo session
│                                            cookie, protected portal page and its filter chain, the "Confirm with
│                                            itsme" button and callback (DemoConfirmationController), confirmed
│                                            operations (DemoConfirmedOperations)
└── local/                                   LOCAL ONLY (profile "local"): the itsme stub, a stand-in for itsme
    ├── ItsmeStubController.java             the stub's endpoints and its approval page (/itsme-stub/v2)
    ├── ItsmeStubScenario.java               test itsme accounts offered by the stub page
    ├── ItsmeStubCrypto.java                 itsme's side of the crypto; also used by the tests (see below)
    ├── ItsmeStubGuard.java                  stops the application if the stub would run with non-localhost endpoints
    └── ItsmeStubSecurityConfig.java         filter chain that opens /itsme-stub/** without login

src/test/java/com/example/voltahome
├── support/ItsmeIntegrationTest.java        application + H2 + WireMock itsme, helpers
├── support/ItsmeWireMock.java               WireMock stubs + ID token builder (itsme format)
├── support/MutableClock.java                test clock, to test expiry without waiting
├── support/TestClockConfig.java             makes MutableClock the application clock in tests
├── itsme/ItsmeLoginFlowTest.java            protocol scenarios, key pairs
├── itsme/ClientSecretLoginFlowTest.java     what differs with a client secret
├── itsme/ItsmeCryptoTest.java               nested signed JWT check
├── itsme/ItsmeKeysTest.java                 ephemeral keys refused outside localhost
├── itsme/ItsmeConfirmationFlowTest.java     confirmation scenarios: the right person, the ID token, the state
├── itsme/ItsmeFreeTextTest.java             the text shown in the itsme app
├── demo/DemoSessionTest.java                protected portal and its demo session cookie
└── local/ItsmeStubGuardTest.java            itsme stub refused outside localhost
```

---

## Login flow

```mermaid
sequenceDiagram
    autonumber
    actor U as Customer
    participant B as Backend (Spring Security)
    participant I as itsme
    participant D as Database

    U->>B: GET /oauth2/authorization/itsme
    Note over B: state, nonce, code_verifier stored (5 min)<br/>Request Object signed by us, encrypted for itsme
    B-->>U: 302 to itsme (request, PKCE)
    U->>I: Approves in the itsme app
    I-->>U: 302 to /login/oauth2/code/itsme with code and state
    U->>B: Callback
    B->>I: POST /token (signed client assertion + code_verifier)
    I-->>B: ID token, signed by itsme, encrypted for us
    Note over B: Decrypt, verify signature, iss, aud, exp, nonce, acr
    B->>D: Customer by sub
    alt sub linked to an active customer
        Note over B: Hand-over to token management
    else sub unknown
        B-->>U: 302 to the error page (not_linked)
    end
```

---

## Keys

Key pairs method only. The application owns two RSA key pairs (itsme supports RSA only), loaded by `ItsmeKeys`:

| Key | Our private key is used to | itsme uses our public key to |
|---|---|---|
| Signing (`sig`, RS256) | Sign the Request Object and the client assertion | Verify those signatures |
| Encryption (`enc`, RSA-OAEP-256) | Decrypt ID tokens | Encrypt ID tokens for us |

- **Production**: a PKCS12 keystore provided by a vault (`itsme.keys.keystore`, `keystore-password`, aliases). Private keys never go to Git or a log.
- **Local and tests**: `itsme.keys.ephemeral=true` generates throw-away keys at startup. It is refused with non-localhost endpoints.
- **Publication**: `/.well-known/jwks.json` serves the public keys. itsme requires a public HTTPS URL with an OV or EV certificate, no authentication, and a response under one second. Its URL is given to itsme at onboarding.
- **Rotation** (itsme documentation, *Key rotation*): itsme caches our JWKS for 30 minutes to 24 hours. For the signing key: publish the new key with a new `kid`, start signing with it, then remove the old one. For the encryption key: publish the new key, keep both able to decrypt for 24 hours, then remove the old one. `encryption-aliases` accepts several aliases for that period.

---

## Configuration

`application.yml`, prefix `itsme`. Endpoints are **static**: Spring Boot's `issuer-uri` is not used, so startup does not depend on reaching itsme, and the hosts are known in advance for proxy and firewall requests.

| Property | Description |
|---|---|
| `client-id`, `service-code` | Issued by itsme at onboarding |
| `client-authentication` | `private-key-jwt` (default) or `client-secret` |
| `client-secret` | Client secret method only; injected from a vault |
| `redirect-uri` | Absolute URL, identical to the value registered at itsme |
| `acr` | `http://itsme.services/v2/claim/acr_advanced` (itsme code required) |
| `endpoints.issuer` | Copy the exact value from the discovery document of each environment |
| `endpoints.authorization-uri`, `token-uri` | `https://idp.[e2e/prd].itsme.services/v2/...` |
| `endpoints.jwk-set-uri` | Value of `jwks_uri` in the discovery document |
| `endpoints.discovery-uri` | Used only by `ItsmeDiscoveryCheck` |
| `proxy.host`, `proxy.port` | Outbound proxy; empty host = direct connection |
| `keys.*` | Key pairs method only; see [Keys](#keys) |
| `confirmation.service-code` | The itsme service registered as a *Confirmation* service, issued at onboarding |
| `confirmation.redirect-uri` | Absolute URL of `/portal/confirmation/callback`, identical to the value registered at itsme |

Outside the `itsme` prefix: `app.login-error-url` (where `ItsmeLoginFailureHandler` redirects).

Network flows: outbound from the backend to one itsme host per environment (token endpoint, itsme JWKS, discovery), and, with key pairs only, **inbound** from itsme to our `/.well-known/jwks.json`. itsme requires TLS SNI.

`ItsmeDiscoveryCheck` compares the configuration with the discovery document at startup and every day at 06:00. It returns `OK`, `MISMATCH` (logged at `ERROR`, to be wired to alerting) or `UNREACHABLE` (logged as a warning). It never blocks startup.

---

## Authorization request

`ItsmeAuthorizationRequestResolver` uses Spring's resolver (state, nonce, PKCE) and adds a **Request Object** built by `ItsmeRequestObjectFactory`: all the parameters, plus `acr_values` (`acr_advanced`) and `claims` (`ongoing_call` only), signed with our private key, then encrypted with itsme's public key.

`acr_values` and `claims` exist **only** inside the Request Object. Editing the browser URL cannot change them.

With the client secret method there is no Request Object: a customizer adds `acr_values` and `claims` to the URL, where they can be edited. The `acr` check on the ID token is then the only protection of the authentication level.

---

## Token request

Key pairs (`private_key_jwt`): Spring builds the client assertion (`iss` and `sub` = client id, `aud` = token endpoint, `jti`, `iat`, `exp`), signs it with our signing key, and adds the PKCE `code_verifier`. Client secret: Spring sends `client_id`, `client_secret` and `code_verifier` in the body. Error responses are read with itsme's field names: itsme puts the description in `detail` instead of `error_description`, and the sample keeps it for the logs (`tokenEndpointErrorDetailIsKept`). The `RestClient` has an explicit outbound proxy: by default the JDK `HttpClient` follows the JVM-wide proxy settings, and setting it explicitly keeps the route to itsme visible in the itsme configuration.

---

## ID token validation

Spring's default ID token decoder cannot decrypt JWE. `ItsmeClientConfig.idTokenDecoderFactory` replaces it:

| Check | Where |
|---|---|
| Encrypted and nested signed JWT (`cty: JWT`) | `ItsmeCrypto.requireNestedSignedJwt` |
| Decryption: RSA-OAEP-256 / A128CBC-HS256 with our private key, or dir / A256GCM with the key derived from the secret | Nimbus `JWEDecryptionKeySelector` |
| Signature RS256 with itsme's JWKS | Nimbus `JWSVerificationKeySelector` |
| `iss`, `aud`, `iat` | Spring `OidcIdTokenValidator` |
| `exp` (60 s skew) | Spring `JwtTimestampValidator` |
| `nonce` | Spring (built in) |
| `acr` | `AcrValidator` — *presence of the claim to be confirmed in E2E* |

Why the nested check matters: with key pairs, our encryption key is **public**, so anyone can encrypt a token for us; with a client secret, anyone holding the secret can. Only itsme's signature proves where the token comes from.

---

## Customer mapping

`ItsmeOidcUserService` replaces Spring's `OidcUserService`, which would copy every claim into the principal. It:

1. rejects the login if a phone call was in progress (`ongoing_call`);
2. looks up the customer by the itsme `sub` (`ItsmeLinkRepository`);
3. unknown `sub` → `not_linked` (see [Authentication, not identification](#authentication-not-identification));
4. linked but inactive customer → `no_customer`;
5. linked and active → the principal carries the `sub` and our customer id, nothing else.

---

## Login in progress

Between the redirect to itsme and the callback, `state`, `nonce` and `code_verifier` must survive, possibly on another instance. `JdbcAuthorizationRequestRepository` stores them in `itsme_authorization_request` for 5 minutes. At callback time the row is read and deleted; checking the number of deleted rows makes it single-use without database-specific SQL, even when two callbacks arrive at the same time. Abandoned rows are purged every 15 minutes.

---

## Stateless security configuration

`SecurityConfig` replaces every component that would create an `HttpSession`:

| Setting | Default replaced |
|---|---|
| `JdbcAuthorizationRequestRepository` | `HttpSessionOAuth2AuthorizationRequestRepository` |
| `NoOpAuthorizedClientRepository` | `HttpSessionOAuth2AuthorizedClientRepository` |
| `RequestAttributeSecurityContextRepository` | `HttpSessionSecurityContextRepository` |
| `NullRequestCache` | `HttpSessionRequestCache` |
| Custom success handler | `SavedRequestAwareAuthenticationSuccessHandler` |

CSRF protection is disabled in this chain because it serves no form: the login is a GET redirect, and the callback is protected by `state`. An application with forms keeps its existing CSRF setup. The portal chain has a form (the confirmation button) and keeps CSRF protection on, with the token in a cookie (`CookieCsrfTokenRepository`) instead of the session. The tests `noSessionIsCreatedDuringTheWholeFlow` and `noSessionIsCreatedDuringTheWholeConfirmation` verify that no session and no `JSESSIONID` cookie are created.

---

## Hand-over

The itsme integration ends with the success handler passing an `ItsmeLoginResult` (customer id, method, level, time — no itsme claim) to a `LoginHandover`. Issuing the application's own token or session is out of scope. The callback is a browser navigation, not an XHR call: the hand-over answers with a page or a redirect.

The `demo` package shows the shape of a real hand-over, with a stand-in for the application's session:

- `DemoLoginHandover` asks `DemoSession` for a cookie holding a JWT signed by the application (HS256, 15 minutes, `HttpOnly`, `Secure`, `SameSite=Lax`, path `/portal`), then redirects to `/portal`;
- `DemoSecurityConfig` adds a filter chain for `/portal/**`, evaluated before the main one. Its `DemoSessionFilter` checks the cookie on every request; without a valid cookie, the browser is sent back to the home page;
- `/portal` shows the customer from our own customer table.

The signing key is generated at startup and kept in memory: a restart logs everyone out, and several instances would not share it. It is a demo, not a session design. The itsme login itself never makes a later request authenticated (`RequestAttributeSecurityContextRepository` keeps it for the callback request only); only the application's own credential does. `DemoSessionTest` lists the attacks on the demo cookie.

---

## Confirmation of an operation

itsme offers a third service type besides authentication and identification: **confirmation**. The customer approves one operation in the itsme app, and the app shows the operation's text before the approval. The sample adds it to the protected portal: a **Confirm with itsme** button sets the monthly advance payment to EUR 85.00 for the logged-in customer.

### What the itsme documentation says

Source: the public [Confirmation](https://belgianmobileid.github.io/doc/confirmation/) and [JOSE](https://belgianmobileid.github.io/doc/JOSE/) pages of the itsme v2 documentation.

- **Same protocol as the login.** The confirmation uses the same endpoints, parameters, token request and ID token as authentication. The differences: a service registered as a Confirmation service (`scope=openid service:<code>`), and a *WYSIWYS template* in the `claims` parameter, which is mandatory.
- **Two templates.** `adv_payment` takes an amount ("a string holding an integer"), a currency and an IBAN. `free_text` takes a text that may use `<b>`, `<i>`, `<u>` and `<br>`, up to 7500 characters, in the ISO-8859-15 character set. Every template claim is `essential: true`, under `http://itsme.services/v2/claim/`.
- **A Request Object is required** (`request` or `request_uri`), signed (RS256) and then encrypted.
- **Nothing comes back about the operation.** The answer is the usual ID token. There is no approval proof, no echo of the text, and no hash of it.

### What it means for the application

Because itsme returns no proof, **the application's own checks are what make an approval count**:

| Question | Answered by | Code |
|---|---|---|
| Is this answer for this confirmation? | `state` (single use, 5 minutes), `nonce`, PKCE | `JdbcConfirmationRequestRepository`, Spring's `OidcAuthorizationCodeAuthenticationProvider` |
| Was the text the customer saw the one we sent? | Text fixed on the server, inside the signed and encrypted Request Object; never in the URL, never read from the callback | `DemoConfirmationController`, `ItsmeRequestObjectFactory` |
| Is it the customer who started it? | The customer of the session on the callback must be the one stored at the start | `ItsmeConfirmationService.complete` (`wrong_customer`) |
| **Was it approved by the right person?** | The ID token `sub` must be the itsme account linked to that customer *now*. Any other itsme account is refused and reported as a fraud signal (`WRONG_ITSME_ACCOUNT`) | `ItsmeConfirmationService.complete` (`wrong_person`) |
| Was the itsme code used? | `acr_advanced`, as for the login | `AcrValidator` |
| Was the customer on the phone with someone? | `ongoing_call`, refused and reported, as for the login | `ItsmeConfirmationService.complete` (`fraud_suspected`) |
| Is the ID token from itsme? | Nested signed JWT, decryption, signature, `iss`, `aud`, `exp` | The same decoder as the login (`idTokenDecoderFactory`) |

Without the `sub` check, any itsme account could approve the operation, for example a fraudster's own account, in a session where the fraudster is logged in as the victim.

### Choices made in the sample

- **Key pairs only.** itsme requires a signed Request Object for a confirmation. The client secret method has no signing key, and without a Request Object the text would travel in the URL, where it can be edited, with nothing in the answer to detect it. With `client-secret`, the portal does not show the button and `ItsmeConfirmationService.start` refuses (`confirmation_unavailable`).
- **`free_text` only.** `adv_payment` describes the amount as "a string holding an integer" without saying in which unit (euros or cents). Add it once that is confirmed with itsme.
- **Text built, not escaped.** `ItsmeFreeText` emits only the tags itsme renders, checks the length and the character set, and refuses `<`, `>` and `&` in the text: the documentation does not say whether HTML entities are decoded.
- **Template under `id_token`.** The documentation does not say whether the template goes under `id_token` or `userinfo`. The sample uses `id_token`, like the login, and never calls UserInfo (*to be confirmed in E2E*).
- **Not a login.** The confirmation registration (`itsme-confirmation`) is not in the login's `ClientRegistrationRepository`, and its callback is under `/portal`, outside `oauth2Login()`. A confirmation `state` cannot end a login, and a login `state` cannot end a confirmation. The confirmation authenticates nobody: its result is never put in a security context.
- **In the portal.** The button is a POST form protected against CSRF. The callback is in the portal filter chain: without a valid demo session, the browser is sent to the home page and nothing is executed.
- **The result is the evidence.** `ItsmeConfirmationResult` carries the customer, the operation reference, the exact text approved, the level and the time, and no itsme claim. A real application executes the operation and keeps this record in its audit trail. The demo keeps it in memory (`DemoConfirmedOperations`) and lists it on the portal.

### Flow

```mermaid
sequenceDiagram
    autonumber
    actor U as Customer (logged in)
    participant P as Portal (demo session)
    participant S as ItsmeConfirmationService
    participant I as itsme
    participant D as Database

    U->>P: POST /portal/confirmation (CSRF token)
    P->>S: start(customer, operation)
    S->>D: state, nonce, code_verifier, customer, text (5 min)
    S-->>U: 302 to itsme (Request Object with the text)
    U->>I: Reads the text, approves in the itsme app
    I-->>U: 302 to /portal/confirmation/callback with code and state
    U->>P: Callback (demo session cookie)
    P->>S: complete(request, customer of the session)
    S->>D: Read and delete by state (single use)
    S->>I: POST /token
    Note over S: ID token checks, as for the login<br/>same customer, ongoing_call, sub = linked sub
    alt approved by the linked itsme account
        P-->>U: Operation executed, 302 to /portal
    else anything else
        P-->>U: 302 to the error page, nothing executed
    end
```

### Tests

`ItsmeConfirmationFlowTest` lists the attacks, each against an otherwise valid confirmation by Alex: `unknownItsmeAccountCannotConfirmAndIsReported`, `itsmeAccountOfAnotherCustomerCannotConfirm`, `itsmeAccountLinkedWhenTheConfirmationStartedButReplacedSinceCannotConfirm`, `confirmationStartedByAnotherCustomerCannotBeEndedInThisSession`, `callbackWithoutSessionIsRefused`, `ongoingCallIsRejectedAndReported`, `customerNoLongerActiveCannotConfirm`, `confirmationCannotBeStartedWithoutCsrfToken`, `confirmationCannotBeStartedWithForgedCsrfToken`, `confirmationCannotBeStartedWithoutSession`, `loginEndpointCannotStartAConfirmation`, `loginStateCannotEndAConfirmation`, `confirmationStateCannotEndALogin`, `callbackCannotBeReplayed`, `confirmationExpiresAfterFiveMinutesAndIsPurged`, `noSessionIsCreatedDuringTheWholeConfirmation`, and the ID token checks (`basicAcrIsRejected`, `idTokenIssuedForAnotherRequestIsRejected`, `encryptedButUnsignedTokenIsRejected`). `ItsmeFreeTextTest` covers the text. `ClientSecretLoginFlowTest.confirmationIsNotOfferedWithTheClientSecretMethod` covers the client secret method.

---

## Error codes

`ItsmeLoginFailureHandler` redirects to `app.login-error-url?error=<code>`. The page maps each code to a fixed message (`DemoController`); it never displays the raw `error` parameter, which comes from the URL.

| Code | Cause |
|---|---|
| `access_denied` | User cancelled in itsme |
| `authorization_request_not_found` | Unknown, expired (> 5 min) or already used `state` |
| `invalid_grant` | Authorization code expired (3 minutes), already used, or PKCE mismatch |
| `invalid_client`, `unauthorized_client` | Client assertion or secret refused: signing key, JWKS, `aud`, or client not allowed (itsme uses `unauthorized_client` for an invalid `client_assertion`) |
| `invalid_id_token` | Decryption, signature, `iss`, `aud`, `exp` or `acr` check failed |
| `invalid_nonce` | Nonce mismatch |
| `invalid_token_response` | Token endpoint unreachable or unreadable response |
| `not_linked` | The itsme account is not linked to a customer |
| `no_customer` | The linked customer is no longer active |
| `fraud_suspected` | Phone call in progress during approval |

The confirmation redirects to `/portal/confirmation/error?error=<code>` (`DemoConfirmationController`, fixed messages too). It passes on the codes above that come from itsme's answer and Spring's checks (`access_denied`, `invalid_grant`, `invalid_client`, `unauthorized_client`, `invalid_id_token`, `invalid_nonce`, `invalid_token_response`), uses `fraud_suspected` and `no_customer` with the same meaning, and adds:

| Code | Cause |
|---|---|
| `confirmation_not_found` | Unknown, expired (> 5 min) or already used `state` |
| `wrong_customer` | The callback arrived in the session of another customer than the one who started the confirmation |
| `wrong_person` | Approved with an itsme account that is not the one linked to the customer (also a fraud signal) |
| `not_linked` | At the start: the customer has no linked itsme account, so nobody could confirm |
| `confirmation_unavailable` | Client secret method: confirmation is not offered |
| `invalid_request` | Callback with neither `code` nor `error` |

---

## Rules you must not change

Each rule below protects against an attack or an operational failure. Changing one is a design change: agree it with your solution architect first. A new security rule comes with a new test.

| # | Rule | Why | Guarded by |
|---|---|---|---|
| 1 | **No hand-written cryptography.** Only Nimbus and Spring Security calls, with explicit algorithms. The one exception is the key derivation of the client secret method (`ItsmeCrypto.idTokenDecryptionKey`, OIDC Core §10.2), and its deliberately independent copy in `ItsmeStubCrypto`, which must not call production code: it is the reference the tests check us against. | Crypto mistakes do not show in a demo; they show in an incident. | The `IdTokenValidation` tests; `tokenEncryptedWithAnotherSecretIsRejected` |
| 2 | **The ID token must be signed inside the encryption** (`ItsmeCrypto.requireNestedSignedJwt`). | Our encryption key is public (key pairs) or shared (client secret): anyone can encrypt a token for us. Only itsme's signature proves where it comes from. | `encryptedButUnsignedTokenIsRejected` (both methods), `plainJwsIsNotAcceptedAsIdToken` |
| 3 | **Keep `AcrValidator`.** If itsme E2E shows that `acr` is not sent, report it; do not remove the check. | Without it, a fingerprint approval (or, with the client secret, an edited URL) passes as an itsme-code approval. | `missingAcrIsRejected`, `basicAcrIsRejected`, `withoutRequestObjectTheAcrCheckOnTheResponseIsTheProtection` |
| 4 | **Find the customer by `sub` only.** Never by itsme's `email` or any other itsme claim. | itsme does not verify the email: anyone can put a victim's address in their own itsme account. | `unknownItsmeAccountIsNotIdentifiedByTheEmailItsmeSends` |
| 5 | **Authentication only.** Request no identity claim; refuse an unknown `sub`; never write `itsme_link` during the login. | See [Authentication, not identification](#authentication-not-identification). | `authorizationRequestAsksForFraudSignalsOnlyNoIdentityClaim` (both methods), `confirmationRequestAsksForTheWysiwysTemplateAndFraudSignalsOnlyNoIdentityClaim`, `unknownItsmeAccountIsRejected` |
| 6 | **No `HttpSession`, ever.** | The backend runs on several instances without sticky sessions; a session would break the callback. | `noSessionIsCreatedDuringTheWholeFlow`, `noSessionIsCreatedDuringTheWholeConfirmation` |
| 7 | **No itsme claim leaves `ItsmeOidcUserService`.** The principal carries the `sub` and our customer id; `ItsmeLoginResult` carries no itsme claim at all. | Personal data that is not passed on cannot leak, be logged or be misused downstream. | `identityClaimsSentByItsmeAreNotHandedOver` |
| 8 | **Static endpoints, no `issuer-uri`.** The discovery document is an alarm, never a source of configuration. | Startup must not depend on reaching itsme, and the hosts must be known in advance for proxies and firewalls. | `discoveryCheckReportsAMovedEndpoint`, `discoveryCheckReportsUnreachableItsme` |
| 9 | **Explicit outbound proxy** on the `RestClient` and on the Nimbus `DefaultResourceRetriever`. | JVM-wide proxy settings change without notice; the route to itsme must be visible in the itsme configuration. | Code review (no automated test) |
| 10 | **Ephemeral keys and the itsme stub refuse non-localhost endpoints** (`ItsmeKeys`, `ItsmeStubGuard`). | Throw-away keys or a fake itsme must never reach a shared environment. | `ephemeralKeysAreRefusedWithARealItsmeEndpoint`, `itsmeStubIsRefusedWithARealItsmeEndpoint` |
| 11 | **Single-use login state:** read the row, delete it, and accept it only if exactly one row was deleted. No database-specific SQL. | Two callbacks with the same `state` (double click, replay) must not both succeed. | `callbackCannotBeReplayed`, `concurrentCallbacksWithTheSameStateSucceedOnlyOnce`, `loginInProgressExpiresAfterFiveMinutesAndIsPurged` |

---

## Using the code in a real application

1. Copy the `itsme` and `security` packages; adapt the package names. Keep the [rules](#rules-you-must-not-change) and their tests.
2. Replace `customer/` with the application's customer lookup and `fraud/` with the real fraud monitoring.
3. Implement `LoginHandover` with the application's token management; delete `demo/` and `local/` (or keep `local/` for local development only).
4. Create the tables of `schema.sql` (except `customer`) in the application's database, and decide how `itsme_link` rows are created: the login only reads them (see [Authentication, not identification](#authentication-not-identification)).
5. Choose the method. Key pairs: generate the two key pairs, store them in the vault, and publish `/.well-known/jwks.json` on a public host with an OV/EV certificate. Client secret: store the secret in the vault.
6. Fill in the configuration per environment from itsme onboarding and the discovery document; give itsme the redirect URI, and with key pairs the JWKS URL.
7. Ask itsme to enforce PKCE for the client.
8. For the confirmation: register a separate Confirmation service and its redirect URI at itsme, replace `DemoConfirmationController` with the application's operations, and keep each `ItsmeConfirmationResult` in the operation's audit trail.
9. Run the E2E checks below.

---

## Points to confirm in itsme E2E

- The ID token contains the `acr` claim (otherwise `AcrValidator` rejects every login). The itsme documentation does not say that it does.
- Key pairs: ID token encrypted RSA-OAEP-256 / A128CBC-HS256, containing a JWS signed RS256. The documentation does not fix these values, and the discovery document also allows RSA-OAEP and A256GCM. itsme accepts the Request Object as built here.
- Client secret: ID token encrypted dir / A256GCM with the key derived from the secret as in OIDC Core §10.2, containing a JWS signed RS256. With this method itsme lets you choose RS256 or HS256 at registration: choose **RS256**, the only signature the sample accepts.
- Client secret: clients onboarded before 25 June 2025 use other endpoints (`https://oidc.[e2e/prd].itsme.services/clientsecret-oidc/csapi/v0.1/...`), and the documentation's example ID token shows an `iss` on the `oidc.` host. Copy the endpoints and the `issuer` from the discovery document of your own client.
- Exact `issuer` and `jwks_uri` values in the discovery document (in PRD, `jwks_uri` is `https://idp.prd.itsme.services/v2/jwkSet`).
- Confirmation: itsme accepts the `free_text` template under the `id_token` member of `claims` (the documentation does not say `id_token` or `userinfo`), renders `<b>` and `<br>` as expected, and returns `acr` in the ID token. Check how a refusal in the app is reported (expected: `access_denied`).
- Confirmation: whether HTML entities (`&amp;`) are decoded in `free_text`, and the unit of the `adv_payment` amount, before supporting either.
- Confirmation: itsme also documents a `sub` claim request (`"userinfo": {"sub": {"value": ...}}`). If itsme then only accepts that account, it would add a check on itsme's side to ours. The sample does not send it: the documented behaviour is too thin. Our `sub` check stays either way.

---

## Test data

All data is fictitious. Email addresses use `example.com`, a domain reserved for documentation. The sample requests no identity attribute from itsme.

| Customer | Email | Status |
|---|---|---|
| C-1001 Alex Martin | alex.martin@example.com | Active, itsme linked (`stub-sub-alex`) |
| C-1002 Sam Peeters | sam.peeters@example.com | Active, itsme not linked: the itsme login is refused |
| C-1003 Robin Dubois | robin.dubois@example.com | Inactive, itsme linked (`stub-sub-robin`) |

itsme® is a registered trademark of Belgian Mobile ID. This sample is not affiliated with or endorsed by Belgian Mobile ID.

Licensed under the MIT License; see [`LICENSE`](LICENSE).
