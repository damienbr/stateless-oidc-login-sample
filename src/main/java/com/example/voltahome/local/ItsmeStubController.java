package com.example.voltahome.local;

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
import java.util.concurrent.ConcurrentHashMap;

import com.example.voltahome.itsme.ItsmeClaims;
import com.example.voltahome.itsme.ItsmeProperties;
import com.nimbusds.jwt.JWTClaimsSet;
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
import tools.jackson.databind.ObjectMapper;

/**
 * LOCAL DEVELOPMENT ONLY ("local" profile). An itsme stub served by the application itself, so the complete
 * login can be clicked through in a browser without itsme credentials, Docker or any external service.
 * It checks what itsme would check: the registered redirect URI, PKCE, a single-use code, the request object of a
 * confirmation (signed with the client secret, encrypted for itsme) and its WYSIWYS template, and the client secret
 * at the token endpoint. It is not itsme and does not look like it.
 */
@Controller
@Profile("local")
@RequestMapping("/itsme-stub/v2")
class ItsmeStubController {

    private static final String ACR_ADVANCED = "http://itsme.services/v2/claim/acr_advanced";
    private static final String ACR_BASIC = "http://itsme.services/v2/claim/acr_basic";
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(3);   // as documented by itsme

    private final ItsmeProperties properties;
    private final ObjectMapper json;
    private final ItsmeStubCrypto crypto = new ItsmeStubCrypto();
    private final Map<String, Authorization> pendingAuthorizations = new ConcurrentHashMap<>();
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** The parameters itsme will act on, as read from the URL (login) or from the request object (confirmation). */
    private record Authorization(String redirectUri, String state, String nonce, String codeChallenge,
                                 String acrValues, Instant expiresAt) {}

    private record PendingCode(Authorization authorization, ItsmeStubScenario scenario, Instant expiresAt) {}

    ItsmeStubController(ItsmeProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
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
        d.put("token_endpoint_auth_methods_supported", List.of("client_secret_post"));
        d.put("id_token_encryption_alg_values_supported", List.of("dir"));
        d.put("id_token_encryption_enc_values_supported", List.of("A256GCM"));
        d.put("id_token_signing_alg_values_supported", List.of("RS256", "HS256"));
        d.put("request_parameter_supported", true);
        d.put("request_object_signing_alg_values_supported", List.of("RS256", "HS256"));
        d.put("request_object_encryption_alg_values_supported", List.of("RSA-OAEP-256"));
        d.put("request_object_encryption_enc_values_supported", List.of("A256GCM"));
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
        // Registered redirect URIs: the login's, and the confirmation's
        boolean confirmation = String.valueOf(query.get("scope"))
                .contains("service:" + properties.confirmation().serviceCode());
        Map<String, String> params = query;
        if (confirmation) {
            // itsme Confirmation: a request object is required, signed with the client secret and encrypted for itsme.
            // Its parameters are the ones itsme acts on; those repeated in the URL must be the same.
            try {
                params = requestObjectParameters(query.get("request"));
            } catch (IllegalArgumentException e) {
                return badRequest("Confirmation: a request object signed with the client secret (HS256) and encrypted "
                        + "with the itsme stub's public key is required (invalid_request_object).");
            }
            for (String repeated : List.of("response_type", "client_id", "scope", "redirect_uri", "state")) {
                if (!String.valueOf(query.get(repeated)).equals(params.get(repeated))) {
                    return badRequest("The parameters of the URL do not match the request object (invalid_request).");
                }
            }
        }
        String registeredRedirectUri = confirmation ? properties.confirmation().redirectUri() : properties.redirectUri();
        if (!properties.clientId().equals(params.get("client_id"))
                || !registeredRedirectUri.equals(params.get("redirect_uri"))
                || !"S256".equals(params.get("code_challenge_method"))
                || params.get("code_challenge") == null) {
            return badRequest("Wrong client_id or redirect_uri, or missing PKCE (S256).");
        }
        String approvalText = null;
        if (confirmation) {
            // itsme Confirmation: a WYSIWYS template is required
            approvalText = freeText(params.get("claims"));
            if (approvalText == null) {
                return badRequest("Confirmation: the free_text WYSIWYS template is required in the claims of the "
                        + "request object.");
            }
        }
        Authorization authorization = new Authorization(params.get("redirect_uri"), params.get("state"),
                params.get("nonce"), params.get("code_challenge"), params.get("acr_values"),
                Instant.now().plus(Duration.ofMinutes(5)));
        String id = randomValue();
        pendingAuthorizations.put(id, authorization);
        return ResponseEntity.ok(page(chooser(id, params, approvalText)));
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
        boolean clientAuthenticated = properties.clientId().equals(form.get("client_id"))
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
                .build();
        try {
            String idToken = crypto.hs256SignedAndEncryptedWithSecret(claims, properties.clientSecret(),
                    properties.clientSecret());
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

    // ------------------------------------------------------------------ page

    private String chooser(String id, Map<String, String> params, String approvalText) {
        StringBuilder html = new StringBuilder();
        if (approvalText != null) {
            // Shown as source text, not rendered: the stub never interprets HTML sent by the application
            html.append("<h2>Operation to approve</h2>")
                    .append("<p>The itsme app would show this text (free_text template; tags b, i, u, br):</p>")
                    .append("<pre class='approval'>").append(esc(approvalText)).append("</pre>")
                    .append("<p class='note'>Read from the <code>claims</code> of the request object (the ")
                    .append("<code>request</code> parameter: signed with the client secret, encrypted for itsme). ")
                    .append("It cannot be read or changed in the address bar. The ")
                    .append("application executes the operation it stored when the confirmation started.</p>")
                    .append("<p class='note'>The request names the itsme account linked to the customer ")
                    .append("(<code>userinfo.sub</code> in the claims): itsme would skip its identification page. ")
                    .append("The stub still offers every account, to show what happens with another one.</p>")
                    .append("<p class='note'>Choosing an account below approves the operation with that account. ")
                    .append("Only the itsme account linked to the logged-in customer is accepted.</p>");
        }
        html.append("<h2>What the application asked for</h2>")
                .append(approvalText != null
                        ? "<p>Read from the <strong>request object</strong>, which only itsme can decrypt.</p>"
                        : "<p>Read from the <strong>URL</strong>. The ID token check is what protects the level: "
                                + "try removing <code>acr_values</code> from the address and reloading.</p>")
                .append("<table>")
                .append(row("acr_values", params.getOrDefault("acr_values", "(none: basic level)")))
                .append(row("claims", params.get("claims")))
                .append(row("scope", params.get("scope")))
                .append("</table>");
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

    /**
     * The parameters of a request object, as strings ("claims" as JSON text). Opened as itsme would: decrypted with
     * the stub's private key, signature verified with the client secret.
     */
    private Map<String, String> requestObjectParameters(String requestObject) {
        if (requestObject == null) {
            throw new IllegalArgumentException("No request object");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        crypto.openRequestObject(requestObject, properties.clientSecret()).getClaims().forEach((name, value) ->
                parameters.put(name, value instanceof Map<?, ?> object ? json.writeValueAsString(object) : str(value)));
        return parameters;
    }

    /** The text of the free_text template, if the claims (JSON) request one (essential, under "id_token"). */
    private String freeText(String claimsJson) {
        Object claims;
        try {
            claims = claimsJson == null ? null : json.readValue(claimsJson, Map.class);
        } catch (Exception e) {
            return null;
        }
        if (!(claims instanceof Map<?, ?> all) || !(all.get("id_token") instanceof Map<?, ?> idToken)) {
            return null;
        }
        Object template = idToken.get(ItsmeClaims.APPROVAL_TEMPLATE_NAME);
        Object text = idToken.get(ItsmeClaims.APPROVAL_TEXT);
        if (!(template instanceof Map<?, ?> t) || !(text instanceof Map<?, ?> x)
                || !ItsmeClaims.FREE_TEXT_TEMPLATE.equals(t.get("value"))
                || !Boolean.TRUE.equals(t.get("essential")) || !Boolean.TRUE.equals(x.get("essential"))) {
            return null;
        }
        return str(x.get("value"));
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
                  .approval{background:#fafafa;border:1px solid #ccc;padding:.75rem;white-space:pre-wrap}
                </style></head><body>
                <h1>itsme stub (local test double)</h1>
                <p class="banner">Local development only. This page stands in for the approval in the itsme app.
                It is not itsme and is not affiliated with Belgian Mobile ID. All accounts are fictitious.</p>
                """ + content + "</body></html>";
    }
}
