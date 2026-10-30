package com.example.voltahome.itsme;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.example.voltahome.fraud.FraudSignal;
import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * README: "Confirmation of an operation", "Hand-over". Alex (C-1001, itsme account stub-sub-alex) is logged in to the
 * SPA with a demo token and confirms the advance payment, in three steps: the SPA starts the confirmation, itsme calls
 * back, and the SPA redeems the approval. Each test changes one thing in that valid confirmation and states the
 * expected answer.
 */
class ItsmeConfirmationFlowTest extends ItsmeIntegrationTest {

    private static final String START = "/api/confirmation/start";
    private static final String REDEEM = "/api/confirmation/redeem";

    private String alex;

    @BeforeEach
    void alexIsLoggedIn() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        alex = tokenOf("stub-sub-alex");
    }

    // ------------------------------------------------------------------ what we send to itsme

    @Test
    void portalOffersTheConfirmation() throws Exception {
        portal(alex)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationAvailable").value(true))
                .andExpect(jsonPath("$.operation").value("Set the monthly advance payment to EUR 85.00"));
    }

    @Test
    void confirmationRequestCarriesTheTextToApproveInASignedAndEncryptedRequestObject() throws Exception {
        StartedConfirmation started = startConfirmation(alex);

        assertThat(started.location()).startsWith(itsme.baseUrl() + "/v2/authorization?");
        assertThat(decode(started.location())).doesNotContain("claims=", "acr_values=", "EUR", VERIFIER);

        // What itsme does: decrypt with its private key, verify our signature with our public key
        JWTClaimsSet ro = stub.crypto().readRequestObject(started.requestObject(),
                new ImmutableJWKSet<>(keys.publicJwkSet()));
        assertThat(ro.getStringClaim("scope").split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_CONFIRM");
        assertThat(ro.getStringClaim("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(ro.getStringClaim("acr_values")).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
        assertThat(ro.getStringClaim("state")).isEqualTo(started.state());
        assertThat(ro.getStringClaim("nonce")).isEqualTo(started.nonce());
        assertThat(ro.getStringClaim("code_challenge")).isNotBlank();
        assertThat(ro.toString()).doesNotContain(VERIFIER);                   // the verifier stays with us
    }

    @Test
    void confirmationRequestAsksForTheWysiwysTemplateAndFraudSignalsOnlyNoIdentityClaim() throws Exception {
        JWTClaimsSet ro = stub.crypto().readRequestObject(startConfirmation(alex).requestObject(),
                new ImmutableJWKSet<>(keys.publicJwkSet()));

        @SuppressWarnings("unchecked")
        Map<String, Object> idTokenClaims = (Map<String, Object>) ro.getJSONObjectClaim("claims").get("id_token");
        assertThat(idTokenClaims.keySet()).containsExactlyInAnyOrder(
                ItsmeClaims.APPROVAL_TEMPLATE_NAME, ItsmeClaims.APPROVAL_TEXT, ItsmeClaims.ONGOING_CALL);
        assertThat(idTokenClaims.get(ItsmeClaims.APPROVAL_TEMPLATE_NAME))
                .isEqualTo(Map.of("essential", true, "value", "free_text"));
        assertThat(idTokenClaims.get(ItsmeClaims.APPROVAL_TEXT)).isEqualTo(Map.of("essential", true, "value",
                "<b>VoltaHome</b><br>Customer number: C-1001<br>New monthly advance payment: <b>EUR 85.00</b><br>"
                        + "From the next advance invoice."));
    }

    @Test
    void tokenRequestUsesTheConfirmationRedirectUriASignedClientAssertionAndPkce() throws Exception {
        confirm(alex, t -> t).andExpect(status().isOk());

        List<LoggedRequest> requests = itsme.findAll(postRequestedFor(urlEqualTo("/v2/token")));
        Map<String, String> form = formParams(requests.get(requests.size() - 1).getBodyAsString());
        assertThat(form.get("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(form).containsKey("code_verifier").doesNotContainKey("client_secret");
        stub.crypto().verifyClientAssertion(form.get("client_assertion"), new ImmutableJWKSet<>(keys.publicJwkSet()));
    }

    @Test
    void confirmationCannotBeStartedWithoutToken() throws Exception {
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("handoverVerifier", VERIFIER))))
                .andExpect(status().isUnauthorized());
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void confirmationCannotBeStartedWithoutVerifier() throws Exception {
        mvc.perform(withToken(post(START), alex).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void loginEndpointCannotStartAConfirmation() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/authorization/itsme-confirmation")
                .param("handover_verifier", VERIFIER)).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(pendingConfirmations()).isZero();
    }

    // ------------------------------------------------------------------ the right person

    @Nested
    class RightPerson {

        @Test
        void linkedItsmeAccountConfirmsTheOperation() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-alex"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operation").value("Set the monthly advance payment to EUR 85.00"));

            assertThat(confirmedOperations.forCustomer("C-1001"))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.operationReference()).isEqualTo("advance-payment-85");
                        assertThat(r.approvalText()).contains("C-1001", "EUR 85.00");
                        assertThat(r.authenticationLevel()).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
                    });
            portal(alex).andExpect(jsonPath("$.confirmed[0].operation")
                    .value("Set the monthly advance payment to EUR 85.00"));
        }

        @Test
        void unknownItsmeAccountCannotConfirmAndIsReported() throws Exception {
            approve(alex, t -> t.sub("stub-sub-stranger"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(codesWaiting()).isZero();
            assertThat(fraud.raisedSignals()).singleElement().satisfies(s -> {
                assertThat(s.signal()).isEqualTo(FraudSignal.WRONG_ITSME_ACCOUNT);
                assertThat(s.customerId()).isEqualTo("C-1001");
                assertThat(s.itsmeSub()).isEqualTo("stub-sub-stranger");
            });
        }

        @Test
        void itsmeAccountOfAnotherCustomerCannotConfirm() throws Exception {
            givenLinked("C-1002", "stub-sub-sam");

            approve(alex, t -> t.sub("stub-sub-sam"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(codesWaiting()).isZero();
        }

        @Test
        void itsmeAccountLinkedWhenTheConfirmationStartedButReplacedSinceCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_link SET itsme_sub = 'stub-sub-alex-new' WHERE customer_id = 'C-1001'").update();
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));
        }

        @Test
        void confirmationStartedByAnotherCustomerCannotBeRedeemedWithThisToken() throws Exception {
            givenLinked("C-1002", "stub-sub-sam");
            String sam = tokenOf("stub-sub-sam");
            String approvedForAlex = handoverCode(approve(alex, t -> t.sub("stub-sub-alex"))
                    .andExpect(redirectedToConfirmationHandover()).andReturn());

            redeem(sam, approvedForAlex, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("wrong_customer"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
            assertThat(confirmedOperations.forCustomer("C-1002")).isEmpty();
        }

        @Test
        void ongoingCallIsRejectedAndReported() throws Exception {
            approve(alex, t -> t.ongoingCall(true))
                    .andExpect(redirectedUrl("/confirmation-error?error=fraud_suspected"));

            assertThat(codesWaiting()).isZero();
            assertThat(fraud.raisedSignals()).singleElement()
                    .satisfies(s -> assertThat(s.signal()).isEqualTo(FraudSignal.ONGOING_CALL));
        }

        @Test
        void refusalInTheItsmeAppChangesNothing() throws Exception {
            StartedConfirmation started = startConfirmation(alex);

            mvc.perform(get(CONFIRMATION_CALLBACK).param("error", "access_denied").param("state", started.state()))
                    .andExpect(redirectedUrl("/confirmation-error?error=access_denied"));

            assertThat(codesWaiting()).isZero();
        }

        @Test
        void customerNoLongerActiveCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE customer SET active = FALSE WHERE customer_id = 'C-1001'").update();
            try {
                stubTokenEndpoint(idToken(started.nonce()).build());
                confirmationCallback(started.state())
                        .andExpect(redirectedUrl("/confirmation-error?error=no_customer"));
            } finally {
                jdbc.sql("UPDATE customer SET active = TRUE WHERE customer_id = 'C-1001'").update();
            }
        }
    }

    // ------------------------------------------------------------------ the ID token (same checks as the login)

    @Nested
    class IdTokenValidation {

        @Test
        void basicAcrIsRejected() throws Exception {
            approve(alex, t -> t.acr(ItsmeWireMock.ACR_BASIC))
                    .andExpect(redirectedUrl("/confirmation-error?error=invalid_id_token"));
        }

        @Test
        void idTokenIssuedForAnotherRequestIsRejected() throws Exception {
            approve(alex, t -> t.claim("nonce", "nonce-of-another-request"))
                    .andExpect(redirectedUrl("/confirmation-error?error=invalid_nonce"));
        }

        @Test
        void encryptedButUnsignedTokenIsRejected() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).buildEncryptedButUnsigned());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=invalid_id_token"));
        }
    }

    // ------------------------------------------------------------------ hand-over to the SPA (README "Hand-over")

    @Nested
    class Handover {

        @Test
        void callbackExecutesNothing() throws Exception {
            approve(alex, t -> t).andExpect(redirectedToConfirmationHandover());

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void redemptionWithoutTokenIsRefused() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            mvc.perform(post(REDEEM).contentType(MediaType.APPLICATION_JSON).content(
                            json.writeValueAsString(Map.of("handover", code, "verifier", VERIFIER))))
                    .andExpect(status().isUnauthorized());
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void redemptionWithTheVerifierOfAnotherBrowserIsRefusedAndConsumesTheCode() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            redeem(alex, code, ANOTHER_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            redeem(alex, code, VERIFIER).andExpect(status().isBadRequest());             // no second try
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void approvalCannotBeRedeemedTwice() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());
            redeem(alex, code, VERIFIER).andExpect(status().isOk());

            redeem(alex, code, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void approvalExpiresAfterSixtySecondsAndIsPurged() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            clock.advance(ItsmeHandover.CODE_TTL.plusSeconds(1));

            redeem(alex, code, VERIFIER).andExpect(status().isBadRequest());
            handovers.purgeExpired();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_handover").query(Long.class).single()).isZero();
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void loginCodeCannotBeRedeemedAsAConfirmation() throws Exception {
            String loginCode = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            redeem(alex, loginCode, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationCodeCannotBeRedeemedAsALogin() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            redeemLogin(code, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void callbackRedirectTargetCannotBeChosenByTheRequest() throws Exception {
            // No open redirect: the SPA URLs are configuration, never a request parameter
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code")
                            .param("state", started.state()).param("redirect_uri", "https://attacker.example/"))
                    .andExpect(redirectedToConfirmationHandover());
        }
    }

    // ------------------------------------------------------------------ confirmation in progress

    @Nested
    class ConfirmationInProgress {

        @Test
        void callbackCannotBeReplayed() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());
            confirmationCallback(started.state()).andExpect(redirectedToConfirmationHandover());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=confirmation_not_found"));
            assertThat(codesWaiting()).isOne();
        }

        @Test
        void confirmationExpiresAfterFiveMinutesAndIsPurged() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            clock.advance(JdbcConfirmationRequestRepository.TTL.plus(Duration.ofSeconds(1)));

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=confirmation_not_found"));
            confirmationRequests.purgeExpired();
            assertThat(pendingConfirmations()).isZero();
        }

        @Test
        void loginStateCannotEndAConfirmation() throws Exception {
            StartedLogin login = startLogin();
            stubTokenEndpoint(idToken(login.nonce()).build());

            confirmationCallback(login.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=confirmation_not_found"));
        }

        @Test
        void confirmationStateCannotEndALogin() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            callback(started.state()).andExpect(redirectedUrl("/login-error?error=authorization_request_not_found"));
            assertThat(pendingConfirmations()).isOne();
        }

        @Test
        void noSessionIsCreatedDuringTheWholeConfirmation() throws Exception {
            for (MvcResult result : wholeConfirmation()) {
                assertThat(result.getRequest().getSession(false)).isNull();
                assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
            }
        }

        @Test
        void noCookieIsSetDuringTheWholeConfirmation() throws Exception {
            for (MvcResult result : wholeConfirmation()) {
                assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    @Autowired JdbcConfirmationRequestRepository confirmationRequests;
    @Autowired ItsmeHandover handovers;

    private record StartedConfirmation(String location, String state, String nonce, String requestObject) {}

    /** A complete login of the SPA: the token it keeps after the hand-over. */
    private String tokenOf(String sub) throws Exception {
        return tokenAfter(login(t -> t.sub(sub)).andExpect(redirectedToLoginHandover()).andReturn());
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** Step 1: the customer clicks "Confirm with itsme"; the SPA sends its verifier with its token. */
    private StartedConfirmation startConfirmation(String token) throws Exception {
        String body = mvc.perform(withToken(post(START), token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("handoverVerifier", VERIFIER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String location = json.readTree(body).get("redirect").asString();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new StartedConfirmation(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")),
                decode(query.getFirst("request")));
    }

    /** Step 2: itsme redirects the browser back with a code. The navigation carries no credential. */
    private ResultActions confirmationCallback(String state) throws Exception {
        return mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code").param("state", state));
    }

    /** Steps 1 and 2, where the test only describes the ID token returned by itsme. */
    private ResultActions approve(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        StartedConfirmation started = startConfirmation(token);
        stubTokenEndpoint(idToken.apply(idToken(started.nonce())).build());
        return confirmationCallback(started.state());
    }

    /** Step 3: the SPA redeems the approval with a verifier and a bearer token. */
    private ResultActions redeem(String token, String code, String verifier) throws Exception {
        return mvc.perform(withToken(post(REDEEM), token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("handover", code, "verifier", verifier))));
    }

    /** The three steps, with the right verifier. */
    private ResultActions confirm(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        return redeem(token, handoverCode(approve(token, idToken)
                .andExpect(redirectedToConfirmationHandover()).andReturn()), VERIFIER);
    }

    /** Portal API and the three steps, as the SPA makes them. */
    private List<MvcResult> wholeConfirmation() throws Exception {
        MvcResult portal = portal(alex).andExpect(status().isOk()).andReturn();
        MvcResult start = mvc.perform(withToken(post(START), alex).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("handoverVerifier", VERIFIER))))
                .andExpect(status().isOk()).andReturn();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(json.readTree(
                start.getResponse().getContentAsString()).get("redirect").asString()).build().getQueryParams();
        stubTokenEndpoint(idToken(decode(query.getFirst("nonce"))).build());
        MvcResult callback = confirmationCallback(decode(query.getFirst("state")))
                .andExpect(redirectedToConfirmationHandover()).andReturn();
        MvcResult redeem = redeem(alex, handoverCode(callback), VERIFIER).andExpect(status().isOk()).andReturn();
        return List.of(portal, start, callback, redeem);
    }

    private static ResultMatcher redirectedToConfirmationHandover() {
        return result -> assertThat(result.getResponse().getRedirectedUrl())
                .matches("/#confirmation=[A-Za-z0-9_-]{43}");
    }

    private long pendingConfirmations() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_confirmation_request").query(Long.class).single();
    }

    private long codesWaiting() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_handover WHERE code IS NOT NULL").query(Long.class).single();
    }
}
