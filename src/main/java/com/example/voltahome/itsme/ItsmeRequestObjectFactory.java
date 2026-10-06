package com.example.voltahome.itsme;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
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
 * README: "Authorization request". Packs the authorization parameters into a Request Object:
 * signed with OUR private key (itsme knows it comes from us), then encrypted with ITSME's public key
 * (nobody else can read it). Parameters inside cannot be changed in the browser.
 */
@Component
public class ItsmeRequestObjectFactory {

    private final ItsmeKeys keys;
    private final ItsmeProperties properties;
    private final JWKSource<SecurityContext> itsmeJwkSource;

    public ItsmeRequestObjectFactory(ItsmeKeys keys, ItsmeProperties properties,
                                     JWKSource<SecurityContext> itsmeJwkSource) {
        this.keys = keys;
        this.properties = properties;
        this.itsmeJwkSource = itsmeJwkSource;
    }

    public String create(OAuth2AuthorizationRequest request) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(request.getClientId())
                    .audience(properties.endpoints().issuer())
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                    .claim("client_id", request.getClientId())
                    .claim("response_type", "code")
                    .claim("scope", String.join(" ", request.getScopes()))
                    .claim("redirect_uri", request.getRedirectUri())
                    .claim("state", request.getState())
                    .claim("acr_values", properties.acr())
                    .claim("claims", Map.of("id_token", ItsmeClaims.REQUESTED));
            // nonce (as sent by Spring), code_challenge, code_challenge_method
            request.getAdditionalParameters().forEach(claims::claim);

            // 1. Sign with our private signing key
            SignedJWT signed = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.signingKey().getKeyID()).build(),
                    claims.build());
            signed.sign(new RSASSASigner(keys.signingKey()));

            // 2. Encrypt with itsme's public encryption key
            RSAKey itsmeKey = itsmeEncryptionKey();
            JWEObject encrypted = new JWEObject(
                    new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A128CBC_HS256)
                            .contentType("JWT")              // nested JWT: a signed JWT inside
                            .keyID(itsmeKey.getKeyID())
                            .build(),
                    new Payload(signed));
            encrypted.encrypt(new RSAEncrypter(itsmeKey));
            return encrypted.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build the itsme Request Object", e);
        }
    }

    private RSAKey itsmeEncryptionKey() throws Exception {
        List<JWK> matches = itsmeJwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                .keyType(KeyType.RSA).keyUse(KeyUse.ENCRYPTION).build()), null);
        if (matches.isEmpty()) {
            throw new IllegalStateException("No encryption key in the itsme JWKS");
        }
        return matches.get(0).toRSAKey();
    }
}
