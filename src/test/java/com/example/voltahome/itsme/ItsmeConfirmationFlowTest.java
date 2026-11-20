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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * README: "Confirmation of an operation", "Several operations, one callback". Alex (C-1001, itsme account
 * stub-sub-alex) is logged in to the SPA with a demo token and confirms the open order O-2001, in two steps: the SPA
 * starts the confirmation, and itsme calls back: the operation is executed there. Each test changes one thing in that
 * valid confirmation and states the expected answer. Alex also has an offer F-3001; Sam (C-1002) has an order O-2002
 * and an offer F-3002.
 */
class ItsmeConfirmationFlowTest extends ItsmeIntegrationTest {

    private static final String START = "/api/orders/O-2001/confirm";
    private static final String ACCEPT_OFFER = "/api/offers/F-3001/accept";

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
                .andReturn().getResponse().getContentAsString();
        assertThat(portal).contains("/api/orders/O-2001/confirm", "/api/offers/F-3001/accept")
                .doesNotContain("O-2002", "F-3002");
    }

    @Test
    void confirmationRequestCarriesTheTextToApproveInTheUrl() throws Exception {
        StartedConfirmation started = startConfirmation(alex);
        MultiValueMap<String, String> query = query(started);

        assertThat(started.location()).startsWith(itsme.baseUrl() + "/v2/authorization?");
        assertThat(decode(query.getFirst("scope")).split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_CONFIRM");
        assertThat(decode(query.getFirst("redirect_uri"))).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(decode(query.getFirst("acr_values"))).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(decode(query.getFirst("claims"))).contains("Confirm order O-2001", "EUR 149.00");
    }

    @Test
    void confirmationRequestAsksForTheWysiwysTemplateOnlyNoIdentityClaim() throws Exception {
        JsonNode claims = json.readTree(decode(query(startConfirmation(alex)).getFirst("claims")));
        JsonNode idTokenClaims = claims.get("id_token");

        assertThat(claims.propertyNames()).containsExactlyInAnyOrder("id_token", "userinfo");
        assertThat(claims.get("userinfo").propertyNames()).containsExactly("sub");    // a hint, see the next test
        assertThat(idTokenClaims.propertyNames()).containsExactlyInAnyOrder(
                ItsmeClaims.APPROVAL_TEMPLATE_NAME, ItsmeClaims.APPROVAL_TEXT);
        assertThat(json.convertValue(idTokenClaims.get(ItsmeClaims.APPROVAL_TEMPLATE_NAME), Map.class))
                .isEqualTo(Map.of("essential", true, "value", "free_text"));
        assertThat(json.convertValue(idTokenClaims.get(ItsmeClaims.APPROVAL_TEXT), Map.class)).isEqualTo(Map.of(
                "essential", true, "value",
                "<b>VoltaHome</b><br>Customer number: C-1001<br>Confirm order O-2001: Smart thermostat<br>"
                        + "Total: <b>EUR 149.00</b>"));
    }

    @Test
    void confirmationRequestNamesTheItsmeAccountLinkedToTheCustomer() throws Exception {
        // itsme skips its identification page for this account; it only reads the sub under "userinfo"
        JsonNode claims = json.readTree(decode(query(startConfirmation(alex)).getFirst("claims")));

        assertThat(json.convertValue(claims.get("userinfo"), Map.class))
                .isEqualTo(Map.of("sub", Map.of("value", "stub-sub-alex")));
    }

    @Test
    void tokenRequestUsesTheConfirmationRedirectUriClientSecretPostAndPkce() throws Exception {
        confirm(alex, t -> t).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

        List<LoggedRequest> requests = itsme.findAll(postRequestedFor(urlEqualTo("/v2/token")));
        Map<String, String> form = formParams(requests.get(requests.size() - 1).getBodyAsString());
        assertThat(form.get("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(form).containsEntry("client_secret", CLIENT_SECRET)
                .containsKey("code_verifier")
                .doesNotContainKey("client_assertion");
    }

    @Test
    void confirmationCannotBeStartedWithoutToken() throws Exception {
        mvc.perform(post(START)).andExpect(status().isUnauthorized());
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
                    .andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

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
            confirm(alex, t -> t.sub("stub-sub-stranger"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(fraud.raisedSignals()).singleElement().satisfies(s -> {
                assertThat(s.signal()).isEqualTo(FraudSignal.WRONG_ITSME_ACCOUNT);
                assertThat(s.customerId()).isEqualTo("C-1001");
                assertThat(s.itsmeSub()).isEqualTo("stub-sub-stranger");
            });
        }

        @Test
        void itsmeAccountOfAnotherCustomerCannotConfirm() throws Exception {
            givenLinked("C-1002", "stub-sub-sam");

            confirm(alex, t -> t.sub("stub-sub-sam"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void itsmeAccountLinkedWhenTheConfirmationStartedButReplacedSinceCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_link SET itsme_sub = 'stub-sub-alex-new' WHERE customer_id = 'C-1001'").update();
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void refusalInTheItsmeAppChangesNothing() throws Exception {
            StartedConfirmation started = startConfirmation(alex);

            mvc.perform(get(CONFIRMATION_CALLBACK).param("error", "access_denied").param("state", started.state()))
                    .andExpect(redirectedUrl("/confirmation-error?error=access_denied"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void customerNoLongerActiveCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE customer SET active = FALSE WHERE customer_id = 'C-1001'").update();
            try {
                stubTokenEndpoint(idToken(started.nonce()).build());
                confirmationCallback(started.state())
                        .andExpect(redirectedUrl("/confirmation-error?error=no_customer"));
                assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
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
            confirm(alex, t -> t.acr(ItsmeWireMock.ACR_BASIC))
                    .andExpect(redirectedUrl("/confirmation-error?error=invalid_id_token"));
            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
        }

        @Test
        void idTokenIssuedForAnotherRequestIsRejected() throws Exception {
            confirm(alex, t -> t.claim("nonce", "nonce-of-another-request"))
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

    // ------------------------------------------------------------------ the callback: execution, then back to the SPA

    @Nested
    class Callback {

        @Test
        void callbackExecutesTheOperationOnceItsmeConfirmedIt() throws Exception {
            // No call with the token is needed after the approval: the callback itself executes
            confirm(alex, t -> t).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

            assertThat(orderStatus("O-2001")).isEqualTo("CONFIRMED");
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void confirmationUsesNoHandoverCode() throws Exception {
            confirm(alex, t -> t).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_handover").query(Long.class).single()).isZero();
        }

        @Test
        void confirmedAddressTypedByHandExecutesNothing() throws Exception {
            // "/#confirmed=..." is only where the SPA looks: the API answers with the record as it is
            mvc.perform(withToken(get("/api/orders/O-2001"), alex))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OPEN"));
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void longestApprovalTextIsKeptIntact() throws Exception {
            String longest = "\"".repeat(ItsmeFreeText.MAX_LENGTH);
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET approval_text = :text").param("text", longest).update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state()).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

            assertThat(confirmedOperations.forCustomer("C-1001")).singleElement()
                    .satisfies(r -> assertThat(r.approvalText()).isEqualTo(longest));
        }

        @Test
        void callbackRedirectTargetCannotBeChosenByTheRequest() throws Exception {
            // No open redirect: the SPA URLs are configuration, never a request parameter
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code")
                            .param("state", started.state()).param("redirect_uri", "https://attacker.example/"))
                    .andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));
        }
    }

    // ------------------------------------------------------------------ several operations, one callback

    @Nested
    class SeveralOperations {

        @Test
        void offerIsAcceptedThroughTheSameCallback() throws Exception {
            StartedConfirmation started = startConfirmation(alex, ACCEPT_OFFER);
            assertThat(decode(query(started).getFirst("redirect_uri"))).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state()).andExpect(redirectedUrl("/#confirmed=offer&reference=F-3001"));

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
            confirm(alex, t -> t).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

            start(alex, START)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));
        }

        @Test
        void operationInTheCallbackUrlIsIgnored() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            mvc.perform(get(CONFIRMATION_CALLBACK)
                            .param("code", "stub-authorization-code").param("state", started.state())
                            .param("operation_type", "offer").param("operation_reference", "F-3001"))
                    .andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

            assertThat(orderStatus("O-2001")).isEqualTo("CONFIRMED");      // the stored operation
            assertThat(offerStatus("F-3001")).isEqualTo("OPEN");
        }

        @Test
        void storedOperationOfAnotherCustomerIsNotExecuted() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_reference = 'O-2002'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));

            assertThat(orderStatus("O-2002")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void orderConfirmedInTheMeantimeIsNotExecutedTwice() throws Exception {
            // Two confirmations of the same order, started before either is approved: the execution checks it again
            StartedConfirmation first = startConfirmation(alex);
            StartedConfirmation second = startConfirmation(alex);
            stubTokenEndpoint(idToken(first.nonce()).build());
            confirmationCallback(first.state()).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));
            stubTokenEndpoint(idToken(second.nonce()).build());

            confirmationCallback(second.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void unknownOperationTypeIsRefused() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_type = 'refund'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=unknown_operation"));

            assertThat(orderStatus("O-2001")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void confirmedOrderIsReloadedAsStoredNow() throws Exception {
            // What the SPA does when it lands on "/#confirmed=order&reference=O-2001": reload that record
            confirm(alex, t -> t).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

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
            confirmationCallback(started.state()).andExpect(redirectedUrl("/#confirmed=order&reference=O-2001"));

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=confirmation_not_found"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);                  // executed once
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

    private record StartedConfirmation(String location, String state, String nonce) {}

    private static MultiValueMap<String, String> query(StartedConfirmation started) {
        return UriComponentsBuilder.fromUriString(started.location()).build().getQueryParams();
    }

    /** A complete login of the SPA: the token it keeps after the hand-over. */
    private String tokenOf(String sub) throws Exception {
        return tokenAfter(login(t -> t.sub(sub)).andExpect(redirectedToLoginHandover()).andReturn());
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** Step 1: the customer clicks "Confirm order"; the SPA calls the start endpoint with its token. */
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
        return new StartedConfirmation(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")));
    }

    private ResultActions start(String token, String startPath) throws Exception {
        return mvc.perform(withToken(post(startPath), token));
    }

    /** Step 2: itsme redirects the browser back with a code. The navigation carries no credential. */
    private ResultActions confirmationCallback(String state) throws Exception {
        return mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code").param("state", state));
    }

    /** The two steps: the callback executes the operation, or refuses. */
    private ResultActions confirm(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        StartedConfirmation started = startConfirmation(token);
        stubTokenEndpoint(idToken.apply(idToken(started.nonce())).build());
        return confirmationCallback(started.state());
    }

    /** Portal API, the two steps, and the reload of the record, as the SPA makes them. */
    private List<MvcResult> wholeConfirmation() throws Exception {
        MvcResult portal = portal(alex).andExpect(status().isOk()).andReturn();
        MvcResult start = mvc.perform(withToken(post(START), alex)).andExpect(status().isOk()).andReturn();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(json.readTree(
                start.getResponse().getContentAsString()).get("redirect").asString()).build().getQueryParams();
        stubTokenEndpoint(idToken(decode(query.getFirst("nonce"))).build());
        MvcResult callback = confirmationCallback(decode(query.getFirst("state")))
                .andExpect(redirectedUrl("/#confirmed=order&reference=O-2001")).andReturn();
        MvcResult reload = mvc.perform(withToken(get("/api/orders/O-2001"), alex)).andExpect(status().isOk()).andReturn();
        return List.of(portal, start, callback, reload);
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
}
