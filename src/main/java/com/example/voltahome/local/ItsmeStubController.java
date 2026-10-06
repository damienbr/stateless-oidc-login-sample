package com.example.voltahome.local;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.voltahome.itsme.ItsmeClaims;
import com.example.voltahome.itsme.ItsmeProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * LOCAL DEVELOPMENT ONLY ("local" profile). An itsme stub served by the application itself, so the complete
 * login can be clicked through in a browser without itsme credentials, Docker or any external service.
 * It checks what itsme would check: the Request Object when there is one (decrypted, client signature verified),
 * the registered redirect URI, PKCE, a single-use code, and the client authentication (signed client assertion or
 * client secret, depending on itsme.client-authentication). It is not itsme and does not look like it.
 */
@Controller
@Profile("local")
@RequestMapping("/itsme-stub/v2")
class ItsmeStubController {

    private static final String ACR_ADVANCED = "http://itsme.services/v2/claim/acr_advanced";
    private static final String ACR_BASIC = "http://itsme.services/v2/claim/acr_basic";
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(3);   // as documented by itsme

    private final ItsmeProperties properties;
    private final JWKSource<SecurityContext> clientKeys;      // the application's public keys, read from its JWKS
    private final String clientJwksUri;
    private final ItsmeStubCrypto crypto = new ItsmeStubCrypto();
    private final Map<String, Authorization> pendingAuthorizations = new ConcurrentHashMap<>();
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    private final Set<String> usedAssertionIds = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    /** The parameters itsme will act on: taken from the verified Request Object, not from the URL. */
    private record Authorization(String redirectUri, String state, String nonce, String codeChallenge,
                                 String acrValues, Instant expiresAt) {}

    private record PendingCode(Authorization authorization, ItsmeStubScenario scenario, Instant expiresAt) {}

    ItsmeStubController(ItsmeProperties properties,
                        @Value("${itsme-stub.client-jwks-uri}") String clientJwksUri) throws MalformedURLException {
        this.properties = properties;
        this.clientJwksUri = clientJwksUri;
        this.clientKeys = JWKSourceBuilder.create(URI.create(clientJwksUri).toURL()).retrying(true).build();
    }

    // ------------------------------------------------------------------ metadata

    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Map<String, Object> discovery() {
        ItsmeProperties.Endpoints e = properties.endpoints();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("issuer", e.issuer());
        d.put("authorization_endpoint", e.authorizationUri());
        d.put("token_endpoint", e.tokenUri());
        d.put("jwks_uri", e.jwkSetUri());
        d.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt", "client_secret_post"));
        d.put("id_token_encryption_alg_values_supported", List.of("RSA-OAEP-256", "dir"));
        d.put("id_token_encryption_enc_values_supported", List.of("A128CBC-HS256", "A256GCM"));
        return d;
    }

    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Map<String, Object> jwks() {
        return crypto.publicJwkSet().toJSONObject();
    }

    // ------------------------------------------------------------------ authorization (browser)

    @GetMapping(value = "/authorization", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    ResponseEntity<String> authorize(@RequestParam Map<String, String> query) {
        if (!properties.clientId().equals(query.get("client_id")) || !"code".equals(query.get("response_type"))) {
            return badRequest("Unknown client_id, or response_type is not 'code'.");
        }
        // With a Request Object, only its (verified) content counts. Without one, the URL parameters are used.
        Map<String, Object> params = new LinkedHashMap<>();
        boolean fromRequestObject = query.get("request") != null;
        if (fromRequestObject) {
            try {
                JWTClaimsSet ro = crypto.readRequestObject(query.get("request"), clientKeys);
                if (!properties.clientId().equals(ro.getIssuer())
                        || !ro.getAudience().contains(properties.endpoints().issuer())) {
                    return badRequest("Request Object: wrong issuer or audience.");
                }
                params.putAll(ro.getClaims());
            } catch (Exception e) {
                return badRequest("The Request Object could not be decrypted or its signature is not valid.");
            }
        } else {
            params.putAll(query);
        }
        if (!properties.clientId().equals(params.get("client_id"))
                || !properties.redirectUri().equals(params.get("redirect_uri"))
                || !"S256".equals(params.get("code_challenge_method"))
                || params.get("code_challenge") == null) {
            return badRequest("Wrong client_id or redirect_uri, or missing PKCE (S256).");
        }
        Authorization authorization = new Authorization(str(params.get("redirect_uri")), str(params.get("state")),
                str(params.get("nonce")), str(params.get("code_challenge")), str(params.get("acr_values")),
                Instant.now().plus(Duration.ofMinutes(5)));
        String id = randomValue();
        pendingAuthorizations.put(id, authorization);
        return ResponseEntity.ok(page(chooser(id, query, params, fromRequestObject)));
    }

    @PostMapping("/authorization/approve")
    ResponseEntity<Void> approve(@RequestParam String id, @RequestParam ItsmeStubScenario scenario) {
        Authorization a = pendingAuthorizations.remove(id);
        if (a == null || Instant.now().isAfter(a.expiresAt())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String code = randomValue();
        codes.put(code, new PendingCode(a, scenario, Instant.now().plus(CODE_LIFETIME)));
        return redirect(UriComponentsBuilder.fromUriString(a.redirectUri())
                .queryParam("code", code).queryParam("state", a.state()).encode().build().toUri());
    }

    @PostMapping("/authorization/cancel")
    ResponseEntity<Void> cancel(@RequestParam String id) {
        Authorization a = pendingAuthorizations.remove(id);
        if (a == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return redirect(UriComponentsBuilder.fromUriString(a.redirectUri())
                .queryParam("error", "access_denied").queryParam("state", a.state()).encode().build().toUri());
    }

    // ------------------------------------------------------------------ token (back channel)

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> form) {
        boolean clientAuthenticated = properties.usesKeyPairs()
                ? clientAssertionIsValid(form)
                : properties.clientId().equals(form.get("client_id"))
                        && properties.clientSecret().equals(form.get("client_secret"));
        if (!clientAuthenticated) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client");
        }
        if (!"authorization_code".equals(form.get("grant_type"))) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        }
        PendingCode pending = form.get("code") == null ? null : codes.remove(form.get("code"));   // single use
        if (pending == null || Instant.now().isAfter(pending.expiresAt())
                || !pending.authorization().redirectUri().equals(form.get("redirect_uri"))
                || !s256(form.get("code_verifier")).equals(pending.authorization().codeChallenge())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant");
        }

        Instant now = Instant.now();
        ItsmeStubScenario s = pending.scenario();
        String requestedAcr = pending.authorization().acrValues() == null ? "" : pending.authorization().acrValues();
        boolean advanced = !s.basicAcrOnly && requestedAcr.contains(ACR_ADVANCED);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.endpoints().issuer())
                .audience(properties.clientId())
                .subject(s.sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("nonce", pending.authorization().nonce())
                .claim("acr", advanced ? ACR_ADVANCED : ACR_BASIC)
                .claim(ItsmeClaims.ONGOING_CALL, s.ongoingCall)
                .build();
        try {
            String idToken = properties.usesKeyPairs()
                    ? crypto.signedAndEncrypted(claims,
                            ItsmeStubCrypto.encryptionKeyOf(JWKSet.load(URI.create(clientJwksUri).toURL())))
                    : crypto.signedAndEncryptedWithSecret(claims, properties.clientSecret());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", "stub-access-token-" + randomValue());
            body.put("token_type", "Bearer");
            body.put("expires_in", 3600);
            body.put("id_token", idToken);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "server_error");
        }
    }

    /** private_key_jwt: signed by the client, iss = sub = client_id, aud = token endpoint, not expired, jti unused. */
    private boolean clientAssertionIsValid(Map<String, String> form) {
        if (!"urn:ietf:params:oauth:client-assertion-type:jwt-bearer".equals(form.get("client_assertion_type"))
                || form.get("client_assertion") == null) {
            return false;
        }
        try {
            JWTClaimsSet a = crypto.verifyClientAssertion(form.get("client_assertion"), clientKeys);
            return properties.clientId().equals(a.getIssuer())
                    && properties.clientId().equals(a.getSubject())
                    && a.getAudience().contains(properties.endpoints().tokenUri())
                    && a.getExpirationTime() != null && a.getExpirationTime().toInstant().isAfter(Instant.now())
                    && a.getJWTID() != null && usedAssertionIds.add(a.getJWTID());   // replay protection
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ page

    private String chooser(String id, Map<String, String> query, Map<String, Object> params, boolean fromRequestObject) {
        StringBuilder html = new StringBuilder().append("<h2>What the application asked for</h2>");
        if (fromRequestObject) {
            html.append("<p>Read from the <strong>Request Object</strong>, decrypted with the stub's private key ")
                    .append("and verified with the application's public key. Editing the browser URL cannot change it.</p>");
        } else {
            html.append("<p>Read from the <strong>URL</strong> (no Request Object). These parameters are not protected: ")
                    .append("try removing <code>acr_values</code> from the address and reloading.</p>");
        }
        html.append("<table>")
                .append(row("acr_values", params.getOrDefault("acr_values", "(none: basic level)")))
                .append(row("claims", params.get("claims")))
                .append(row("scope", params.get("scope")))
                .append("</table>");
        if (fromRequestObject && (query.containsKey("acr_values") || query.containsKey("claims"))) {
            html.append("<p class='note'>The URL also contains acr_values or claims. They are ignored: ")
                    .append("only the Request Object counts.</p>");
        }
        html.append("<h2>Choose a test itsme account</h2>");
        for (ItsmeStubScenario s : ItsmeStubScenario.values()) {
            html.append("<form method='post' action='/itsme-stub/v2/authorization/approve'>")
                    .append(hidden("id", id)).append(hidden("scenario", s.name()))
                    .append("<button type='submit'>").append(esc(s.label)).append("</button></form>");
        }
        html.append("<form method='post' action='/itsme-stub/v2/authorization/cancel'>").append(hidden("id", id))
                .append("<button type='submit' class='cancel'>Cancel (the user refuses)</button></form>");
        return html.toString();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String s256(String codeVerifier) {
        if (codeVerifier == null) {
            return "";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().body(page("<p class='error'>" + esc(message) + "</p>"));
    }

    private static String esc(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value.toString());
    }

    private static String hidden(String name, String value) {
        return "<input type='hidden' name='" + name + "' value='" + esc(value) + "'>";
    }

    private static String row(String name, Object value) {
        return "<tr><th>" + esc(name) + "</th><td><code>" + esc(value) + "</code></td></tr>";
    }

    private static String page(String content) {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>itsme stub (local test double)</title>
                <style>
                  body{font-family:sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem}
                  .banner{background:#eef2f6;border:1px solid #9aa8b8;padding:.75rem;border-radius:6px}
                  table{border-collapse:collapse;margin:1rem 0}th,td{border:1px solid #ccc;padding:.4rem;text-align:left;vertical-align:top}
                  code{word-break:break-all}form{margin:.4rem 0}button{width:100%;padding:.6rem;text-align:left;cursor:pointer}
                  .cancel{margin-top:1rem}.error{color:#a00}.note{color:#555}
                </style></head><body>
                <h1>itsme stub (local test double)</h1>
                <p class="banner">Local development only. This page stands in for the approval in the itsme app.
                It is not itsme and is not affiliated with Belgian Mobile ID. All accounts are fictitious.</p>
                """ + content + "</body></html>";
    }
}
