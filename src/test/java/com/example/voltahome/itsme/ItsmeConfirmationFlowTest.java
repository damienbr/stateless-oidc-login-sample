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
 * README: "Confirmation of an operation", "Hand-over", "Several operations, one callback". Alex (C-1001, itsme account
 * stub-sub-alex) is logged in to the SPA with a demo token and confirms the open order O-2001, in three steps: the SPA
 * starts the confirmation, itsme calls back, and the SPA redeems the approval. Each test changes one thing in that
 * valid confirmation and states the expected answer. Alex also has an offer F-3001; Sam (C-1002) has an order O-2002
 * and an offer F-3002.
 */
class ItsmeConfirmationFlowTest extends ItsmeIntegrationTest {

    private static final String START = "/api/orders/O-2001/confirm";
    private static final String ACCEPT_OFFER = "/api/offers/F-3001/accept";
    private static final String REDEEM = "/api/confirmation/redeem";

    private String alex;

    @BeforeEach
    void alexIsLoggedIn() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        alex = tokenOf("stub-sub-alex");
    }

    // ------------------------------------------------------------------ what we send to itsme

    @Test
    void portalListsTheOperationsOfTheCustomerOnly() throws Exception {
        String portal = portal(alex)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationAvailable").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(portal).contains("/api/orders/O-2001/confirm", "/api/offers/F-3001/accept")
                .doesNotContain("O-2002", "F-3002");
    }

    @Test
    void confirmationRequestCarriesTheTextToApproveInASignedAndEncryptedRequestObject() throws Exception {
        StartedConfirmation started = startConfirmation(alex);

        assertThat(started.location()).startsWith(itsme.baseUrl() + "/v2/authorization?");
        assertThat(decode(started.location())).doesNotContain("claims=", "acr_values=", "EUR", BROWSER_CODE_CHALLENGE);

        // What itsme does: decrypt with its private key, verify our signature with our public key
        JWTClaimsSet ro = stub.crypto().readRequestObject(started.requestObject(),
                new ImmutableJWKSet<>(keys.publicJwkSet()));
        assertThat(ro.getStringClaim("scope").split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_CONFIRM");
        assertThat(ro.getStringClaim("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(ro.getStringClaim("acr_values")).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
        assertThat(ro.getStringClaim("state")).isEqualTo(started.state());
        assertThat(ro.getStringClaim("nonce")).isEqualTo(started.nonce());
        assertThat(ro.getStringClaim("code_challenge")).isNotBlank();
        assertThat(ro.toString()).doesNotContain(BROWSER_CODE_CHALLENGE);     // the browser code challenge stays with us
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
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("browserCodeChallenge", BROWSER_CODE_CHALLENGE))))
                .andExpect(status().isUnauthorized());
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void confirmationCannotBeStartedWithoutBrowserCodeChallenge() throws Exception {
        mvc.perform(withToken(post(START), alex).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void loginEndpointCannotStartAConfirmation() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/authorization/itsme-confirmation")
                .param("browser_code_challenge", BROWSER_CODE_CHALLENGE)).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
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
            portal(alex).andExpect(jsonPath("$.confirmed[0].operationReference").value("O-2001"));
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

            redeem(sam, approvedForAlex, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("wrong_customer"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
            assertThat(confirmedOperations.forCustomer("C-1002")).isEmpty();
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
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
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void redemptionWithoutTokenIsRefused() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            mvc.perform(post(REDEEM).contentType(MediaType.APPLICATION_JSON).content(
                            json.writeValueAsString(Map.of("handover", code, "browserCodeVerifier", BROWSER_CODE_VERIFIER))))
                    .andExpect(status().isUnauthorized());
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void redemptionWithTheVerifierOfAnotherBrowserIsRefusedAndConsumesTheCode() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            redeem(alex, code, ANOTHER_BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            redeem(alex, code, BROWSER_CODE_VERIFIER).andExpect(status().isBadRequest());             // no second try
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void longestApprovalTextIsHandedOverIntact() throws Exception {
            // 7500 quotes: twice as long once written as JSON in itsme_handover.result
            String longest = "\"".repeat(ItsmeFreeText.MAX_LENGTH);
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET approval_text = :text").param("text", longest).update();
            stubTokenEndpoint(idToken(started.nonce()).build());
            String code = handoverCode(confirmationCallback(started.state())
                    .andExpect(redirectedToConfirmationHandover()).andReturn());

            redeem(alex, code, BROWSER_CODE_VERIFIER).andExpect(status().isOk());

            assertThat(confirmedOperations.forCustomer("C-1001")).singleElement()
                    .satisfies(r -> assertThat(r.approvalText()).isEqualTo(longest));
        }

        @Test
        void approvalCannotBeRedeemedTwice() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());
            redeem(alex, code, BROWSER_CODE_VERIFIER).andExpect(status().isOk());

            redeem(alex, code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void approvalExpiresAfterSixtySecondsAndIsPurged() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            clock.advance(ItsmeHandover.CODE_TTL.plusSeconds(1));

            redeem(alex, code, BROWSER_CODE_VERIFIER).andExpect(status().isBadRequest());
            handovers.purgeExpired();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_handover").query(Long.class).single()).isZero();
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void loginCodeCannotBeRedeemedAsAConfirmation() throws Exception {
            String loginCode = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            redeem(alex, loginCode, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationCodeCannotBeRedeemedAsALogin() throws Exception {
            String code = handoverCode(approve(alex, t -> t).andReturn());

            redeemLogin(code, BROWSER_CODE_VERIFIER)
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

            redeem(alex, code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operationType").value("offer"))
                    .andExpect(jsonPath("$.operationReference").value("F-3001"));

            assertThat(offerStatus("F-3001")).isEqualTo("ACCEPTED");
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).singleElement().satisfies(r -> {
                assertThat(r.operationType()).isEqualTo("offer");
                assertThat(r.approvalText()).contains("Accept offer F-3001", "EUR 0.2850 per kWh");
            });
        }

        @Test
        void orderOfAnotherCustomerCannotBeSentForConfirmation() throws Exception {
            start(alex, "/api/orders/O-2002/confirm")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(pendingConfirmations()).isZero();
        }

        @Test
        void offerOfAnotherCustomerCannotBeSentForConfirmation() throws Exception {
            start(alex, "/api/offers/F-3002/accept")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(pendingConfirmations()).isZero();
        }

        @Test
        void confirmedOrderCannotBeSentForConfirmationAgain() throws Exception {
            confirm(alex, t -> t).andExpect(status().isOk());

            start(alex, START)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));
        }

        @Test
        void operationInTheCallbackUrlIsIgnored() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            String code = handoverCode(mvc.perform(get(CONFIRMATION_CALLBACK)
                            .param("code", "stub-authorization-code").param("state", started.state())
                            .param("operation_type", "offer").param("operation_reference", "F-3001"))
                    .andExpect(redirectedToConfirmationHandover()).andReturn());
            redeem(alex, code, BROWSER_CODE_VERIFIER).andExpect(status().isOk());

            assertThat(orderStatus("O-2001")).isEqualTo("CONFIRMED");      // the stored operation
            assertThat(offerStatus("F-3001")).isEqualTo("OPEN");
        }

        @Test
        void storedOperationOfAnotherCustomerIsNotExecuted() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_reference = 'O-2002'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());
            String code = handoverCode(confirmationCallback(started.state()).andReturn());

            redeem(alex, code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(orderStatus("O-2002")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void orderConfirmedInTheMeantimeIsNotExecutedTwice() throws Exception {
            // Two confirmations of the same order, started before either is redeemed: the execution checks it again
            StartedConfirmation first = startConfirmation(alex);
            StartedConfirmation second = startConfirmation(alex);
            stubTokenEndpoint(idToken(first.nonce()).build());
            String firstCode = handoverCode(confirmationCallback(first.state()).andReturn());
            stubTokenEndpoint(idToken(second.nonce()).build());
            String secondCode = handoverCode(confirmationCallback(second.state()).andReturn());
            redeem(alex, firstCode, BROWSER_CODE_VERIFIER).andExpect(status().isOk());

            redeem(alex, secondCode, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void unknownOperationTypeIsRefused() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_type = 'refund'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());
            String code = handoverCode(confirmationCallback(started.state()).andReturn());

            redeem(alex, code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("unknown_operation"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void confirmedOrderIsReloadedAsStoredNow() throws Exception {
            // What the SPA does after the redemption: reload the record by the type and reference it returned
            confirm(alex, t -> t).andExpect(status().isOk());

            mvc.perform(withToken(get("/api/orders/O-2001"), alex))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.amount").value(149.00));
        }

        @Test
        void orderOfAnotherCustomerCannotBeReloaded() throws Exception {
            mvc.perform(withToken(get("/api/orders/O-2002"), alex)).andExpect(status().isNotFound());
            mvc.perform(withToken(get("/api/offers/F-3002"), alex)).andExpect(status().isNotFound());
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

    /** Step 1: the customer clicks "Confirm order"; the SPA sends its browser code challenge with its token. */
    private StartedConfirmation startConfirmation(String token) throws Exception {
        return startConfirmation(token, START);
    }

    /** Step 1 for the operation behind this start endpoint. */
    private StartedConfirmation startConfirmation(String token, String startPath) throws Exception {
        String body = start(token, startPath)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String location = json.readTree(body).get("redirect").asString();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new StartedConfirmation(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")),
                decode(query.getFirst("request")));
    }

    private ResultActions start(String token, String startPath) throws Exception {
        return mvc.perform(withToken(post(startPath), token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("browserCodeChallenge", BROWSER_CODE_CHALLENGE))));
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

    /** Step 3: the SPA redeems the approval with a browser code verifier and a bearer token. */
    private ResultActions redeem(String token, String code, String verifier) throws Exception {
        return mvc.perform(withToken(post(REDEEM), token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("handover", code, "browserCodeVerifier", verifier))));
    }

    /** The three steps, with the right browser code verifier. */
    private ResultActions confirm(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        return redeem(token, handoverCode(approve(token, idToken)
                .andExpect(redirectedToConfirmationHandover()).andReturn()), BROWSER_CODE_VERIFIER);
    }

    /** Portal API and the three steps, as the SPA makes them. */
    private List<MvcResult> wholeConfirmation() throws Exception {
        MvcResult portal = portal(alex).andExpect(status().isOk()).andReturn();
        MvcResult start = mvc.perform(withToken(post(START), alex).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("browserCodeChallenge", BROWSER_CODE_CHALLENGE))))
                .andExpect(status().isOk()).andReturn();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(json.readTree(
                start.getResponse().getContentAsString()).get("redirect").asString()).build().getQueryParams();
        stubTokenEndpoint(idToken(decode(query.getFirst("nonce"))).build());
        MvcResult callback = confirmationCallback(decode(query.getFirst("state")))
                .andExpect(redirectedToConfirmationHandover()).andReturn();
        MvcResult redeem = redeem(alex, handoverCode(callback), BROWSER_CODE_VERIFIER).andExpect(status().isOk()).andReturn();
        return List.of(portal, start, callback, redeem);
    }

    private static ResultMatcher redirectedToConfirmationHandover() {
        return result -> assertThat(result.getResponse().getRedirectedUrl())
                .matches("/#confirmation=[A-Za-z0-9_-]{43}");
    }

    private long pendingConfirmations() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_confirmation_request").query(Long.class).single();
    }

    private String orderStatus(String orderId) {
        return jdbc.sql("SELECT status FROM customer_order WHERE order_id = :id").param("id", orderId)
                .query(String.class).single();
    }

    private String offerStatus(String offerId) {
        return jdbc.sql("SELECT status FROM offer WHERE offer_id = :id").param("id", offerId)
                .query(String.class).single();
    }

    private long codesWaiting() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_handover WHERE code IS NOT NULL").query(Long.class).single();
    }
}
