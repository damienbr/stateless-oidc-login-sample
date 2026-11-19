package com.example.voltahome.support;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.voltahome.local.ItsmeStubCrypto;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * itsme as seen by the tests: WireMock stubs for the discovery document, the JWKS and the token endpoint,
 * plus an ID token builder in the expected itsme format (see ItsmeStubCrypto).
 * This reproduces OUR READING of the itsme v2 documentation. It does not replace an E2E test against itsme.
 */
public final class ItsmeWireMock {

    public static final String ACR_ADVANCED = "http://itsme.services/v2/claim/acr_advanced";
    public static final String ACR_BASIC = "http://itsme.services/v2/claim/acr_basic";

    private final String clientId;
    private final ItsmeStubCrypto crypto = new ItsmeStubCrypto();

    public ItsmeWireMock(String clientId) {
        this.clientId = clientId;
    }

    public ItsmeStubCrypto crypto() {
        return crypto;
    }

    public void stubJwks(WireMockServer wm) {
        wm.stubFor(get("/v2/jwks").willReturn(okJson(crypto.publicJwkSet().toString())));
    }

    public void stubDiscovery(WireMockServer wm) {
        String body = """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%1$s/authorization",
                  "token_endpoint": "%1$s/token",
                  "jwks_uri": "%1$s/jwks",
                  "token_endpoint_auth_methods_supported": ["client_secret_post"],
                  "id_token_encryption_alg_values_supported": ["dir"],
                  "id_token_encryption_enc_values_supported": ["A256GCM"],
                  "id_token_signing_alg_values_supported": ["RS256", "HS256"]
                }
                """.formatted(wm.baseUrl() + "/v2");
        wm.stubFor(get("/v2/.well-known/openid-configuration").willReturn(okJson(body)));
    }

    /**
     * The token stub only answers a request that carries client_id and client_secret in the body
     * (client_secret_post) and a PKCE code_verifier; otherwise WireMock answers 404 and the login fails.
     */
    public void stubToken(WireMockServer wm, String idToken, String clientSecret) {
        wm.stubFor(post("/v2/token")
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("client_id=" + clientId))
                .withRequestBody(containing("client_secret=" + clientSecret))
                .withRequestBody(containing("code_verifier="))
                .willReturn(okJson("""
                        {"access_token":"stub-access-token","token_type":"Bearer","expires_in":3600,"id_token":"%s"}
                        """.formatted(idToken))));
    }

    /** The ID token is encrypted with a key derived from the client secret. */
    public IdTokenBuilder idToken(String issuer, String nonce, String clientSecret) {
        return new IdTokenBuilder(issuer, nonce, clientSecret);
    }

    /** Fluent builder with valid defaults; each test changes only what it is about. */
    public final class IdTokenBuilder {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private String issuer;
        private String audience = clientId;
        private Instant issuedAt = Instant.now();
        private Duration validity = Duration.ofMinutes(5);
        private String secret;
        private ItsmeStubCrypto signer = crypto;
        private String hs256Secret;

        private IdTokenBuilder(String issuer, String nonce, String clientSecret) {
            this.issuer = issuer;
            this.secret = clientSecret;
            claims.put("sub", "stub-sub-alex");
            claims.put("nonce", nonce);
            claims.put("acr", ACR_ADVANCED);
        }

        public IdTokenBuilder sub(String sub) { claims.put("sub", sub); return this; }
        public IdTokenBuilder acr(String acr) { return claim("acr", acr); }
        public IdTokenBuilder issuer(String value) { this.issuer = value; return this; }
        public IdTokenBuilder audience(String value) { this.audience = value; return this; }
        public IdTokenBuilder issuedAt(Instant value) { this.issuedAt = value; return this; }
        public IdTokenBuilder validity(Duration value) { this.validity = value; return this; }
        /** Encrypt with a key derived from another secret than the application's. */
        public IdTokenBuilder encryptedWithSecret(String other) { this.secret = other; return this; }
        /** Sign with keys that are not the ones itsme publishes. */
        public IdTokenBuilder signedBy(ItsmeStubCrypto other) { this.signer = other; this.hs256Secret = null; return this; }
        /** Sign with HS256 and this secret instead of itsme's RSA key. */
        public IdTokenBuilder signedWithSecret(String value) { this.hs256Secret = value; return this; }
        /** Sign with itsme's RSA key (RS256), the default. */
        public IdTokenBuilder signedWithItsmeKey() { this.signer = crypto; this.hs256Secret = null; return this; }

        /** null removes the claim. */
        public IdTokenBuilder claim(String name, Object value) {
            if (value == null) {
                claims.remove(name);
            } else {
                claims.put(name, value);
            }
            return this;
        }

        /** Expected itsme format: signed by itsme, then encrypted for the application. */
        public String build() {
            return hs256Secret != null
                    ? crypto.hs256SignedAndEncryptedWithSecret(claimsSet(), hs256Secret, secret)
                    : signer.signedAndEncryptedWithSecret(claimsSet(), secret);
        }

        /** Negative case: encrypted for the application but NOT signed. Must be rejected. */
        public String buildEncryptedButUnsigned() {
            return crypto.encryptedButUnsignedWithSecret(claimsSet(), secret);
        }

        private JWTClaimsSet claimsSet() {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(List.of(audience))
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(validity)));
            claims.forEach(b::claim);
            return b.build();
        }
    }
}
