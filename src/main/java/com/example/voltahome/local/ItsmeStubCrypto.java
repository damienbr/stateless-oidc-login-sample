package com.example.voltahome.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * LOCAL DEVELOPMENT AND TESTS ONLY. The cryptography of the itsme side, as we read the itsme v2 documentation:
 * itsme's own signing key, and ID tokens encrypted with dir + A256GCM (a key derived from the client secret),
 * signed by itsme with RS256 or, if the client was registered so, with HS256 and the client secret.
 * Used by the itsme stub (local profile) and by the WireMock tests. It is not itsme.
 */
public final class ItsmeStubCrypto {

    private final RSAKey signingKey;

    public ItsmeStubCrypto() {
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("itsme-stub-sig").keyUse(KeyUse.SIGNATURE).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What itsme publishes at its jwks_uri: its public signing key. */
    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK());
    }

    /** Expected itsme format: signed by itsme (RS256), then encrypted with dir + A256GCM (key from the secret). */
    public String signedAndEncryptedWithSecret(JWTClaimsSet claims, String clientSecret) {
        try {
            SignedJWT signed = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            signed.sign(new RSASSASigner(signingKey));
            return encryptWithSecret(new Payload(signed), clientSecret, true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Registered with HS256: signed with the client secret itself (OIDC Core §10.1, no derivation), then encrypted
     * with dir + A256GCM. itsme uses the same secret for both; the tests change one.
     */
    public String hs256SignedAndEncryptedWithSecret(JWTClaimsSet claims, String signingSecret, String encryptionSecret) {
        try {
            SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signed.sign(new MACSigner(signingSecret.getBytes(StandardCharsets.UTF_8)));
            return encryptWithSecret(new Payload(signed), encryptionSecret, true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Negative case for tests: encrypted but NOT signed. The client must reject it. */
    public String encryptedButUnsignedWithSecret(JWTClaimsSet claims, String clientSecret) {
        return encryptWithSecret(new Payload(claims.toJSONObject()), clientSecret, false);
    }

    private static String encryptWithSecret(Payload payload, String clientSecret, boolean nested) {
        try {
            // Independent implementation of the OIDC Core §10.2 derivation (SHA-256 of the client secret)
            byte[] key = MessageDigest.getInstance("SHA-256").digest(clientSecret.getBytes(StandardCharsets.UTF_8));
            JWEHeader.Builder header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM);
            if (nested) {
                header.contentType("JWT");
            }
            JWEObject jwe = new JWEObject(header.build(), payload);
            jwe.encrypt(new DirectEncrypter(new SecretKeySpec(key, "AES")));
            return jwe.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
