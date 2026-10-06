package com.example.voltahome.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWEDecryptionKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * LOCAL DEVELOPMENT AND TESTS ONLY. The cryptography of the itsme side, as we read the itsme v2 documentation:
 * itsme's own key pairs, ID tokens signed by itsme (RS256) and encrypted for the client, and the checks itsme
 * makes on what the client sends (Request Object, client assertion). ID token encryption depends on the client's
 * method: RSA-OAEP-256 + A128CBC-HS256 with the client's public key (key pairs), or dir + A256GCM with a key
 * derived from the client secret.
 * Used by the itsme stub (local profile) and by the WireMock tests. It is not itsme.
 */
public final class ItsmeStubCrypto {

    private final RSAKey signingKey;
    private final RSAKey encryptionKey;

    public ItsmeStubCrypto() {
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("itsme-stub-sig").keyUse(KeyUse.SIGNATURE).generate();
            this.encryptionKey = new RSAKeyGenerator(2048).keyID("itsme-stub-enc").keyUse(KeyUse.ENCRYPTION).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What itsme publishes at its jwks_uri: public signing and encryption keys. */
    public JWKSet publicJwkSet() {
        return new JWKSet(List.<JWK>of(signingKey.toPublicJWK(), encryptionKey.toPublicJWK()));
    }

    /** Expected itsme format: signed by itsme (RS256), then encrypted with the client's public key. */
    public String signedAndEncrypted(JWTClaimsSet claims, RSAKey clientEncryptionKey) {
        try {
            SignedJWT signed = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            signed.sign(new RSASSASigner(signingKey));
            return encrypt(new Payload(signed), clientEncryptionKey, true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Negative case for tests: encrypted for the client, but NOT signed. The client must reject it. */
    public String encryptedButUnsigned(JWTClaimsSet claims, RSAKey clientEncryptionKey) {
        return encrypt(new Payload(claims.toJSONObject()), clientEncryptionKey, false);
    }

    /** Client secret method: signed by itsme (RS256), then encrypted with dir + A256GCM (key from the secret). */
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

    /** Negative case for tests, client secret method: encrypted but NOT signed. */
    public String encryptedButUnsignedWithSecret(JWTClaimsSet claims, String clientSecret) {
        return encryptWithSecret(new Payload(claims.toJSONObject()), clientSecret, false);
    }

    /** What itsme does with a Request Object: decrypt with its private key, verify the client's signature. */
    public JWTClaimsSet readRequestObject(String requestObject, JWKSource<SecurityContext> clientKeys) throws Exception {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWEKeySelector(new JWEDecryptionKeySelector<>(JWEAlgorithm.RSA_OAEP_256,
                EncryptionMethod.A128CBC_HS256, new ImmutableJWKSet<>(new JWKSet(encryptionKey))));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, clientKeys));
        return processor.process(requestObject, null);
    }

    /** What itsme does with a private_key_jwt client assertion: verify the client's signature. */
    public JWTClaimsSet verifyClientAssertion(String assertion, JWKSource<SecurityContext> clientKeys) throws Exception {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, clientKeys));
        return processor.process(assertion, null);
    }

    /** The client's public encryption key, as itsme finds it in the client's JWKS. */
    public static RSAKey encryptionKeyOf(JWKSet clientJwks) {
        return clientJwks.getKeys().stream()
                .filter(k -> KeyUse.ENCRYPTION.equals(k.getKeyUse()))
                .findFirst()
                .map(JWK::toRSAKey)
                .orElseThrow(() -> new IllegalStateException("No encryption key in the client JWKS"));
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

    private static String encrypt(Payload payload, RSAKey recipient, boolean nested) {
        try {
            JWEHeader.Builder header = new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A128CBC_HS256)
                    .keyID(recipient.getKeyID());
            if (nested) {
                header.contentType("JWT");
            }
            JWEObject jwe = new JWEObject(header.build(), payload);
            jwe.encrypt(new RSAEncrypter(recipient));
            return jwe.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
