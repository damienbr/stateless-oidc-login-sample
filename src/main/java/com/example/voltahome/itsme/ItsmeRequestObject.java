package com.example.voltahome.itsme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * README: "Request object". itsme requires the authorization request of a confirmation as a request object (the
 * "request" parameter), also for a client that authenticates with a client secret. The parameters are packed in a JWT:
 * 1. signed by us with HS256 and the client secret (itsme knows the request comes from us, unchanged);
 * 2. then encrypted with RSA-OAEP-256 and ITSME's public encryption key, read from its JWKS (only itsme can read it).
 * The application needs no key pair of its own for either step. The text to approve and the linked "sub" are then
 * neither readable nor editable in the browser.
 */
@Component
public class ItsmeRequestObject {

    private static final Duration LIFETIME = Duration.ofMinutes(5);   // as long as the confirmation in progress

    private final ItsmeProperties properties;
    private final JWKSource<SecurityContext> itsmeJwkSource;
    private final Clock clock;
    private final MACSigner signer;

    public ItsmeRequestObject(ItsmeProperties properties, JWKSource<SecurityContext> itsmeJwkSource, Clock clock) {
        this.properties = properties;
        this.itsmeJwkSource = itsmeJwkSource;
        this.clock = clock;
        try {
            // Refuses a secret too short for HS256 at startup, not at the first confirmation
            this.signer = new MACSigner(ItsmeCrypto.signatureKey(properties.clientSecret()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign itsme request objects with the client secret", e);
        }
    }

    /**
     * The request object of this authorization request: every parameter of the request, plus "acr_values" and "claims",
     * which are sent nowhere else.
     * @param claims the "claims" parameter (ItsmeClaims.confirmation), as a JSON object
     * @throws ItsmeConfirmationException itsme_unavailable: itsme's encryption key could not be read
     */
    public String create(OAuth2AuthorizationRequest request, Map<String, Object> claims) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder parameters = new JWTClaimsSet.Builder()
                .issuer(request.getClientId())
                .audience(properties.endpoints().issuer())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .claim("response_type", "code")
                .claim("client_id", request.getClientId())
                .claim("redirect_uri", request.getRedirectUri())
                .claim("scope", String.join(" ", request.getScopes()))
                .claim("state", request.getState())
                .claim("acr_values", properties.acr())
                .claim("claims", claims);
        request.getAdditionalParameters().forEach(parameters::claim);   // nonce, code_challenge, code_challenge_method

        RSAKey itsmeKey = itsmeEncryptionKey();
        try {
            // 1. Sign with the client secret
            SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), parameters.build());
            signed.sign(signer);

            // 2. Encrypt with itsme's public encryption key
            JWEObject encrypted = new JWEObject(
                    new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                            .contentType("JWT")              // nested JWT: a signed JWT inside
                            .keyID(itsmeKey.getKeyID())
                            .build(),
                    new Payload(signed));
            encrypted.encrypt(new RSAEncrypter(itsmeKey));
            return encrypted.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to build the itsme request object", e);
        }
    }

    /** The RSA key itsme publishes for encryption ("use": "enc"), through the proxy, cached by Nimbus. */
    private RSAKey itsmeEncryptionKey() {
        try {
            List<JWK> keys = itsmeJwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                    .keyType(KeyType.RSA).keyUse(KeyUse.ENCRYPTION).build()), null);
            if (keys.isEmpty()) {
                throw new ItsmeConfirmationException("itsme_unavailable");
            }
            return keys.get(0).toRSAKey();
        } catch (JOSEException e) {
            throw new ItsmeConfirmationException("itsme_unavailable", e);
        }
    }
}
