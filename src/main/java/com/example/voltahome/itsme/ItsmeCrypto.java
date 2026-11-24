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
     * Encryption alone must never be enough: the decoder verifies the signature inside, and this check refuses a
     * token that has none.
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
     * ID token decryption key for "dir" + "A256GCM", derived from the client secret
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
     * README: "Client authentication". The key of every HS256 signature exchanged with itsme: the ID token, signed by
     * itsme, and the request object of a confirmation, signed by us. The itsme profile is registered with HS256.
     * OIDC Core §10.1: the HMAC key is the UTF-8 bytes of the client secret, used as they are (no derivation).
     * HS256 needs at least 256 bits (RFC 7518 §3.2); a shorter secret is refused at startup, not at the first login.
     */
    public static SecretKey signatureKey(String clientSecret) {
        byte[] secret = clientSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("HS256 needs a client secret of at least 32 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
