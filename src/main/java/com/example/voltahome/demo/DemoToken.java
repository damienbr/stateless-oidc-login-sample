package com.example.voltahome.demo;

import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import com.example.voltahome.itsme.ItsmeLoginResult;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Stands in for the application's own token, which is out of scope (README: "Hand-over").
 * After the itsme login, the SPA receives a JWT signed by this application (HS256, 15 minutes) in the JSON answer of
 * the hand-over, keeps it in sessionStorage, and sends it as a bearer token on every API call (DemoSecurityConfig).
 * The key is generated at startup and kept in memory: a restart logs everyone out, and several instances would not
 * share it. A real application uses its existing token management, with a key from a vault.
 */
@Component
public class DemoToken {

    static final Duration TTL = Duration.ofMinutes(15);
    private static final String ISSUER = "voltahome-demo";

    private final SecretKey key;
    private final NimbusJwtDecoder decoder;

    public DemoToken(Clock clock) throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
        generator.init(256);
        this.key = generator.generateKey();

        // Only HS256 with our key is accepted ("none" and every other algorithm are refused)
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator expiry = new JwtTimestampValidator(Duration.ZERO);
        expiry.setClock(clock);
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(expiry, new JwtIssuerValidator(ISSUER)));
    }

    /** The token returned to the SPA at the end of a successful itsme login. Our data only, no itsme claim. */
    String issue(ItsmeLoginResult result) {
        Instant now = result.authenticationTime();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(result.customerId())
                .claim("acr", result.authenticationLevel())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TTL)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the demo token", e);
        }
        return jwt.serialize();
    }

    /** Checks the bearer token of every API call: signature, issuer, expiry. */
    JwtDecoder decoder() {
        return decoder;
    }
}
