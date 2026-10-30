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
 * README: "Confirmation of an operation", "Several operations, one callback", "Hand-over". Alex (C-1001, itsme
 * account stub-sub-alex) is logged in to the SPA with a demo token and confirms the open order O-2001: the SPA starts
 * the confirmation, itsme calls back, and the SPA redeems the approval. Each test changes one thing in that valid
 * confirmation and states the expected answer. Sam (C-1002) has an order O-2002 and an offer F-3002; Alex also has an
 * offer F-3001.
 */
class ItsmeConfirmationFlowTest extends ItsmeIntegrationTest {

    private static final String CONFIRM_ORDER = "/api/orders/O-2001/confirm";
    private static final String ACCEPT_OFFER = "/api/offers/F-3001/accept";
    private static final String COMPLETE = "/api/confirmation/complete";

    private String alex;

    @BeforeEach
    void alexIsLoggedIn() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        alex = tokenOf("stub-sub-alex");
    }

    // ------------------------------------------------------------------ what we send to itsme

    @Test
    void portalListsTheOperationsOfTheCustomerOnly() throws Exception {
        String body = portal(alex).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"action\":\"" + CONFIRM_ORDER + "\"", "\"action\":\"" + ACCEPT_OFFER + "\"")
                .doesNotContain("O-2002", "F-3002");
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
        assertThat(ro.toString()).doesNotContain(VERIFIER);                   // the hand-over verifier stays with us
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
                "<b>VoltaHome</b><br>Customer number: C-1001<br>Confirm order O-2001: Smart thermostat<br>"
                        + "Total: <b>EUR 149.00</b>"));
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
        mvc.perform(post(CONFIRM_ORDER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoverVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void confirmationCannotBeStartedWithoutHandoverVerifier() throws Exception {
        mvc.perform(withToken(post(CONFIRM_ORDER), alex).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void loginEndpointCannotStartAConfirmation() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/authorization/itsme-confirmation")
                .param("handover_verifier", VERIFIER)).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).doesNotStartWith(itsme.baseUrl());
        assertThat(pendingConfirmations()).isZero();
    }

    // ------------------------------------------------------------------ the right person

    @Nested
    class RightPerson {

        @Test
        void linkedItsmeAccountConfirmsTheOrder() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-alex"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operationType").value("order"))
                    .andExpect(jsonPath("$.operationReference").value("O-2001"));

            assertThat(orderStatus("O-2001")).isEqualTo("CONFIRMED");
            assertThat(confirmedOperations.forCustomer("C-1001"))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.operationType()).isEqualTo("order");
                        assertThat(r.operationReference()).isEqualTo("O-2001");
                        assertThat(r.approvalText()).contains("C-1001", "O-2001", "EUR 149.00");
                        assertThat(r.authenticationLevel()).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
                    });
            String portal = portal(alex).andReturn().getResponse().getContentAsString();
            assertThat(portal).contains("\"operationReference\":\"O-2001\"")
                    .doesNotContain("\"action\":\"" + CONFIRM_ORDER + "\"");
        }

        @Test
        void unknownItsmeAccountCannotConfirmAndIsReported() throws Exception {
            approve(alex, t -> t.sub("stub-sub-stranger"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(approvalsWaiting()).isZero();
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

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(approvalsWaiting()).isZero();
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
        void confirmationStartedByAnotherCustomerCannotBeCompletedWithThisToken() throws Exception {
            givenLinked("C-1002", "stub-sub-sam");
            String sam = tokenOf("stub-sub-sam");
            String approvedForAlex = handoverCode(approve(alex, t -> t.sub("stub-sub-alex"))
                    .andExpect(redirectedToConfirmationHandover()).andReturn());

            complete(sam, approvedForAlex, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("wrong_customer"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
            assertThat(confirmedOperations.forCustomer("C-1002")).isEmpty();
        }

        @Test
        void ongoingCallIsRejectedAndReported() throws Exception {
            approve(alex, t -> t.ongoingCall(true))
                    .andExpect(redirectedUrl("/confirmation-error?error=fraud_suspected"));

            assertThat(approvalsWaiting()).isZero();
            assertThat(fraud.raisedSignals()).singleElement()
                    .satisfies(s -> assertThat(s.signal()).isEqualTo(FraudSignal.ONGOING_CALL));
        }

        @Test
        void refusalInTheItsmeAppChangesNothing() throws Exception {
            StartedConfirmation started = startConfirmation(alex);

            mvc.perform(get(CONFIRMATION_CALLBACK).param("error", "access_denied").param("state", started.state()))
                    .andExpect(redirectedUrl("/confirmation-error?error=access_denied"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(approvalsWaiting()).isZero();
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

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void completionWithoutTokenIsRefused() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            mvc.perform(post(COMPLETE).contentType(MediaType.APPLICATION_JSON).content(
                            json.writeValueAsString(Map.of("handover", code, "verifier", VERIFIER))))
                    .andExpect(status().isUnauthorized());
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void completionWithTheVerifierOfAnotherBrowserIsRefusedAndConsumesTheCode() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            complete(alex, code, ANOTHER_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            complete(alex, code, VERIFIER).andExpect(status().isBadRequest());           // no second try
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void approvalCannotBeRedeemedTwice() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());
            complete(alex, code, VERIFIER).andExpect(status().isOk());

            complete(alex, code, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void approvalExpiresAfterSixtySecondsAndIsPurged() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            clock.advance(ItsmeHandover.TTL.plusSeconds(1));

            complete(alex, code, VERIFIER).andExpect(status().isBadRequest());
            confirmationRequests.purgeExpired();
            assertThat(approvalsWaiting()).isZero();
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void loginHandoverCodeCannotCompleteAConfirmation() throws Exception {
            String loginCode = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            complete(alex, loginCode, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationHandoverCodeCannotEndALogin() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            redeemLogin(code, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }
    }

    // ------------------------------------------------------------------ several operations, one callback

    @Nested
    class SeveralOperations {

        @Test
        void offerIsAcceptedThroughTheSameCallback() throws Exception {
            StartedConfirmation started = startConfirmation(alex, ACCEPT_OFFER);
            JWTClaimsSet ro = stub.crypto().readRequestObject(started.requestObject(),
                    new ImmutableJWKSet<>(keys.publicJwkSet()));
            assertThat(ro.getStringClaim("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
            stubTokenEndpoint(idToken(started.nonce()).build());
            String code = handoverCode(confirmationCallback(started.state())
                    .andExpect(redirectedToConfirmationHandover()).andReturn());

            complete(alex, code, VERIFIER).andExpect(status().isOk());

            assertThat(offerStatus("F-3001")).isEqualTo("ACCEPTED");
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).singleElement().satisfies(r -> {
                assertThat(r.operationType()).isEqualTo("offer");
                assertThat(r.operationReference()).isEqualTo("F-3001");
                assertThat(r.approvalText()).contains("Accept offer F-3001", "EUR 0.2850 per kWh");
            });
        }

        @Test
        void orderOfAnotherCustomerCannotBeSentForConfirmation() throws Exception {
            start(alex, "/api/orders/O-2002/confirm")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(pendingConfirmations()).isZero();
            assertThat(orderStatus("O-2002")).isEqualTo("OPEN");
        }

        @Test
        void offerOfAnotherCustomerCannotBeSentForConfirmation() throws Exception {
            start(alex, "/api/offers/F-3002/accept")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(pendingConfirmations()).isZero();
            assertThat(offerStatus("F-3002")).isEqualTo("OPEN");
        }

        @Test
        void operationInTheCallbackUrlIsIgnored() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            String code = handoverCode(mvc.perform(get(CONFIRMATION_CALLBACK)
                            .param("code", "stub-authorization-code").param("state", started.state())
                            .param("operation_type", "offer").param("operation_reference", "F-3001"))
                    .andExpect(redirectedToConfirmationHandover()).andReturn());
            complete(alex, code, VERIFIER).andExpect(status().isOk());

            assertThat(orderStatus("O-2001")).isEqualTo("CONFIRMED");      // the stored operation
            assertThat(offerStatus("F-3001")).isEqualTo("OPEN");
        }

        @Test
        void storedOperationOfAnotherCustomerIsNotExecuted() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_reference = 'O-2002'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());
            String code = handoverCode(confirmationCallback(started.state()).andReturn());

            complete(alex, code, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(orderStatus("O-2002")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void unknownOperationTypeIsRefused() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_type = 'refund'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());
            String code = handoverCode(confirmationCallback(started.state()).andReturn());

            complete(alex, code, VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("unknown_operation"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
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
            assertThat(approvalsWaiting()).isOne();
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

    private record StartedConfirmation(String location, String state, String nonce, String requestObject) {}

    /** A complete login of the SPA: the token it keeps after the hand-over. */
    private String tokenOf(String sub) throws Exception {
        return tokenAfter(login(t -> t.sub(sub)).andExpect(redirectedToLoginHandover()).andReturn());
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** The customer clicks a button in the SPA: the SPA sends its verifier with the bearer token. */
    private ResultActions start(String token, String action) throws Exception {
        return mvc.perform(withToken(post(action), token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("handoverVerifier", VERIFIER))));
    }

    /** The customer clicks "Confirm order" for O-2001. */
    private StartedConfirmation startConfirmation(String token) throws Exception {
        return startConfirmation(token, CONFIRM_ORDER);
    }

    private StartedConfirmation startConfirmation(String token, String action) throws Exception {
        String body = start(token, action).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String location = json.readTree(body).get("redirect").asString();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new StartedConfirmation(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")),
                decode(query.getFirst("request")));
    }

    /** itsme redirects the browser back with a code. The navigation carries no credential. */
    private ResultActions confirmationCallback(String state) throws Exception {
        return mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code").param("state", state));
    }

    /** Start and callback, where the test only describes the ID token returned by itsme. */
    private ResultActions approve(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        StartedConfirmation started = startConfirmation(token);
        stubTokenEndpoint(idToken.apply(idToken(started.nonce())).build());
        return confirmationCallback(started.state());
    }

    /** The SPA redeems the approval with a verifier and a bearer token. */
    private ResultActions complete(String token, String code, String verifier) throws Exception {
        return mvc.perform(withToken(post(COMPLETE), token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("handover", code, "verifier", verifier))));
    }

    /** A complete confirmation: start, callback, and the SPA's completion with the right verifier. */
    private ResultActions confirm(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        return complete(token, handoverCode(approve(token, idToken)
                .andExpect(redirectedToConfirmationHandover()).andReturn()), VERIFIER);
    }

    /** Portal API, start, callback and completion, as the SPA makes them. */
    private List<MvcResult> wholeConfirmation() throws Exception {
        MvcResult portal = portal(alex).andExpect(status().isOk()).andReturn();
        MvcResult start = start(alex, CONFIRM_ORDER).andExpect(status().isOk()).andReturn();
        String location = json.readTree(start.getResponse().getContentAsString()).get("redirect").asString();
        stubTokenEndpoint(idToken(decode(UriComponentsBuilder.fromUriString(location).build().getQueryParams()
                .getFirst("nonce"))).build());
        String state = decode(UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"));
        MvcResult callback = confirmationCallback(state).andExpect(redirectedToConfirmationHandover()).andReturn();
        MvcResult complete = complete(alex, handoverCode(callback), VERIFIER).andExpect(status().isOk()).andReturn();
        return List.of(portal, start, callback, complete);
    }

    private static ResultMatcher redirectedToConfirmationHandover() {
        return result -> assertThat(result.getResponse().getRedirectedUrl())
                .matches("/#confirmation=[A-Za-z0-9_-]{43}");
    }

    private long pendingConfirmations() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_confirmation_request").query(Long.class).single();
    }

    private long approvalsWaiting() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_confirmation_handover").query(Long.class).single();
    }

    private String orderStatus(String orderId) {
        return jdbc.sql("SELECT status FROM customer_order WHERE order_id = :id").param("id", orderId)
                .query(String.class).single();
    }

    private String offerStatus(String offerId) {
        return jdbc.sql("SELECT status FROM offer WHERE offer_id = :id").param("id", offerId)
                .query(String.class).single();
    }
}
