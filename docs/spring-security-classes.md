# Spring Security classes in this sample, explained

This page is for a developer who is new to Spring Security, or who has only used `oauth2Login()` with its defaults. It lists every Spring Security class the sample uses, in the order they come into play during a login, on the protected page that follows, and when the customer confirms an operation from that page, and explains in plain words what each one is for.

The [README](../README.md) is the reference for behaviour, and the [walkthrough](walkthrough.md) explains the design choices. This page answers a simpler question: "I see this class in the code. What is it, and why is it here?"

## Contents

- [How to read this page](#how-to-read-this-page)
- [The big picture: a chain of filters](#the-big-picture-a-chain-of-filters)
- [1. Building the filter chain](#1-building-the-filter-chain)
- [2. Who may open which URL](#2-who-may-open-which-url)
- [3. Staying stateless](#3-staying-stateless)
- [4. Describing itsme to Spring](#4-describing-itsme-to-spring)
- [5. Step one: sending the customer to itsme](#5-step-one-sending-the-customer-to-itsme)
- [6. Remembering the login in progress](#6-remembering-the-login-in-progress)
- [7. Step two: the callback and the token request](#7-step-two-the-callback-and-the-token-request)
- [8. Checking the ID token](#8-checking-the-id-token)
- [9. Turning the ID token into a user](#9-turning-the-id-token-into-a-user)
- [10. The end of the login: success or failure](#10-the-end-of-the-login-success-or-failure)
- [11. After the login: the protected portal](#11-after-the-login-the-protected-portal)
- [12. Confirming an operation from the portal](#12-confirming-an-operation-from-the-portal)
- [Index](#index)

## How to read this page

Two kinds of classes appear below:

- **Spring classes** (package `org.springframework.security...`): provided by the framework. You configure them, you rarely read their code.
- **Our classes** (package `com.example.voltahome...`): written for this sample. Most of them implement a Spring interface, to replace one default behaviour that does not fit itsme. Classes in `demo/` are demo stand-ins, not meant to be copied.

A useful habit: when you see one of our classes, ask "which Spring interface does it implement, and which default does it replace?" The answer almost always explains why the class exists.

## The big picture: a chain of filters

Every HTTP request that reaches the application first passes through a **chain of servlet filters** set up by Spring Security. Each filter has one job: "is this the login URL?", "is this the callback URL?", "is this person allowed to open this page?". A filter either handles the request itself (and stops the chain), or passes it on to the next filter. Only when every filter has let the request through does it reach a controller.

For the itsme login, three filters matter:

| Filter (Spring class) | Reacts to | What it does |
|---|---|---|
| `OAuth2AuthorizationRequestRedirectFilter` | `GET /oauth2/authorization/itsme` | Prepares the login and redirects the browser to itsme. |
| `OAuth2LoginAuthenticationFilter` | `GET /login/oauth2/code/itsme` (the callback) | Exchanges the code for an ID token, checks it, finds the customer, ends the login. |
| `AuthorizationFilter` | Every other request | Lets the request through or refuses it, according to the rules in `authorizeHttpRequests`. |

The protected portal has its own small chain, with one filter of ours, described in [section 11](#11-after-the-login-the-protected-portal). Apart from that one, you never create these filters yourself. `oauth2Login()` and `authorizeHttpRequests()` in `SecurityConfig` add them, and the classes described below are the pieces you plug into them.

## 1. Building the filter chain

**`SecurityFilterChain`** (Spring) — *file: `security/SecurityConfig.java`*

The finished chain of filters, as a Spring bean. Declaring a `SecurityFilterChain` bean is how you tell Spring Boot: "do not use your default security, use this one". The sample has one main chain, built in `SecurityConfig.securityFilterChain`.

**`HttpSecurity`** (Spring) — *file: `security/SecurityConfig.java`*

The builder that produces a `SecurityFilterChain`. Each method call (`.sessionManagement(...)`, `.oauth2Login(...)`, ...) adds or configures filters. At the end, `http.build()` returns the chain. Think of it as a form you fill in; Spring then assembles the filters from your answers.

**`AbstractHttpConfigurer`** (Spring) — *files: `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java`*

The base class of every section of `HttpSecurity` (CSRF, sessions, login...). The sample only uses it through the method reference `AbstractHttpConfigurer::disable`, which is the standard way to switch a section off: `.csrf(AbstractHttpConfigurer::disable)` means "no CSRF filter in this chain". The comment in `SecurityConfig` explains why CSRF is not needed for this login, and why a real application keeps its own CSRF setup.

**A second chain: `ItsmeStubSecurityConfig`** (ours) — *file: `local/ItsmeStubSecurityConfig.java`*

An application may have several `SecurityFilterChain` beans. For each request, Spring tries them in order and uses the first one whose `securityMatcher` matches the URL. `ItsmeStubSecurityConfig` adds a small chain, active only with the `local` profile, that matches `/itsme-stub/**` and lets everything through, so that the local itsme stub page can be opened without being logged in. `@Order(1)` makes Spring try it before the main chain. In production this chain does not exist.

## 2. Who may open which URL

**`authorizeHttpRequests(...)`** (Spring configuration, which creates the `AuthorizationFilter`) — *file: `security/SecurityConfig.java`*

The list of access rules, read from top to bottom; the first matching rule wins:

- `requestMatchers("/", "/login-error", "/error").permitAll()`: the home page and the error pages are public;
- `requestMatchers("/.well-known/jwks.json").permitAll()`: itsme must be able to download our public keys without logging in;
- `anyRequest().authenticated()`: everything else requires a logged-in user.

The two login URLs (`/oauth2/authorization/itsme` and the callback) do not need a rule: their filters run earlier in the chain and answer before the `AuthorizationFilter` is reached.

When an anonymous browser asks for a protected URL, Spring does not simply answer 401. Because there is exactly one login provider configured, `oauth2Login()` sends the browser to `/oauth2/authorization/itsme`, which starts the itsme login.

## 3. Staying stateless

A classic Spring application remembers the logged-in user in an `HttpSession` (the `JSESSIONID` cookie). This backend must not create sessions (README: "Stateless security configuration"), so every default that would write to the session is replaced. The test `noSessionIsCreatedDuringTheWholeFlow` checks the result.

**`SessionCreationPolicy.STATELESS`** (Spring) — *file: `security/SecurityConfig.java`*

Tells Spring Security never to create an `HttpSession` and never to use one to find the user. It is the general switch; the next three classes cover the places where a session would otherwise still be used.

**`RequestAttributeSecurityContextRepository`** (Spring) — *file: `security/SecurityConfig.java`*

After a successful login, Spring stores "who is logged in" (the `SecurityContext`) in a *security context repository*. The default one, `HttpSessionSecurityContextRepository`, puts it in the session, so that the next request finds the user again. This replacement puts it in a request attribute instead: it lives only until the end of the current request. In other words, the login result is **not remembered** for the next request. That is intended: remembering the customer is the job of the application's own token, issued at the hand-over (see [section 11](#11-after-the-login-the-protected-portal)).

**`NullRequestCache`** (Spring) — *files: `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java`*

By default, when an anonymous user opens a protected page, Spring saves that request in the session (`HttpSessionRequestCache`) so that it can send the user back to it after the login. `NullRequestCache` is a cache that saves nothing, so no session is needed. Where the customer goes after the login is decided by the hand-over instead.

**`OAuth2AuthorizedClientRepository`, `OAuth2AuthorizedClient`** (Spring) and **`NoOpAuthorizedClientRepository`** (ours) — *file: `itsme/NoOpAuthorizedClientRepository.java`*

After an OAuth 2 login, Spring keeps the provider's access token (wrapped in an `OAuth2AuthorizedClient`) so that the application can later call the provider's APIs with it. The default repository stores it in the session. This sample never calls itsme after the login, so it has nothing to keep: `NoOpAuthorizedClientRepository` implements the interface and does nothing. "No-op" means "no operation".

## 4. Describing itsme to Spring

**`ClientRegistration`** (Spring) — *file: `itsme/ItsmeClientConfig.java`, method `clientRegistrationRepository`*

Everything Spring needs to know about one login provider, in one object: our client id, how we prove who we are, the scopes we ask for, the itsme URLs (authorization, token, public keys) and the expected issuer. The id of the registration, `itsme`, is the last part of the login and callback URLs (`/oauth2/authorization/itsme`, `/login/oauth2/code/itsme`).

In many tutorials this object is built by Spring from `spring.security.oauth2.client.*` properties, often with an `issuer-uri` that makes Spring download the provider's discovery document at startup. Here it is built in code from static configuration, so that the application starts even when itsme cannot be reached (design rule 8; README: "Configuration").

**`ClientRegistrationRepository`, `InMemoryClientRegistrationRepository`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

The place where Spring looks up a `ClientRegistration` by its id. The in-memory version simply holds a fixed list; here, a list of one.

**`ClientAuthenticationMethod`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

How our backend proves its identity to itsme when it asks for the token. The sample supports two values (README: "Two client authentication methods"):

- `PRIVATE_KEY_JWT`: we send a short JWT signed with our private key (the *client assertion*);
- `CLIENT_SECRET_POST`: we send our client id and a shared secret in the request body.

**`AuthorizationGrantType.AUTHORIZATION_CODE`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

Names the OAuth 2 flow in use: the browser brings back a short-lived *code*, and the backend exchanges it for tokens. It is the standard flow for a browser login.

**`IdTokenClaimNames`** (Spring) — *files: `itsme/ItsmeClientConfig.java`, `itsme/ItsmeOidcUserService.java`*

Constants for the standard claim names of an ID token. The sample uses `IdTokenClaimNames.SUB` to say "the user's name is the `sub` claim", the itsme pseudonym.

## 5. Step one: sending the customer to itsme

The customer clicks "Log in with itsme", which opens `/oauth2/authorization/itsme`.

**`OAuth2AuthorizationRequestRedirectFilter`** (Spring) — *not in our code; added by `oauth2Login()`*

The filter that reacts to `/oauth2/authorization/{registrationId}`. It asks a *resolver* to build the authorization request, asks a *repository* to remember it, and redirects the browser to itsme. The sample replaces both the resolver and the repository. It also uses the filter's constant `DEFAULT_AUTHORIZATION_REQUEST_BASE_URI` (`/oauth2/authorization`), so that the resolver recognises the same URL as the filter.

**`OAuth2AuthorizationRequest`** (Spring) — *files: several in `itsme/`*

The authorization request as an object: the itsme URL to redirect to, our client id, the redirect URI, the scopes, the `state`, extra URL parameters, and *attributes*. Attributes are values that are not sent to itsme but must be kept for the callback: the `nonce` and the PKCE `code_verifier`. It is the object that `JdbcAuthorizationRequestRepository` saves and loads.

**`OAuth2AuthorizationRequestResolver`** (Spring interface) and **`ItsmeAuthorizationRequestResolver`** (ours) — *file: `itsme/ItsmeAuthorizationRequestResolver.java`*

A resolver answers one question: "for this incoming request, what authorization request should we send?" Our resolver starts from Spring's result, then adds what itsme needs:

- with key pairs, a **Request Object**: the parameters packed into a JWT signed by us and encrypted for itsme, created by `ItsmeRequestObjectFactory`;
- with a client secret, the `acr_values` and `claims` parameters directly in the URL.

Because `OAuth2AuthorizationRequest` cannot be modified, `finish(...)` builds a new one with the extra `request` parameter.

**`DefaultOAuth2AuthorizationRequestResolver`** (Spring) — *file: `itsme/ItsmeAuthorizationRequestResolver.java`*

Spring's standard resolver. It already generates the random `state` and `nonce`. Our resolver keeps it as a *delegate* (it calls it, then completes the result) rather than rewriting what it does well.

**`OAuth2AuthorizationRequestCustomizers.withPkce()`** (Spring) — *file: `itsme/ItsmeAuthorizationRequestResolver.java`*

A ready-made customizer that adds PKCE to the request: it generates a secret `code_verifier`, keeps it as an attribute, and sends its SHA-256 hash (`code_challenge`) to itsme. At the token request, only the holder of the verifier can use the code. Customizers can be chained with `andThen(...)`, which is how the client secret mode adds `acr_values` and `claims`.

**`ItsmeRequestObjectFactory`** (ours) — *file: `itsme/ItsmeRequestObjectFactory.java`*

Not a Spring extension point, but it reads the `OAuth2AuthorizationRequest` that Spring prepared and turns it into the signed and encrypted Request Object (with Nimbus, the JWT library). It is listed here because it is called from the resolver above.

## 6. Remembering the login in progress

Between the redirect to itsme and the callback, the backend must remember the `state`, the `nonce` and the `code_verifier`. Without them, the callback cannot be checked.

**`AuthorizationRequestRepository`** (Spring interface) and **`JdbcAuthorizationRequestRepository`** (ours) — *file: `itsme/JdbcAuthorizationRequestRepository.java`*

The repository has three methods: save the request before the redirect, load it at the callback, remove it once it is used. The default implementation keeps it in the session; ours stores it in the database table `itsme_authorization_request`, keyed by `state`, for 5 minutes. Any instance of the backend can then handle the callback, even if the redirect was served by another one.

`removeAuthorizationRequest` is the method to read carefully (design rule 11): it reads the row, then deletes it and checks that exactly one row was deleted. If two callbacks with the same `state` arrive at the same time, only one gets the request; the other gets `null`, and Spring refuses that login with `authorization_request_not_found`. This makes each login attempt single-use.

**`OAuth2ParameterNames`, `OidcParameterNames`, `PkceParameterNames`** (Spring) — *file: `itsme/JdbcAuthorizationRequestRepository.java`*

Constants for parameter and attribute names (`state`, `registration_id`, `nonce`, `code_verifier`). Using them instead of string literals guarantees that we read and write exactly the keys Spring expects.

## 7. Step two: the callback and the token request

The customer approves in the itsme app; itsme redirects the browser to `/login/oauth2/code/itsme?code=...&state=...`.

**`OAuth2LoginAuthenticationFilter`** (Spring) — *not in our code; added by `oauth2Login()`*

The filter that handles the callback. In order, it:

1. removes the stored request from the `AuthorizationRequestRepository` (unknown or already used `state`: login refused);
2. hands the code to an *authentication provider*, `OidcAuthorizationCodeAuthenticationProvider` (Spring), which calls the token endpoint, decodes and checks the ID token (including the `nonce`), then loads the user;
3. calls the success handler or the failure handler.

All the classes below are plugged into steps 2 and 3.

**`OAuth2AccessTokenResponseClient`, `OAuth2AuthorizationCodeGrantRequest`** (Spring) — *file: `itsme/ItsmeClientConfig.java`, method `itsmeTokenResponseClient`*

The *token response client* is the component that sends the `POST` to itsme's token endpoint. The grant request is its input: the client registration, plus the authorization request and the response received at the callback (the code). The sample declares its own client to control the HTTP connection and the client authentication.

**`RestClientAuthorizationCodeTokenResponseClient`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

Spring's standard token client, based on Spring's `RestClient`. The sample gives it its own `RestClient` (`itsmeRestClient`), configured with the outbound proxy and timeouts (design rule 9), and a limited, explicit list of message converters.

**`NimbusJwtClientAuthenticationParametersConverter`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

Used only with key pairs. Before the token request is sent, it builds and signs the *client assertion*: a short JWT that proves to itsme that the request comes from us. We only give it our signing key; Spring fills in the claims (`iss`, `sub`, `aud`, `jti`, `iat`, `exp`).

**`OAuth2AccessTokenResponseHttpMessageConverter`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

Reads itsme's JSON token response and turns it into an object Spring understands (access token, ID token, expiry).

**`OAuth2ErrorResponseErrorHandler`, `OAuth2ErrorHttpMessageConverter`, `OAuth2Error`** (Spring) — *file: `itsme/ItsmeClientConfig.java`, method `itsmeErrorHandler`*

When the token endpoint answers with an error (for example an expired code), the error handler reads the error body into an `OAuth2Error` (a code, a description, a URI) and the login fails with that code. The OAuth 2 standard puts the description in `error_description`; itsme puts it in `detail`. The sample configures the converter to read either one, so that the reason appears in our logs.

## 8. Checking the ID token

The token response contains the ID token: the statement from itsme saying who logged in. Before we trust it, it must be decrypted and checked. This is the part where the sample differs most from Spring's defaults (README: "ID token validation").

**`JwtDecoderFactory<ClientRegistration>`** (Spring interface) — *file: `itsme/ItsmeClientConfig.java`, method `idTokenDecoderFactory`*

Spring asks this factory "give me the decoder for the ID tokens of this provider". The default factory can only check *signed* tokens, while itsme *encrypts* its ID tokens. Declaring our own factory as a bean is enough: `oauth2Login()` finds it automatically, which is why it does not appear in `SecurityConfig`. The decoders are cached, one per registration.

**`JwtDecoder`, `NimbusJwtDecoder`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

A `JwtDecoder` turns the token string into a checked `Jwt` object, or throws an exception. `NimbusJwtDecoder` is Spring's implementation on top of the Nimbus library. The sample gives it a Nimbus processor configured with explicit algorithms: first decrypt (with our private key, or with the key derived from the client secret), then verify itsme's signature with itsme's public keys. The factory wraps this decoder in a small lambda that first calls `ItsmeCrypto.requireNestedSignedJwt`, which refuses a token that is encrypted but not signed inside (design rule 2).

**`BadJwtException`** (Spring) — *file: `itsme/ItsmeCrypto.java`*

The exception a decoder throws when a token is malformed or invalid. `ItsmeCrypto` throws it for a token that is not a nested signed JWT, so that Spring treats it like any other invalid token and the login fails.

**`Jwt`** (Spring) — *file: `itsme/AcrValidator.java`*

A decoded token: its headers and its claims, with helpers such as `getClaimAsString("acr")`.

**`OAuth2TokenValidator<Jwt>`, `OAuth2TokenValidatorResult`** (Spring) — *file: `itsme/AcrValidator.java`*

A validator checks one rule about a decoded token and returns success, or failure with an `OAuth2Error`. The signature proves who wrote the token; validators check what it says.

**`DelegatingOAuth2TokenValidator`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

Combines several validators into one: the token is valid only if all of them succeed. The sample combines the three below.

**`JwtTimestampValidator`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

Refuses an expired token (`exp`) or a token that is not valid yet (`nbf`), with 60 seconds of tolerance for clocks that are slightly out of sync.

**`OidcIdTokenValidator`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

The standard OpenID Connect checks: the token was issued by itsme (`iss` equals the issuer of the `ClientRegistration`), for us (`aud` contains our client id), it has a subject (`sub`) and an issue time (`iat`). The `nonce` is checked separately by `OidcAuthorizationCodeAuthenticationProvider`, against the value saved in the login in progress.

**`AcrValidator`** (ours) — *file: `itsme/AcrValidator.java`*

Checks that the `acr` claim is the authentication level we require (`acr_advanced`: the customer typed their itsme code). With a client secret, the requested level travels in the browser URL, where it can be edited; this check on the signed answer is what guarantees the level was really used. Design rule 3 says it must stay. The tests `basicAcrIsRejected` show the attack it stops.

## 9. Turning the ID token into a user

At this point, Spring holds an ID token it trusts. It now asks a *user service* to build the logged-in user.

**`OAuth2UserService<OidcUserRequest, OidcUser>`** (Spring interface) and **`ItsmeOidcUserService`** (ours) — *file: `itsme/ItsmeOidcUserService.java`*

The user service receives the checked ID token and returns the user, or throws an exception to refuse the login. Spring's default (`OidcUserService`) copies every claim of the token into the user and may call the provider's UserInfo endpoint. Ours does something different, because the login only *authenticates* a customer we already know (README: "Authentication, not identification"):

1. looks up the customer by the itsme `sub` in `itsme_link`;
2. refuses the login if itsme reports an ongoing phone call (`fraud_suspected`);
3. refuses an unknown `sub` (`not_linked`) or an inactive customer (`no_customer`);
4. builds a user that contains only the `sub` and our customer id, never the raw token or other itsme claims (design rule 7).

**`OidcUserRequest`** (Spring) — the input of the user service: the client registration, the access token and the checked `OidcIdToken`.

**`OidcIdToken`** (Spring) — an ID token as an object, with helpers such as `getSubject()`. The user service reads the received one, then builds a new, reduced one with `OidcIdToken.withTokenValue("redacted")`, so that the original token string is not kept in memory with the user.

**`OidcUser`, `DefaultOidcUser`** (Spring) — the logged-in user of an OpenID Connect login. `DefaultOidcUser` is the standard implementation: a set of authorities, an ID token, and the name of the claim that holds the user's name (`sub`).

**`SimpleGrantedAuthority`** (Spring) — a role or permission given to the user, here `ROLE_ITSME_USER`. Access rules can test it (for example `hasRole("ITSME_USER")`); the sample has none, but the authority states how the user logged in.

**`OAuth2AuthenticationException`** (Spring) — the exception that refuses an OAuth 2 login. It carries an `OAuth2Error` whose code (`not_linked`, `fraud_suspected`, ...) reaches the failure handler and becomes the `error` parameter of the error page.

## 10. The end of the login: success or failure

**`Authentication`** (Spring) — *file: `itsme/ItsmeLoginSuccessHandler.java`*

Spring's general object for "someone who has proven who they are". After an OAuth 2 login, it is an `OAuth2AuthenticationToken` whose *principal* (`getPrincipal()`) is the `OidcUser` built by `ItsmeOidcUserService`.

**`AuthenticationSuccessHandler`** (Spring interface) and **`ItsmeLoginSuccessHandler`** (ours) — *file: `itsme/ItsmeLoginSuccessHandler.java`*

Called once, when the login has succeeded; it writes the HTTP response. Spring's default redirects to the page saved in the request cache, which is a session feature. Ours builds an `ItsmeLoginResult` (customer id, method, level, time; no itsme claim) and passes it to `LoginHandover`.

**`LoginHandover`** (ours, an interface) — *file: `itsme/LoginHandover.java`*

The boundary between the itsme integration and the rest of the application (README: "Hand-over"). A real application implements it to issue its own session or token and redirect to its portal. In this sample, `demo/DemoLoginHandover` does the same with a demo stand-in: a signed cookie and a redirect to `/portal` ([section 11](#11-after-the-login-the-protected-portal)).

**`AuthenticationException`** (Spring) and **`AuthenticationFailureHandler`** (Spring interface), with **`ItsmeLoginFailureHandler`** (ours) — *file: `itsme/ItsmeLoginFailureHandler.java`*

`AuthenticationException` is the parent of every "login refused" exception. The failure handler is called with it when any step fails: unknown `state`, token error, invalid ID token, unknown customer. Ours logs the error code and its description, then redirects to `/login-error?error=<code>`. The error page shows a fixed message for each code and never displays the raw parameter (README: "Error codes").

## 11. After the login: the protected portal

A common question: after a successful itsme login, how does the customer open a protected page?

**Not through the itsme login itself.** `RequestAttributeSecurityContextRepository` keeps the logged-in user only for the duration of the callback request; on the next request, Spring knows nobody. Remembering the customer is the job of the application's own credential (its session or its own token), issued at the hand-over. Issuing it is out of scope of the sample, so the `demo` package provides a stand-in, to show the whole journey (README: "Hand-over"):

1. `DemoLoginHandover` sets a cookie holding a JWT signed by the application, and redirects to `/portal`.
2. The browser opens `/portal` with the cookie.
3. A second filter chain, dedicated to `/portal/**`, checks the cookie and lets the request through.

**`DemoSecurityConfig`** (ours) — *file: `demo/DemoSecurityConfig.java`*

A second `SecurityFilterChain`, like `ItsmeStubSecurityConfig` in [section 1](#1-building-the-filter-chain). Its `securityMatcher("/portal", "/portal/**")` means it handles only portal URLs; `@Order(2)` makes Spring try it before the main chain. It is stateless in the same way as the main chain ([section 3](#3-staying-stateless)), requires an authenticated user for every portal URL, and adds `DemoSessionFilter` to read the cookie. Unlike the main chain, it keeps CSRF protection on, because the portal has a form ([section 12](#12-confirming-an-operation-from-the-portal)).

**`OncePerRequestFilter`** (Spring, from `spring-web`) and **`DemoSessionFilter`** (ours) — *file: `demo/DemoSessionFilter.java`*

`OncePerRequestFilter` is a convenient base class for a servlet filter that must run exactly once per request. `DemoSessionFilter` extends it: if the request carries a valid demo session cookie, it tells Spring Security "this request comes from customer C-1001"; otherwise it does nothing, and the request continues as anonymous. It checks the cookie on every request, so nothing needs to be stored on the server.

The filter is created with `new` inside `DemoSecurityConfig`, not declared as a `@Component`. A filter that is a Spring bean is also registered by Spring Boot as a servlet filter for every URL, outside the security chain, which is rarely what you want.

**`addFilterBefore(..., AnonymousAuthenticationFilter.class)`** (Spring configuration) — *file: `demo/DemoSecurityConfig.java`*

Places our filter at a precise position in the chain. `AnonymousAuthenticationFilter` (Spring) is the filter that marks a request as "anonymous" when no earlier filter has authenticated it. Our filter must run just before it: if the cookie is valid, the request is already authenticated when the anonymous filter runs, and that filter leaves it alone.

**`SecurityContextHolder`, `SecurityContextHolderStrategy`, `SecurityContext`** (Spring) — *file: `demo/DemoSessionFilter.java`*

The `SecurityContext` holds the `Authentication` of the current request. The `SecurityContextHolder` is where Spring keeps it while the request is processed, so that any later filter or controller can ask "who is this?". The filter creates an empty context, puts the authentication in it and stores it through the holder's *strategy* (the recommended way since Spring Security 5.8, instead of the static methods). Spring clears it at the end of the request.

**`PreAuthenticatedAuthenticationToken`** (Spring) — *file: `demo/DemoSessionFilter.java`*

An `Authentication` that means "this user was authenticated by someone else, earlier". It fits exactly: the customer proved who they were during the itsme login, and the cookie is the proof of it. Its principal is a `DemoSession.LoggedInCustomer` (customer id, level, login time, expiry), with the authority `ROLE_CUSTOMER`.

**The entry point lambda in `exceptionHandling(...)`** (Spring configuration, an `AuthenticationEntryPoint`) — *file: `demo/DemoSecurityConfig.java`*

When an anonymous request reaches a URL that requires a login, Spring calls the *authentication entry point* to decide what to answer. In the main chain, `oauth2Login()` provides one that starts the itsme login. The portal chain has no login of its own, so its entry point simply redirects the browser to the home page, where the "Log in with itsme" button is.

**`DemoSession`** (ours), with **`NimbusJwtDecoder.withSecretKey(...)`, `MacAlgorithm`, `JwtIssuerValidator`, `JwtException`** (Spring) — *file: `demo/DemoSession.java`*

`DemoSession` issues and reads the cookie. The cookie value is a JWT signed with a key only the application knows (HMAC SHA-256), so the customer can read it but cannot change it. To read it, it reuses the Spring classes of [section 8](#8-checking-the-id-token), configured for this simpler token:

- `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256)`: a decoder that accepts only `HS256` with our key, so an unsigned token (`alg: none`) or a token signed with any other key is refused;
- `JwtTimestampValidator` with the application clock: refuses an expired session;
- `JwtIssuerValidator`: refuses a token that is not a demo session;
- `JwtException`: the parent of every decoding or validation error; `DemoSession` catches it and treats the request as not logged in.

**`@AuthenticationPrincipal`** (Spring annotation) — *file: `demo/DemoController.java`*

Placed on a controller method parameter, it injects the principal of the current `Authentication`, here the `LoggedInCustomer` set by the filter. The portal page uses it to know which customer to show.

`DemoSessionTest` lists the attacks on the demo cookie: missing, expired, edited to another customer, signed with another key, unsigned.

Remember that everything in this section is a demo stand-in. A real application keeps its existing session or token management, plugged in behind `LoginHandover`.

## 12. Confirming an operation from the portal

A logged-in customer clicks "Confirm with itsme" and approves one operation in the itsme app (README: "Confirmation of an operation"). The protocol is the login's: same itsme endpoints, `state`, `nonce`, PKCE, Request Object, token request and ID token checks. But a confirmation logs nobody in, so `oauth2Login()` is not used for it. Its filters would turn the answer into a login and call the hand-over. `ItsmeConfirmationService` calls the same Spring classes directly instead.

**`ItsmeConfirmationService`** (ours) — *file: `itsme/ItsmeConfirmationService.java`*

Two methods. `start` builds the authorization request and stores the confirmation in progress; `complete` checks itsme's answer and returns the confirmed operation, or throws `ItsmeConfirmationException`. After Spring's checks, it makes the checks that decide whether the approval counts: the customer ending the confirmation is the one who started it, and the itsme account that approved it is the one linked to that customer.

**`ClientRegistration`, built by `ItsmeClientConfig.confirmationRegistration`** (Spring) — *file: `itsme/ItsmeClientConfig.java`*

A second registration with the same client id, the confirmation's itsme service and its own redirect URI (`/portal/confirmation/callback`). It is deliberately **not** in the `ClientRegistrationRepository` of [section 4](#4-describing-itsme-to-spring). `/oauth2/authorization/itsme-confirmation` therefore starts nothing, and the login callback cannot end a confirmation.

**`DefaultOAuth2AuthorizationRequestResolver`** and **`OAuth2AuthorizationRequestCustomizers.withPkce()`** (Spring) — *file: `itsme/ItsmeConfirmationService.java`*

The same resolver as in [section 5](#5-step-one-sending-the-customer-to-itsme), given an `InMemoryClientRegistrationRepository` that holds only the confirmation registration. It generates `state`, `nonce` and PKCE. `ItsmeRequestObjectFactory.withRequestObject` then adds the Request Object, with the text to approve in its `claims`.

**`JdbcConfirmationRequestRepository`** (ours) — *file: `itsme/JdbcConfirmationRequestRepository.java`*

Not a Spring interface: the confirmation is not handled by `oauth2Login()`, so nothing calls an `AuthorizationRequestRepository`. It stores the same protocol values as [section 6](#6-remembering-the-login-in-progress), plus the customer and the operation, in `itsme_confirmation_request`, with the same read-then-checked-delete.

**`OidcAuthorizationCodeAuthenticationProvider`** (Spring) — *file: `itsme/ItsmeConfirmationService.java`*

The provider that `OAuth2LoginAuthenticationFilter` uses for the login ([section 7](#7-step-two-the-callback-and-the-token-request)), here created and called directly. In order, it refuses an error answer (for example `access_denied`) and a `state` mismatch, calls the token endpoint with our token client, decodes the ID token with our `JwtDecoderFactory` ([section 8](#8-checking-the-id-token)), checks the `nonce`, then calls a user service. The user service here only returns the ID token: the result is read by `ItsmeConfirmationService` and never stored in a `SecurityContext`.

**`OAuth2LoginAuthenticationToken`, `OAuth2AuthorizationExchange`, `OAuth2AuthorizationResponse`** (Spring) — *file: `itsme/ItsmeConfirmationService.java`*

The provider's input and output. The exchange pairs the request we sent, rebuilt from the stored values, with the response that came back on the callback URL (`code` or `error`, and `state`). `OAuth2LoginAuthenticationToken` carries the registration and the exchange in, and the checked ID token out. Despite its name, it logs nobody in here.

**`CookieCsrfTokenRepository`, `CsrfToken`** (Spring) — *files: `demo/DemoSecurityConfig.java`, `demo/DemoController.java`*

The button is a POST form, so the portal chain has CSRF protection: a request that changes something must carry a token that a page from another site cannot know. The default repository keeps the token in the `HttpSession`. `CookieCsrfTokenRepository` keeps it in a cookie instead (`XSRF-TOKEN`, `HttpOnly`, path `/portal`), so the backend stays stateless. Declared as a controller method parameter, `CsrfToken` gives the portal page the parameter name and the token value for the hidden form field. The callback is a GET; like the login callback, it is protected by `state`.

In the tests, Spring Security's `csrf()` request post-processor is **not** used: it replaces the token repository of the shared filter chain with one backed by the `HttpSession`, and `noSessionIsCreatedDuringTheWholeFlow` then fails. The tests submit the form like a browser does: the cookie and the token read from the page.

`ItsmeConfirmationFlowTest` lists the attacks on the confirmation.

## Index

| Class | Kind | Section | Where |
|---|---|---|---|
| `AbstractHttpConfigurer` | Spring | [1](#1-building-the-filter-chain) | `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java` |
| `AcrValidator` | ours | [8](#8-checking-the-id-token) | `itsme/AcrValidator.java` |
| `AnonymousAuthenticationFilter` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSecurityConfig.java` |
| `Authentication` | Spring | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/ItsmeLoginSuccessHandler.java` |
| `AuthenticationEntryPoint` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSecurityConfig.java` (lambda) |
| `AuthenticationException` | Spring | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/ItsmeLoginFailureHandler.java` |
| `AuthenticationFailureHandler` | Spring | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/ItsmeLoginFailureHandler.java` |
| `@AuthenticationPrincipal` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoController.java`, `demo/DemoConfirmationController.java` |
| `AuthenticationSuccessHandler` | Spring | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/ItsmeLoginSuccessHandler.java` |
| `AuthorizationGrantType` | Spring | [4](#4-describing-itsme-to-spring) | `itsme/ItsmeClientConfig.java` |
| `AuthorizationRequestRepository` | Spring | [6](#6-remembering-the-login-in-progress) | `itsme/JdbcAuthorizationRequestRepository.java` |
| `BadJwtException` | Spring | [8](#8-checking-the-id-token) | `itsme/ItsmeCrypto.java` |
| `ClientAuthenticationMethod` | Spring | [4](#4-describing-itsme-to-spring) | `itsme/ItsmeClientConfig.java` |
| `ClientRegistration` | Spring | [4](#4-describing-itsme-to-spring), [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeClientConfig.java` |
| `ClientRegistrationRepository` | Spring | [4](#4-describing-itsme-to-spring) | `itsme/ItsmeClientConfig.java` |
| `CookieCsrfTokenRepository` | Spring | [12](#12-confirming-an-operation-from-the-portal) | `demo/DemoSecurityConfig.java` |
| `CsrfToken` | Spring | [12](#12-confirming-an-operation-from-the-portal) | `demo/DemoController.java` |
| `DefaultOAuth2AuthorizationRequestResolver` | Spring | [5](#5-step-one-sending-the-customer-to-itsme), [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeAuthorizationRequestResolver.java`, `itsme/ItsmeConfirmationService.java` |
| `DefaultOidcUser` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java`, `itsme/ItsmeConfirmationService.java` |
| `DelegatingOAuth2TokenValidator` | Spring | [8](#8-checking-the-id-token) | `itsme/ItsmeClientConfig.java`, `demo/DemoSession.java` |
| `DemoSecurityConfig` | ours (demo) | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSecurityConfig.java` |
| `DemoSession` | ours (demo) | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSession.java` |
| `DemoSessionFilter` | ours (demo) | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSessionFilter.java` |
| `HttpSecurity` | Spring | [1](#1-building-the-filter-chain) | `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java`, `demo/DemoSecurityConfig.java` |
| `IdTokenClaimNames` | Spring | [4](#4-describing-itsme-to-spring) | `itsme/ItsmeClientConfig.java`, `itsme/ItsmeOidcUserService.java` |
| `InMemoryClientRegistrationRepository` | Spring | [4](#4-describing-itsme-to-spring), [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeClientConfig.java`, `itsme/ItsmeConfirmationService.java` |
| `ItsmeAuthorizationRequestResolver` | ours | [5](#5-step-one-sending-the-customer-to-itsme) | `itsme/ItsmeAuthorizationRequestResolver.java` |
| `ItsmeConfirmationService` | ours | [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeConfirmationService.java` |
| `ItsmeLoginFailureHandler` | ours | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/ItsmeLoginFailureHandler.java` |
| `ItsmeLoginSuccessHandler` | ours | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/ItsmeLoginSuccessHandler.java` |
| `ItsmeOidcUserService` | ours | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java` |
| `ItsmeRequestObjectFactory` | ours | [5](#5-step-one-sending-the-customer-to-itsme) | `itsme/ItsmeRequestObjectFactory.java` |
| `ItsmeStubSecurityConfig` | ours (local) | [1](#1-building-the-filter-chain) | `local/ItsmeStubSecurityConfig.java` |
| `JdbcAuthorizationRequestRepository` | ours | [6](#6-remembering-the-login-in-progress) | `itsme/JdbcAuthorizationRequestRepository.java` |
| `JdbcConfirmationRequestRepository` | ours | [12](#12-confirming-an-operation-from-the-portal) | `itsme/JdbcConfirmationRequestRepository.java` |
| `Jwt` | Spring | [8](#8-checking-the-id-token) | `itsme/AcrValidator.java`, `demo/DemoSession.java` |
| `JwtDecoder` | Spring | [8](#8-checking-the-id-token) | `itsme/ItsmeClientConfig.java` |
| `JwtDecoderFactory` | Spring | [8](#8-checking-the-id-token), [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeClientConfig.java`, `itsme/ItsmeConfirmationService.java` |
| `JwtException` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSession.java` |
| `JwtIssuerValidator` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSession.java` |
| `JwtTimestampValidator` | Spring | [8](#8-checking-the-id-token) | `itsme/ItsmeClientConfig.java`, `demo/DemoSession.java` |
| `LoginHandover` | ours | [10](#10-the-end-of-the-login-success-or-failure) | `itsme/LoginHandover.java` |
| `MacAlgorithm` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSession.java` |
| `NimbusJwtClientAuthenticationParametersConverter` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java` |
| `NimbusJwtDecoder` | Spring | [8](#8-checking-the-id-token) | `itsme/ItsmeClientConfig.java`, `demo/DemoSession.java` |
| `NoOpAuthorizedClientRepository` | ours | [3](#3-staying-stateless) | `itsme/NoOpAuthorizedClientRepository.java` |
| `NullRequestCache` | Spring | [3](#3-staying-stateless) | `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java`, `demo/DemoSecurityConfig.java` |
| `OAuth2AccessTokenResponseClient` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java`, `security/SecurityConfig.java`, `itsme/ItsmeConfirmationService.java` |
| `OAuth2AccessTokenResponseHttpMessageConverter` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java` |
| `OAuth2AuthenticationException` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java`, `itsme/ItsmeLoginFailureHandler.java`, `itsme/ItsmeConfirmationService.java` |
| `OAuth2AuthorizationCodeGrantRequest` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java`, `security/SecurityConfig.java`, `itsme/ItsmeConfirmationService.java` |
| `OAuth2AuthorizationExchange` | Spring | [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeConfirmationService.java` |
| `OAuth2AuthorizationRequest` | Spring | [5](#5-step-one-sending-the-customer-to-itsme), [12](#12-confirming-an-operation-from-the-portal) | `itsme/` (resolver, repository, Request Object factory, confirmation service) |
| `OAuth2AuthorizationRequestCustomizers` | Spring | [5](#5-step-one-sending-the-customer-to-itsme), [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeAuthorizationRequestResolver.java`, `itsme/ItsmeConfirmationService.java` |
| `OAuth2AuthorizationRequestRedirectFilter` | Spring | [5](#5-step-one-sending-the-customer-to-itsme) | added by `oauth2Login()`; constant used in the resolver |
| `OAuth2AuthorizationRequestResolver` | Spring | [5](#5-step-one-sending-the-customer-to-itsme) | `itsme/ItsmeAuthorizationRequestResolver.java` |
| `OAuth2AuthorizationResponse` | Spring | [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeConfirmationService.java` |
| `OAuth2AuthorizedClient` | Spring | [3](#3-staying-stateless) | `itsme/NoOpAuthorizedClientRepository.java` |
| `OAuth2AuthorizedClientRepository` | Spring | [3](#3-staying-stateless) | `itsme/NoOpAuthorizedClientRepository.java` |
| `OAuth2Error` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/` (client config, validator, user service) |
| `OAuth2ErrorHttpMessageConverter` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java` |
| `OAuth2ErrorResponseErrorHandler` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java` |
| `OAuth2LoginAuthenticationFilter` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | added by `oauth2Login()` |
| `OAuth2LoginAuthenticationToken` | Spring | [12](#12-confirming-an-operation-from-the-portal) | `itsme/ItsmeConfirmationService.java` |
| `OAuth2ParameterNames` | Spring | [6](#6-remembering-the-login-in-progress) | `itsme/JdbcAuthorizationRequestRepository.java`, `itsme/ItsmeConfirmationService.java` |
| `OAuth2TokenValidator` | Spring | [8](#8-checking-the-id-token) | `itsme/AcrValidator.java` |
| `OAuth2TokenValidatorResult` | Spring | [8](#8-checking-the-id-token) | `itsme/AcrValidator.java` |
| `OAuth2UserService` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java` |
| `OidcAuthorizationCodeAuthenticationProvider` | Spring | [7](#7-step-two-the-callback-and-the-token-request), [12](#12-confirming-an-operation-from-the-portal) | added by `oauth2Login()`; `itsme/ItsmeConfirmationService.java` |
| `OidcIdToken` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java`, `itsme/ItsmeConfirmationService.java` |
| `OidcIdTokenValidator` | Spring | [8](#8-checking-the-id-token) | `itsme/ItsmeClientConfig.java` |
| `OidcParameterNames` | Spring | [6](#6-remembering-the-login-in-progress) | `itsme/JdbcAuthorizationRequestRepository.java`, `itsme/ItsmeConfirmationService.java` |
| `OidcUser` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java`, `itsme/ItsmeLoginSuccessHandler.java`, `itsme/ItsmeConfirmationService.java` |
| `OidcUserRequest` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java` |
| `OncePerRequestFilter` | Spring (web) | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSessionFilter.java` |
| `PkceParameterNames` | Spring | [6](#6-remembering-the-login-in-progress) | `itsme/JdbcAuthorizationRequestRepository.java`, `itsme/ItsmeConfirmationService.java` |
| `PreAuthenticatedAuthenticationToken` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSessionFilter.java` |
| `RequestAttributeSecurityContextRepository` | Spring | [3](#3-staying-stateless) | `security/SecurityConfig.java`, `demo/DemoSecurityConfig.java` |
| `RestClientAuthorizationCodeTokenResponseClient` | Spring | [7](#7-step-two-the-callback-and-the-token-request) | `itsme/ItsmeClientConfig.java` |
| `SecurityContext` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSessionFilter.java` |
| `SecurityContextHolder` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSessionFilter.java` |
| `SecurityContextHolderStrategy` | Spring | [11](#11-after-the-login-the-protected-portal) | `demo/DemoSessionFilter.java` |
| `SecurityFilterChain` | Spring | [1](#1-building-the-filter-chain) | `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java`, `demo/DemoSecurityConfig.java` |
| `SessionCreationPolicy` | Spring | [3](#3-staying-stateless) | `security/SecurityConfig.java`, `local/ItsmeStubSecurityConfig.java`, `demo/DemoSecurityConfig.java` |
| `SimpleGrantedAuthority` | Spring | [9](#9-turning-the-id-token-into-a-user) | `itsme/ItsmeOidcUserService.java`, `demo/DemoSessionFilter.java` |
