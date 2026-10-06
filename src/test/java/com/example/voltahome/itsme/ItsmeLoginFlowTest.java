package com.example.voltahome.itsme;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.example.voltahome.fraud.FraudService;
import com.example.voltahome.fraud.FraudSignal;
import com.example.voltahome.local.ItsmeStubCrypto;
import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;

/** The itsme protocol, end to end against a WireMock itsme: keys, request, token, ID token, state. */
class ItsmeLoginFlowTest extends ItsmeIntegrationTest {

    @Autowired JdbcAuthorizationRequestRepository authorizationRequests;
    @Autowired RestClient itsmeRestClient;

    // ------------------------------------------------------------------ keys and what we send (README "Keys")

    @Test
    void jwksEndpointPublishesOnlyPublicKeys() throws Exception {
        String body = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"use\":\"sig\"", "\"use\":\"enc\"");
        assertThat(body).doesNotContain("\"d\":", "\"p\":", "\"q\":");   // RSA private parts
    }

    @Test
    void authorizationRequestCarriesASignedAndEncryptedRequestObject() throws Exception {
        StartedLogin started = startLogin();

        assertThat(started.location()).startsWith(itsme.baseUrl() + "/v2/authorization?");
        assertThat(decode(started.location())).doesNotContain("acr_values=").contains("code_challenge_method=S256");

        // What itsme does: decrypt with its private key, verify our signature with our public key
        JWTClaimsSet ro = stub.crypto().readRequestObject(started.requestObject(),
                new ImmutableJWKSet<>(keys.publicJwkSet()));
        assertThat(ro.getIssuer()).isEqualTo(CLIENT_ID);
        assertThat(ro.getAudience()).containsExactly(itsme.baseUrl() + "/v2");
        assertThat(ro.getStringClaim("acr_values")).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
        assertThat(ro.getStringClaim("state")).isEqualTo(started.state());
        assertThat(ro.getStringClaim("nonce")).isEqualTo(started.nonce());
        assertThat(ro.getStringClaim("code_challenge")).isNotBlank();
    }

    @Test
    void authorizationRequestAsksForFraudSignalsOnlyNoIdentityClaim() throws Exception {
        JWTClaimsSet ro = stub.crypto().readRequestObject(startLogin().requestObject(),
                new ImmutableJWKSet<>(keys.publicJwkSet()));

        @SuppressWarnings("unchecked")
        Map<String, Object> idTokenClaims = (Map<String, Object>) ro.getJSONObjectClaim("claims").get("id_token");
        assertThat(idTokenClaims.keySet()).containsExactly(ItsmeClaims.ONGOING_CALL);
        assertThat(ro.getStringClaim("scope").split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_LOGIN");
    }

    @Test
    void tokenRequestUsesASignedClientAssertionAndPkce() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        login(t -> t.sub("stub-sub-alex")).andExpect(status().isOk());

        String body = itsme.findAll(postRequestedFor(urlEqualTo("/v2/token"))).get(0).getBodyAsString();
        Map<String, String> form = formParams(body);
        assertThat(form).containsKey("code_verifier").doesNotContainKey("client_secret");

        // What itsme does: verify the assertion with our public key
        JWTClaimsSet assertion = stub.crypto().verifyClientAssertion(form.get("client_assertion"),
                new ImmutableJWKSet<>(keys.publicJwkSet()));
        assertThat(assertion.getIssuer()).isEqualTo(CLIENT_ID);
        assertThat(assertion.getSubject()).isEqualTo(CLIENT_ID);
        assertThat(assertion.getAudience()).contains(itsme.baseUrl() + "/v2/token");
        assertThat(assertion.getJWTID()).isNotBlank();
    }

    // ------------------------------------------------------------------ customer mapping (README "Customer mapping")

    @Nested
    class CustomerMapping {

        @Test
        void linkedItsmeAccountLogsTheCustomerIn() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");

            login(t -> t.sub("stub-sub-alex"))
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

            String body = login(t -> t.sub("stub-sub-alex").claim("name", "Alex Martin")
                            .claim("email", "alex.martin@example.com"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("stub-sub-alex", "Alex Martin", "alex.martin@example.com");
        }

        @Test
        void linkedButInactiveCustomerIsRejected() throws Exception {
            givenLinked("C-1003", "stub-sub-robin");
            login(t -> t.sub("stub-sub-robin")).andExpect(redirectedUrl("/login-error?error=no_customer"));
        }

        @Test
        void ongoingCallIsRejectedAndReported() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            login(t -> t.sub("stub-sub-alex").ongoingCall(true))
                    .andExpect(redirectedUrl("/login-error?error=fraud_suspected"));

            assertThat(fraud.raisedSignals()).extracting(FraudService.RaisedSignal::signal)
                    .containsExactly(FraudSignal.ONGOING_CALL);
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
        void tokenEncryptedForAnotherKeyIsRejected() throws Exception {
            var otherKey = new RSAKeyGenerator(2048).keyUse(KeyUse.ENCRYPTION).generate().toPublicJWK();
            login(t -> t.encryptedFor(otherKey)).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void tokenSignedWithKeysItsmeDoesNotPublishIsRejected() throws Exception {
            login(t -> t.signedBy(new ItsmeStubCrypto())).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
        }

        @Test
        void encryptedButUnsignedTokenIsRejected() throws Exception {
            // Our encryption key is public: anyone can encrypt for us. Only itsme's signature proves the origin.
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
                    .withBody("{\"error\":\"unauthorized_client\",\"detail\":\"invalid client_assertion\"}")));

            assertThatThrownBy(() -> itsmeRestClient.post().uri(itsme.baseUrl() + "/v2/token").retrieve().toBodilessEntity())
                    .isInstanceOfSatisfying(OAuth2AuthorizationException.class, e -> {
                        assertThat(e.getError().getErrorCode()).isEqualTo("unauthorized_client");
                        assertThat(e.getError().getDescription()).isEqualTo("invalid client_assertion");
                    });
        }

        @Test
        void callbackCannotBeReplayed() throws Exception {
            givenLinked("C-1001", "stub-sub-alex");
            StartedLogin started = startLogin();
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            callback(started.state()).andExpect(status().isOk());
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
            MvcResult result = login(t -> t.sub("stub-sub-alex")).andExpect(status().isOk()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
        }
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
