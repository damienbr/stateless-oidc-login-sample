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
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.util.List;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * LOCAL DEVELOPMENT AND TESTS ONLY. The cryptography of the itsme side, as we read the itsme v2 documentation:
 * itsme's own signing and encryption keys, ID tokens encrypted with dir + A256GCM (a key derived from the client
 * secret) and signed by itsme with HS256 and the client secret (the profile is registered with HS256), and request
 * objects signed by the client with HS256 and encrypted for itsme with RSA-OAEP-256.
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

    /** What itsme publishes at its jwks_uri: its public signing key and its public encryption key. */
    public JWKSet publicJwkSet() {
        return new JWKSet(List.of(signingKey.toPublicJWK(), encryptionKey.toPublicJWK()));
    }

    /** The JOSE headers of a request object, outside (encryption) and inside (signature), for the tests. */
    public record RequestObjectHeaders(String encryptionAlg, String encryptionEnc, String encryptionKeyId,
                                       String contentType, String signatureAlg) {}

    /**
     * What itsme does with the "request" parameter of a client secret client: decrypt it with its private encryption
     * key, then verify the HS256 signature with the client secret (OIDC Core §10.1, no derivation).
     * @throws IllegalArgumentException if it is not a request object signed with this secret and encrypted for itsme
     */
    public JWTClaimsSet openRequestObject(String requestObject, String clientSecret) {
        try {
            SignedJWT signed = decryptRequestObject(requestObject);
            if (!JWSAlgorithm.HS256.equals(signed.getHeader().getAlgorithm())
                    || !signed.verify(new MACVerifier(clientSecret.getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException("Request object not signed with the client secret (HS256)");
            }
            return signed.getJWTClaimsSet();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request object", e);
        }
    }

    /** For the tests: how the request object was encrypted and signed. */
    public RequestObjectHeaders requestObjectHeaders(String requestObject) {
        try {
            JWEHeader outside = JWEObject.parse(requestObject).getHeader();
            return new RequestObjectHeaders(outside.getAlgorithm().getName(), outside.getEncryptionMethod().getName(),
                    outside.getKeyID(), outside.getContentType(),
                    decryptRequestObject(requestObject).getHeader().getAlgorithm().getName());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request object", e);
        }
    }

    private SignedJWT decryptRequestObject(String requestObject) throws Exception {
        JWEObject encrypted = JWEObject.parse(requestObject);
        encrypted.decrypt(new RSADecrypter(encryptionKey));
        SignedJWT signed = encrypted.getPayload().toSignedJWT();
        if (signed == null) {
            throw new IllegalArgumentException("Request object encrypted but not signed");
        }
        return signed;
    }

    /**
     * Negative case for tests: signed with itsme's RSA key (RS256), as itsme does for a profile registered with RS256,
     * then encrypted with dir + A256GCM. The client accepts HS256 only and must reject it.
     */
    public String rs256SignedAndEncryptedWithSecret(JWTClaimsSet claims, String clientSecret) {
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
     * Expected itsme format: signed with the client secret itself (OIDC Core §10.1, no derivation), then encrypted
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
