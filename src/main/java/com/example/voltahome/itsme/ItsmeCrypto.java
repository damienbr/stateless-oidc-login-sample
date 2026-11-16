package com.example.voltahome.itsme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JWEObject;
import org.springframework.security.oauth2.jwt.BadJwtException;

/** README: "ID token validation". */
public final class ItsmeCrypto {

    private ItsmeCrypto() {
    }

    /**
     * The ID token must be a nested JWT: an encrypted envelope (JWE header "cty": "JWT") with a SIGNED token inside.
     * Encryption alone proves nothing about the origin: with key pairs our encryption key is public, and with the
     * client secret method anyone holding the secret can encrypt. Only the signature inside proves the origin.
     */
    public static void requireNestedSignedJwt(String token) {
        try {
            String contentType = JWEObject.parse(token).getHeader().getContentType();
            if (!"JWT".equalsIgnoreCase(contentType)) {
                throw new BadJwtException("ID token is not a nested signed JWT");
            }
        } catch (ParseException e) {
            throw new BadJwtException("ID token is not an encrypted JWT", e);
        }
    }

    /**
     * Client secret method only: ID token decryption key for "dir" + "A256GCM", derived from the client secret
     * as defined in OpenID Connect Core §10.2 (left-truncated SHA-256 of the UTF-8 secret = 256 bits).
     * The itsme documentation does not describe the derivation: to be confirmed in E2E.
     */
    public static SecretKey idTokenDecryptionKey(String clientSecret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clientSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Client secret method with itsme.id-token-signature=HS256 only: the key that verifies the ID token signature.
     * OIDC Core §10.1: the HMAC key is the UTF-8 bytes of the client secret, used as they are (no derivation).
     * HS256 needs at least 256 bits (RFC 7518 §3.2); a shorter secret is refused at startup, not at the first login.
     */
    public static SecretKey idTokenSignatureKey(String clientSecret) {
        byte[] secret = clientSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("itsme.id-token-signature=HS256 needs a client secret of at least 32 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
