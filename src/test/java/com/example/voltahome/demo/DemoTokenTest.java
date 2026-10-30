package com.example.voltahome.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.example.voltahome.support.ItsmeIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * DEMO ONLY. The protected portal API and its demo bearer token (demo/DemoToken), which stands in for the
 * application's own token. Each test changes one thing in a valid token.
 */
class DemoTokenTest extends ItsmeIntegrationTest {

    @Test
    void linkedCustomerReachesTheProtectedApiWithTheHandedOverToken() throws Exception {
        portal(tokenOfAlex())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alex Martin"))     // from our customer table
                .andExpect(jsonPath("$.customerId").value("C-1001"));
    }

    @Test
    void protectedApiWithoutTokenIsRefused() throws Exception {
        mvc.perform(get("/api/portal"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void failedItsmeLoginIssuesNoToken() throws Exception {
        login(t -> t.sub("stub-sub-unknown")).andExpect(redirectedUrl("/login-error?error=not_linked"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_login_handover").query(Integer.class).single()).isZero();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = tokenOfAlex();

        clock.advance(DemoToken.TTL.plus(Duration.ofSeconds(1)));

        portal(token).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenEditedToAnotherCustomerIsRejected() throws Exception {
        SignedJWT valid = SignedJWT.parse(tokenOfAlex());
        JWTClaimsSet edited = new JWTClaimsSet.Builder(valid.getJWTClaimsSet()).subject("C-1002").build();
        String tampered = valid.getHeader().toBase64URL() + "." + edited.toPayload().toBase64URL()
                + "." + valid.getSignature();                                            // signature of the original

        portal(tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        SignedJWT valid = SignedJWT.parse(tokenOfAlex());
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), valid.getJWTClaimsSet());
        forged.sign(new MACSigner(new OctetSequenceKeyGenerator(256).generate()));

        portal(forged.serialize()).andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        SignedJWT valid = SignedJWT.parse(tokenOfAlex());
        String unsigned = new PlainJWT(valid.getJWTClaimsSet()).serialize();                 // alg "none"

        portal(unsigned).andExpect(status().isUnauthorized());
    }

    private String tokenOfAlex() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        return tokenAfter(login(t -> t.sub("stub-sub-alex")).andExpect(redirectedToLoginHandover()).andReturn());
    }
}
