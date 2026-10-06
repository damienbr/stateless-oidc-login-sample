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
import com.nimbusds.jose.jwk.RSAKey;
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
                  "token_endpoint_auth_methods_supported": ["private_key_jwt", "client_secret_post"],
                  "id_token_encryption_alg_values_supported": ["RSA-OAEP-256", "dir"],
                  "id_token_encryption_enc_values_supported": ["A128CBC-HS256", "A256GCM"]
                }
                """.formatted(wm.baseUrl() + "/v2");
        wm.stubFor(get("/v2/.well-known/openid-configuration").willReturn(okJson(body)));
    }

    /**
     * The token stub only answers a request that carries a client assertion (private_key_jwt) and a PKCE
     * code_verifier; otherwise WireMock answers 404 and the login fails. The assertion's signature is
     * verified separately, in a test that reads the recorded request.
     */
    public void stubToken(WireMockServer wm, String idToken) {
        wm.stubFor(post("/v2/token")
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer"))
                .withRequestBody(containing("client_assertion="))
                .withRequestBody(containing("code_verifier="))
                .willReturn(okJson("""
                        {"access_token":"stub-access-token","token_type":"Bearer","expires_in":3600,"id_token":"%s"}
                        """.formatted(idToken))));
    }

    /** Client secret method: the token request carries client_id and client_secret in the body. */
    public void stubTokenForClientSecret(WireMockServer wm, String idToken, String clientSecret) {
        wm.stubFor(post("/v2/token")
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("client_id=" + clientId))
                .withRequestBody(containing("client_secret=" + clientSecret))
                .withRequestBody(containing("code_verifier="))
                .willReturn(okJson("""
                        {"access_token":"stub-access-token","token_type":"Bearer","expires_in":3600,"id_token":"%s"}
                        """.formatted(idToken))));
    }

    /** Key pairs method: the ID token is encrypted with the application's public key. */
    public IdTokenBuilder idToken(String issuer, String nonce, RSAKey clientEncryptionKey) {
        return new IdTokenBuilder(issuer, nonce, clientEncryptionKey, null);
    }

    /** Client secret method: the ID token is encrypted with a key derived from the client secret. */
    public IdTokenBuilder idTokenForClientSecret(String issuer, String nonce, String clientSecret) {
        return new IdTokenBuilder(issuer, nonce, null, clientSecret);
    }

    /** Fluent builder with valid defaults; each test changes only what it is about. */
    public final class IdTokenBuilder {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private String issuer;
        private String audience = clientId;
        private Instant issuedAt = Instant.now();
        private Duration validity = Duration.ofMinutes(5);
        private RSAKey recipient;
        private String secret;
        private ItsmeStubCrypto signer = crypto;

        private IdTokenBuilder(String issuer, String nonce, RSAKey clientEncryptionKey, String clientSecret) {
            this.issuer = issuer;
            this.recipient = clientEncryptionKey;
            this.secret = clientSecret;
            claims.put("sub", "stub-sub-alex");
            claims.put("nonce", nonce);
            claims.put("acr", ACR_ADVANCED);
            claims.put("http://itsme.services/v2/claim/ongoing_call", false);
        }

        public IdTokenBuilder sub(String sub) { claims.put("sub", sub); return this; }
        public IdTokenBuilder acr(String acr) { return claim("acr", acr); }
        public IdTokenBuilder ongoingCall(boolean value) { return claim("http://itsme.services/v2/claim/ongoing_call", value); }
        public IdTokenBuilder issuer(String value) { this.issuer = value; return this; }
        public IdTokenBuilder audience(String value) { this.audience = value; return this; }
        public IdTokenBuilder issuedAt(Instant value) { this.issuedAt = value; return this; }
        public IdTokenBuilder validity(Duration value) { this.validity = value; return this; }
        /** Encrypt for another key than the application's. */
        public IdTokenBuilder encryptedFor(RSAKey key) { this.recipient = key; this.secret = null; return this; }
        /** Encrypt with a key derived from another secret than the application's. */
        public IdTokenBuilder encryptedWithSecret(String other) { this.secret = other; this.recipient = null; return this; }
        /** Sign with keys that are not the ones itsme publishes. */
        public IdTokenBuilder signedBy(ItsmeStubCrypto other) { this.signer = other; return this; }

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
            return secret != null
                    ? signer.signedAndEncryptedWithSecret(claimsSet(), secret)
                    : signer.signedAndEncrypted(claimsSet(), recipient);
        }

        /** Negative case: encrypted for the application but NOT signed. Must be rejected. */
        public String buildEncryptedButUnsigned() {
            return secret != null
                    ? crypto.encryptedButUnsignedWithSecret(claimsSet(), secret)
                    : crypto.encryptedButUnsigned(claimsSet(), recipient);
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
