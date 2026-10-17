package com.example.voltahome.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * DEMO ONLY. The protected portal and its demo session cookie (demo/DemoSession), which stands in for the
 * application's own session or token. Each test changes one thing in a valid session.
 */
class DemoSessionTest extends ItsmeIntegrationTest {

    @Test
    void linkedCustomerReachesTheProtectedPortalAfterTheItsmeLogin() throws Exception {
        openPortal(loginAsAlex())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome, Alex Martin.")))     // from our customer table
                .andExpect(content().string(containsString("C-1001")));
    }

    @Test
    void portalWithoutSessionRedirectsToTheHomePage() throws Exception {
        mvc.perform(get("/portal")).andExpect(redirectedUrl("/"));
    }

    @Test
    void failedItsmeLoginIssuesNoSession() throws Exception {
        MvcResult result = login(t -> t.sub("stub-sub-unknown"))
                .andExpect(redirectedUrl("/login-error?error=not_linked"))
                .andReturn();
        assertThat(result.getResponse().getCookie(DemoSession.COOKIE)).isNull();
    }

    @Test
    void sessionCookieIsHttpOnlySecureSameSiteAndLimitedToThePortal() throws Exception {
        Cookie cookie = loginAsAlex().getResponse().getCookie(DemoSession.COOKIE);

        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/portal");
        assertThat(cookie.getMaxAge()).isEqualTo((int) DemoSession.TTL.toSeconds());
    }

    @Test
    void expiredSessionIsRejected() throws Exception {
        Cookie cookie = loginAsAlex().getResponse().getCookie(DemoSession.COOKIE);

        clock.advance(DemoSession.TTL.plus(Duration.ofSeconds(1)));

        mvc.perform(get("/portal").cookie(cookie)).andExpect(redirectedUrl("/"));
    }

    @Test
    void sessionEditedToAnotherCustomerIsRejected() throws Exception {
        SignedJWT valid = SignedJWT.parse(loginAsAlex().getResponse().getCookie(DemoSession.COOKIE).getValue());
        JWTClaimsSet edited = new JWTClaimsSet.Builder(valid.getJWTClaimsSet()).subject("C-1002").build();
        String tampered = valid.getHeader().toBase64URL() + "." + edited.toPayload().toBase64URL()
                + "." + valid.getSignature();                                            // signature of the original

        mvc.perform(get("/portal").cookie(new Cookie(DemoSession.COOKIE, tampered))).andExpect(redirectedUrl("/"));
    }

    @Test
    void sessionSignedWithAnotherKeyIsRejected() throws Exception {
        SignedJWT valid = SignedJWT.parse(loginAsAlex().getResponse().getCookie(DemoSession.COOKIE).getValue());
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), valid.getJWTClaimsSet());
        forged.sign(new MACSigner(new OctetSequenceKeyGenerator(256).generate()));

        mvc.perform(get("/portal").cookie(new Cookie(DemoSession.COOKIE, forged.serialize())))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void unsignedSessionIsRejected() throws Exception {
        SignedJWT valid = SignedJWT.parse(loginAsAlex().getResponse().getCookie(DemoSession.COOKIE).getValue());
        String unsigned = new PlainJWT(valid.getJWTClaimsSet()).serialize();                 // alg "none"

        mvc.perform(get("/portal").cookie(new Cookie(DemoSession.COOKIE, unsigned))).andExpect(redirectedUrl("/"));
    }

    private MvcResult loginAsAlex() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        return login(t -> t.sub("stub-sub-alex")).andExpect(redirectedUrl("/portal")).andReturn();
    }
}
