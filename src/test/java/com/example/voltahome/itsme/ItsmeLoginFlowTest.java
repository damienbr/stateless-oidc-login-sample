package com.example.voltahome.itsme;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.voltahome.local.ItsmeStubCrypto;
import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The itsme protocol, end to end against a WireMock itsme: request, token, ID token, state. Client secret
 * (client_secret_post), parameters in the URL, ID token encrypted with dir + A256GCM and signed with RS256.
 */
class ItsmeLoginFlowTest extends ItsmeIntegrationTest {

    @Autowired JdbcAuthorizationRequestRepository authorizationRequests;
    @Autowired RestClient itsmeRestClient;

    // ------------------------------------------------------------------ what we send (README "Authorization request")

    @Test
    void authorizationParametersTravelInTheUrl() throws Exception {
        StartedLogin started = startLogin();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(started.location()).build()
                .getQueryParams();

        assertThat(started.location()).startsWith(itsme.baseUrl() + "/v2/authorization?");
        assertThat(decode(query.getFirst("acr_values"))).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).isNotBlank();
    }

    @Test
    void authorizationRequestAsksForNoClaimAndNoIdentityScope() throws Exception {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(startLogin().location()).build()
                .getQueryParams();

        assertThat(query).doesNotContainKey("claims");
        assertThat(decode(query.getFirst("scope")).split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_LOGIN");
    }

    @Test
    void tokenRequestUsesClientSecretPostAndPkce() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        login(t -> t.sub("stub-sub-alex")).andExpect(redirectedToLoginHandover());

        Map<String, String> form = formParams(
                itsme.findAll(postRequestedFor(urlEqualTo("/v2/token"))).get(0).getBodyAsString());
        assertThat(form).containsEntry("client_id", CLIENT_ID)
                .containsEntry("client_secret", CLIENT_SECRET)
                .containsKey("code_verifier")
                .doesNotContainKey("client_assertion");
    }

    @Test
    void noPublicKeysArePublished() throws Exception {
        // The application publishes no keys: the path is not opened, so it only leads to the login
        MvcResult result = mvc.perform(get("/.well-known/jwks.json")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"keys\"");
    }

    // ------------------------------------------------------------------ customer mapping (README "Customer mapping")

    @Nested
    class CustomerMapping {

        @Test
        void linkedItsmeAccountLogsTheCustomerIn() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");

            MvcResult result = login(t -> t.sub("stub-sub-alex")).andExpect(redirectedToLoginHandover()).andReturn();

            portal(tokenAfter(result))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value("C-1001"))
                    .andExpect(jsonPath("$.authenticationLevel").value(ItsmeWireMock.ACR_ADVANCED));
        }

        @Test
        void unknownItsmeAccountIsRejected() throws Exception {
            login(t -> t.sub("stub-sub-unknown")).andExpect(redirectedUrl("/login-error?error=not_linked"));

            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_link").query(Integer.class).single()).isZero();
        }

        @Test
        void unknownItsmeAccountIsNotIdentifiedByTheEmailItsmeSends() throws Exception {
            // itsme does not verify the email: an attacker's itsme account may carry the victim's address
            login(t -> t.sub("stub-sub-unknown").claim("email", "alex.martin@example.com"))
                    .andExpect(redirectedUrl("/login-error?error=not_linked"));
        }

        @Test
        void identityClaimsSentByItsmeAreNotHandedOver() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");

            MvcResult result = login(t -> t.sub("stub-sub-alex").claim("name", "Alex Martin")
                            .claim("email", "alex.martin@example.com"))
                    .andExpect(redirectedToLoginHandover())
                    .andReturn();

            // What the SPA receives (our own customer data, from our customer table) and the token it carries
            String body = redeemLogin(handoverCode(result), BROWSER_CODE_VERIFIER).andReturn().getResponse().getContentAsString();
            String token = SignedJWT.parse(json.readTree(body).get("accessToken").asString()).getPayload().toString();
            assertThat(token).contains("C-1001")
                    .doesNotContain("stub-sub-alex", "Alex Martin", "alex.martin@example.com");
            assertThat(body).contains("C-1001").doesNotContain("stub-sub-alex", "alex.martin@example.com");
        }

        @Test
        void linkedButInactiveCustomerIsRejected() throws Exception {
            givenLinked("C-1003", "stub-sub-robin");
            login(t -> t.sub("stub-sub-robin")).andExpect(redirectedUrl("/login-error?error=no_customer"));
        }
    }

    // ------------------------------------------------------------------ ID token validation (README "ID token validation")

    @Nested
    class IdTokenValidation {

        @Test
        void missingAcrIsRejected() throws Exception {
            login(t -> t.acr(null)).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void basicAcrIsRejected() throws Exception {
            login(t -> t.acr(ItsmeWireMock.ACR_BASIC)).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void wrongNonceIsRejected() throws Exception {
            login(t -> t.claim("nonce", "replayed-nonce")).andExpect(redirectedUrl("/login-error?error=invalid_nonce"));
        }

        @Test
        void wrongIssuerIsRejected() throws Exception {
            login(t -> t.issuer("https://attacker.example/v2")).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void tokenForAnotherClientIsRejected() throws Exception {
            login(t -> t.audience("another-client")).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            login(t -> t.issuedAt(Instant.now().minus(Duration.ofHours(1))))
                    .andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void tokenEncryptedWithAnotherSecretIsRejected() throws Exception {
            login(t -> t.encryptedWithSecret("another-secret")).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void tokenSignedWithKeysItsmeDoesNotPublishIsRejected() throws Exception {
            login(t -> t.signedBy(new ItsmeStubCrypto())).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void hs256TokenIsRejectedWhenRs256IsConfigured() throws Exception {
            // Default itsme.id-token-signature=RS256: an HMAC made with the client secret is not accepted
            login(t -> t.signedWithSecret(CLIENT_SECRET)).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void encryptedButUnsignedTokenIsRejected() throws Exception {
            // Anyone holding the client secret can encrypt: only itsme's signature proves the origin
            StartedLogin started = startLogin();
            stubTokenEndpoint(idToken(started.nonce()).buildEncryptedButUnsigned());

            callback(started.state()).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }
    }

    // ------------------------------------------------------------------ token request and login in progress

    @Nested
    class TokenRequestAndState {

        @Test
        void tokenEndpointErrorIsReported() throws Exception {
            StartedLogin started = startLogin();
            itsme.stubFor(WireMock.post("/v2/token").willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));

            callback(started.state()).andExpect(redirectedUrl("/login-error?error=invalid_grant"));
        }

        @Test
        void tokenEndpointErrorDetailIsKept() {
            // itsme puts the description in "detail", not in the standard "error_description"
            itsme.stubFor(WireMock.post("/v2/token").willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"unauthorized_client\",\"detail\":\"invalid client_secret\"}")));

            assertThatThrownBy(() -> itsmeRestClient.post().uri(itsme.baseUrl() + "/v2/token").retrieve().toBodilessEntity())
                    .isInstanceOfSatisfying(OAuth2AuthorizationException.class, e -> {
                        assertThat(e.getError().getErrorCode()).isEqualTo("unauthorized_client");
                        assertThat(e.getError().getDescription()).isEqualTo("invalid client_secret");
                    });
        }

        @Test
        void callbackCannotBeReplayed() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            StartedLogin started = startLogin();
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            callback(started.state()).andExpect(redirectedToLoginHandover());
            callback(started.state()).andExpect(redirectedUrl("/login-error?error=authorization_request_not_found"));
        }

        @Test
        void unknownStateIsRejected() throws Exception {
            startLogin();
            callback("forged-state").andExpect(redirectedUrl("/login-error?error=authorization_request_not_found"));
        }

        @Test
        void loginInProgressExpiresAfterFiveMinutesAndIsPurged() throws Exception {
            StartedLogin started = startLogin();
            stubTokenEndpoint(idToken(started.nonce()).build());

            clock.advance(Duration.ofMinutes(6));

            callback(started.state()).andExpect(redirectedUrl("/login-error?error=authorization_request_not_found"));
            authorizationRequests.purgeExpired();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_authorization_request").query(Integer.class).single()).isZero();
        }

        @Test
        void concurrentCallbacksWithTheSameStateSucceedOnlyOnce() throws Exception {
            StartedLogin started = startLogin();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setParameter("state", started.state());

            CountDownLatch go = new CountDownLatch(1);
            Callable<OAuth2AuthorizationRequest> consume = () -> {
                go.await();
                return authorizationRequests.removeAuthorizationRequest(request, new MockHttpServletResponse());
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<OAuth2AuthorizationRequest>> results = new ArrayList<>();
                results.add(pool.submit(consume));
                results.add(pool.submit(consume));
                go.countDown();
                long successes = 0;
                for (Future<OAuth2AuthorizationRequest> r : results) {
                    if (r.get() != null) {
                        successes++;
                    }
                }
                assertThat(successes).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void noSessionIsCreatedDuringTheWholeFlow() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            for (MvcResult result : wholeLogin()) {
                assertThat(result.getRequest().getSession(false)).isNull();
                assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ hand-over to the SPA (README "Hand-over")

    @Nested
    class Handover {

        @Test
        void loginStartedWithoutBrowserCodeChallengeCannotBeHandedOver() throws Exception {
            // Not started by the SPA: nobody holds a browser code verifier, so nobody may receive the result
            givenLinked("C-1001", "stub-sub-alex");
            StartedLogin started = startLogin(post("/oauth2/authorization/itsme"));
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            callback(started.state()).andExpect(redirectedUrl("/login-error?error=invalid_request"));
            assertThat(codesWaiting()).isZero();
        }

        @Test
        void loginStartedWithALinkCannotBeHandedOver() throws Exception {
            // The start is a POST from the SPA: a link (a GET), wherever it comes from, starts nothing to hand over
            givenLinked("C-1001", "stub-sub-alex");
            StartedLogin started = startLogin(get("/oauth2/authorization/itsme")
                    .param("browser_code_challenge", BROWSER_CODE_CHALLENGE));
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            callback(started.state()).andExpect(redirectedUrl("/login-error?error=invalid_request"));
            assertThat(codesWaiting()).isZero();
        }

        @Test
        void browserCodeChallengeIsKeptWithTheLoginAndNotSentToItsme() throws Exception {
            StartedLogin started = startLogin();

            assertThat(decode(started.location())).doesNotContain(BROWSER_CODE_CHALLENGE);
            assertThat(jdbc.sql("SELECT browser_code_challenge FROM itsme_handover WHERE state = :state")
                    .param("state", started.state()).query(String.class).single()).isEqualTo(BROWSER_CODE_CHALLENGE);
        }

        @Test
        void browserCodeChallengeAloneCannotRedeemTheCode() throws Exception {
            // Whoever saw the challenge (the start request, the database) still lacks the verifier
            givenLinked("C-1001", "stub-sub-alex");
            String code = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            redeemLogin(code, BROWSER_CODE_CHALLENGE)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void loginCodeWithoutBrowserCodeVerifierIsRefused() throws Exception {
            // The code alone is not enough: the browser that started the login must show its verifier
            givenLinked("C-1001", "stub-sub-alex");
            String code = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            mvc.perform(post(LOGIN_HANDOVER).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("handover", code))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void callbackRedirectsToTheSpaWithOnlyAOneTimeCodeInTheFragment() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");

            MvcResult result = login(t -> t.sub("stub-sub-alex")).andExpect(redirectedToLoginHandover()).andReturn();

            assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        }

        @Test
        void handoverReturnsTheTokenAndOurCustomerDataAsJson() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            MvcResult result = login(t -> t.sub("stub-sub-alex")).andExpect(redirectedToLoginHandover()).andReturn();

            redeemLogin(handoverCode(result), BROWSER_CODE_VERIFIER)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.customerId").value("C-1001"))
                    .andExpect(jsonPath("$.displayName").value("Alex Martin"));      // from our customer table
        }

        @Test
        void handoverWithTheVerifierOfAnotherBrowserIsRefusedAndConsumesTheCode() throws Exception {
            // A login CSRF: the attacker's callback link opened in the victim's browser, which holds another verifier
            givenLinked("C-1001", "stub-sub-alex");
            String code = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            redeemLogin(code, ANOTHER_BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            redeemLogin(code, BROWSER_CODE_VERIFIER).andExpect(status().isBadRequest());           // no second try
        }

        @Test
        void handoverCodeCannotBeReplayed() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            String code = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());
            redeemLogin(code, BROWSER_CODE_VERIFIER).andExpect(status().isOk());

            redeemLogin(code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void unknownHandoverCodeIsRefused() throws Exception {
            redeemLogin("forged-code", BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void handoverCodeExpiresAfterSixtySecondsAndIsPurged() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            String code = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            clock.advance(ItsmeHandover.CODE_TTL.plusSeconds(1));

            redeemLogin(code, BROWSER_CODE_VERIFIER).andExpect(status().isBadRequest());
            handovers.purgeExpired();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_handover").query(Integer.class).single()).isZero();
        }

        @Test
        void failedLoginLeavesNothingToRedeem() throws Exception {
            login(t -> t.sub("stub-sub-unknown")).andExpect(redirectedUrl("/login-error?error=not_linked"));

            assertThat(codesWaiting()).isZero();
        }

        @Test
        void noCookieIsSetDuringTheWholeFlow() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            for (MvcResult result : wholeLogin()) {
                assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            }
        }
    }

    @Autowired ItsmeHandover handovers;

    private long codesWaiting() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_handover WHERE code IS NOT NULL").query(Long.class).single();
    }

    /** Start, callback, hand-over and a call to the protected API, as the SPA makes them. */
    private List<MvcResult> wholeLogin() throws Exception {
        StartedLogin started = startLogin();
        stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());
        MvcResult callback = callback(started.state()).andExpect(redirectedToLoginHandover()).andReturn();
        MvcResult handover = redeemLogin(handoverCode(callback), BROWSER_CODE_VERIFIER).andExpect(status().isOk()).andReturn();
        String token = json.readTree(handover.getResponse().getContentAsString()).get("accessToken").asString();
        MvcResult portal = portal(token).andExpect(status().isOk()).andReturn();
        return List.of(started.result(), callback, handover, portal);
    }

    // ------------------------------------------------------------------ discovery check (README "Configuration")

    @Autowired ItsmeDiscoveryCheck discoveryCheck;

    @Test
    void discoveryCheckIsOkWhenConfigurationIsConsistent() {
        assertThat(discoveryCheck.check().status()).isEqualTo(ItsmeDiscoveryCheck.Status.OK);
    }

    @Test
    void discoveryCheckReportsUnreachableItsme() {
        itsme.stubFor(WireMock.get("/v2/.well-known/openid-configuration").willReturn(aResponse().withStatus(503)));
        assertThat(discoveryCheck.check().status()).isEqualTo(ItsmeDiscoveryCheck.Status.UNREACHABLE);
    }

    @Test
    void discoveryCheckReportsAMovedEndpoint() {
        itsme.stubFor(WireMock.get("/v2/.well-known/openid-configuration").willReturn(okJson("""
                {"issuer": "%1$s/v2", "authorization_endpoint": "%1$s/v2/authorization",
                 "token_endpoint": "%1$s/v3/token", "jwks_uri": "%1$s/v2/jwks"}
                """.formatted(itsme.baseUrl()))));

        ItsmeDiscoveryCheck.Result result = discoveryCheck.check();
        assertThat(result.status()).isEqualTo(ItsmeDiscoveryCheck.Status.MISMATCH);
        assertThat(result.mismatches()).singleElement().asString().startsWith("token_endpoint");
    }
}
