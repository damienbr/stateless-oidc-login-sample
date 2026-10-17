package com.example.voltahome.demo;

import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import com.example.voltahome.itsme.ItsmeLoginResult;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/**
 * DEMO ONLY. Stands in for the application's own session or token, which is out of scope (README: "Hand-over").
 * After the itsme login, the customer gets a short-lived cookie holding a JWT signed by this application (HS256).
 * The key is generated at startup and kept in memory: a restart logs everyone out, and several instances would not
 * share it. A real application uses its existing session or token management instead.
 */
@Component
public class DemoSession {

    public static final String COOKIE = "voltahome_demo_session";
    static final Duration TTL = Duration.ofMinutes(15);
    private static final String ISSUER = "voltahome-demo-session";

    /** The customer as seen by the portal: our own data only, no itsme claim. */
    public record LoggedInCustomer(String customerId, String authenticationLevel, Instant loggedInAt,
                                   Instant expiresAt) {}

    private final SecretKey key;
    private final NimbusJwtDecoder decoder;

    public DemoSession(Clock clock) throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
        generator.init(256);
        this.key = generator.generateKey();

        // Only HS256 with our key is accepted ("none" and every other algorithm are refused)
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator expiry = new JwtTimestampValidator(Duration.ZERO);
        expiry.setClock(clock);
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(expiry, new JwtIssuerValidator(ISSUER)));
    }

    /** The cookie to set at the end of a successful itsme login. */
    public ResponseCookie issue(ItsmeLoginResult result) {
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
            throw new IllegalStateException("Cannot sign the demo session", e);
        }
        return ResponseCookie.from(COOKIE, jwt.serialize())
                .httpOnly(true)              // not readable by JavaScript
                .secure(true)                // HTTPS only (browsers accept it on http://localhost)
                .sameSite("Lax")             // not sent with cross-site POST requests
                .path("/portal")             // only sent to the portal
                .maxAge(TTL)
                .build();
    }

    /** The logged-in customer, if the request carries a valid, unexpired demo session cookie. */
    public Optional<LoggedInCustomer> read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, COOKIE);
        if (cookie == null) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(cookie.getValue());
            return Optional.of(new LoggedInCustomer(jwt.getSubject(), jwt.getClaimAsString("acr"),
                    jwt.getIssuedAt(), jwt.getExpiresAt()));
        } catch (JwtException e) {
            return Optional.empty();     // forged, tampered with or expired: treated as not logged in
        }
    }
}
