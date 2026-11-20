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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.example.voltahome.fraud.FraudSignal;
import com.example.voltahome.operation.JpaPendingOperationRepository;
import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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
import tools.jackson.databind.JsonNode;

/**
 * README: "Confirmation of an operation", "Several operations, one callback", "Hand-over". Alex (C-1001, itsme account
 * stub-sub-alex) is logged in to the SPA with a demo token and orders the product P-100, in three steps: the SPA starts
 * the confirmation (the data of the order is persisted as a pending operation), itsme calls back (the pending
 * operation is reloaded and the order is created from it), and the SPA
 * redeems a one-time code for its new token. Each test changes one thing in that valid confirmation and states the
 * expected answer. Alex also has an offer F-3001; Sam (C-1002) has an offer F-3002.
 */
class ItsmeConfirmationFlowTest extends ItsmeIntegrationTest {

    private static final String START = "/api/orders/confirm";
    private static final Map<String, Object> ORDER = Map.of("productCode", "P-100");
    private static final String ACCEPT_OFFER = "/api/offers/F-3001/accept";
    private static final Map<String, Object> NOTHING = Map.of();

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
        assertThat(portal).contains("/api/orders/confirm", "P-100", "/api/offers/F-3001/accept")
                .doesNotContain("F-3002");
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
        assertThat(decode(query.getFirst("claims"))).contains("Order: Smart thermostat", "EUR 149.00");
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
                "<b>VoltaHome</b><br>Customer number: C-1001<br>Order: Smart thermostat<br>"
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
        confirm(alex, t -> t).andExpect(redirectedToConfirmationHandover());

        List<LoggedRequest> requests = itsme.findAll(postRequestedFor(urlEqualTo("/v2/token")));
        Map<String, String> form = formParams(requests.get(requests.size() - 1).getBodyAsString());
        assertThat(form.get("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(form).containsEntry("client_secret", CLIENT_SECRET)
                .containsKey("code_verifier")
                .doesNotContainKey("client_assertion");
    }

    @Test
    void confirmationCannotBeStartedWithoutToken() throws Exception {
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(body(ORDER, BROWSER_CODE_CHALLENGE)))
                .andExpect(status().isUnauthorized());
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void confirmationCannotBeStartedWithoutBrowserCodeChallenge() throws Exception {
        // Nothing could be handed over at the end: refused before anything is stored or sent to itsme
        start(alex, START, ORDER, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void browserCodeChallengeIsNotSentToItsme() throws Exception {
        assertThat(startConfirmation(alex).location()).doesNotContain(BROWSER_CODE_CHALLENGE);
    }

    @Test
    void loginEndpointCannotStartAConfirmation() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/authorization/itsme-confirmation")
                .param("browser_code_challenge", BROWSER_CODE_CHALLENGE)).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(pendingConfirmations()).isZero();
    }

    // ------------------------------------------------------------------ the data of the operation

    @Nested
    class OperationData {

        @Test
        void noOrderExistsBeforeTheConfirmation() throws Exception {
            startConfirmation(alex);

            assertThat(orders("C-1001")).isZero();
            assertThat(json.readTree(storedData())).isEqualTo(json.readTree(
                    "{\"productCode\": \"P-100\", \"description\": \"Smart thermostat\", \"amount\": 149.00}"));
        }

        @Test
        void startPersistsThePendingOperationOfTheCustomer() throws Exception {
            startConfirmation(alex);

            assertThat(jdbc.sql("SELECT customer_id || '/' || operation_type FROM pending_operation")
                    .query(String.class).single()).isEqualTo("C-1001/order");
            assertThat(jdbc.sql("SELECT operation_reference FROM itsme_confirmation_request").query(String.class)
                    .single()).isEqualTo(jdbc.sql("SELECT operation_id FROM pending_operation").query(String.class)
                    .single());
        }

        @Test
        void refusedStartPersistsNothing() throws Exception {
            start(alex, START, ORDER, null).andExpect(status().isBadRequest());
            start(alex, START, Map.of("productCode", "P-999"), BROWSER_CODE_CHALLENGE).andExpect(status().isBadRequest());

            assertThat(pendingOperations()).isZero();
        }

        @Test
        void orderIsCreatedFromTheReloadedData() throws Exception {
            String reference = confirmed(alex).get("operationReference").asString();

            assertThat(reference).matches("O-[0-9A-F]{16}");
            mvc.perform(withToken(get("/api/orders/" + reference), alex))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("Smart thermostat"))
                    .andExpect(jsonPath("$.amount").value(149.00))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
            assertThat(pendingOperations()).isZero();                 // executed once: deleted
        }

        @Test
        void refusedConfirmationDoesNotExecuteThePendingOperation() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-stranger"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(orders("C-1001")).isZero();
            assertThat(pendingOperations()).isOne();                  // never executed; purged below
            clock.advance(Duration.ofMinutes(11));
            pendingOperationRepository.purgeExpired();
            assertThat(pendingOperations()).isZero();
        }

        @Test
        void pendingOperationOfAnotherCustomerIsNotExecuted() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE pending_operation SET customer_id = 'C-1002'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));
            assertThat(orders("C-1001")).isZero();
            assertThat(orders("C-1002")).isZero();
        }

        @Test
        void pendingOperationOfAnotherTypeIsNotExecuted() throws Exception {
            // The confirmation says "offer", the pending operation it points to is an order
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_type = 'offer'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));
            assertThat(orders("C-1001")).isZero();
        }

        @Test
        void unknownPendingOperationIsNotExecuted() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET operation_reference = 'unknown'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));
            assertThat(orders("C-1001")).isZero();
        }

        @Test
        void priceChangedAfterTheStartDoesNotChangeTheConfirmedOrder() throws Exception {
            // The customer read EUR 149.00 in the itsme app: that is what is created
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE product SET price = 199.00 WHERE product_code = 'P-100'").update();
            try {
                stubTokenEndpoint(idToken(started.nonce()).build());
                confirmationCallback(started.state()).andExpect(redirectedToConfirmationHandover());

                assertThat(jdbc.sql("SELECT amount FROM customer_order").query(java.math.BigDecimal.class).single())
                        .isEqualByComparingTo("149.00");
            } finally {
                jdbc.sql("UPDATE product SET price = 149.00 WHERE product_code = 'P-100'").update();
            }
        }

        @Test
        void priceAndDescriptionCannotBeChosenByTheRequest() throws Exception {
            Map<String, Object> request = Map.of("productCode", "P-100", "amount", 1, "description", "Free");

            StartedConfirmation started = startConfirmation(alex, START, request);

            assertThat(decode(query(started).getFirst("claims"))).contains("EUR 149.00").doesNotContain("Free");
            assertThat(json.readTree(storedData()).get("amount").decimalValue()).isEqualByComparingTo("149.00");
        }

        @Test
        void unknownProductCannotBeOrdered() throws Exception {
            start(alex, START, Map.of("productCode", "P-999"), BROWSER_CODE_CHALLENGE)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(pendingConfirmations()).isZero();
        }

        @Test
        void persistedDataThatIsNotAnOrderIsNotExecuted() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE pending_operation SET data = 'not json'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));
            assertThat(orders("C-1001")).isZero();
        }

        @Test
        void eachConfirmationCreatesOneOrder() throws Exception {
            confirm(alex, t -> t).andExpect(redirectedToConfirmationHandover());
            confirm(alex, t -> t).andExpect(redirectedToConfirmationHandover());

            assertThat(orders("C-1001")).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ the right person

    @Nested
    class RightPerson {

        @Test
        void linkedItsmeAccountConfirmsTheOrder() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-alex")).andExpect(redirectedToConfirmationHandover());

            assertThat(orders("C-1001")).isOne();
            assertThat(confirmedOperations.forCustomer("C-1001"))
                    .singleElement()
                    .satisfies(executed -> {
                        assertThat(executed.reference()).startsWith("O-");
                        assertThat(executed.evidence().operationType()).isEqualTo("order");
                        assertThat(executed.evidence().approvalText()).contains("C-1001", "Smart thermostat", "EUR 149.00");
                        assertThat(executed.evidence().authenticationLevel()).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
                    });
            portal(alex).andExpect(jsonPath("$.confirmed[0].operationType").value("order"));
        }

        @Test
        void unknownItsmeAccountCannotConfirmAndIsReported() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-stranger"))
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));

            assertThat(orders("C-1001")).isZero();
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

            assertThat(orders("C-1001")).isZero();
            assertThat(orders("C-1002")).isZero();
        }

        @Test
        void itsmeAccountLinkedWhenTheConfirmationStartedButReplacedSinceCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_link SET itsme_sub = 'stub-sub-alex-new' WHERE customer_id = 'C-1001'").update();
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=wrong_person"));
            assertThat(orders("C-1001")).isZero();
        }

        @Test
        void refusalInTheItsmeAppChangesNothing() throws Exception {
            StartedConfirmation started = startConfirmation(alex);

            mvc.perform(get(CONFIRMATION_CALLBACK).param("error", "access_denied").param("state", started.state()))
                    .andExpect(redirectedUrl("/confirmation-error?error=access_denied"));

            assertThat(orders("C-1001")).isZero();
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
                assertThat(orders("C-1001")).isZero();
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
            assertThat(orders("C-1001")).isZero();
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

    // ------------------------------------------------------------------ hand-over: the new token

    @Nested
    class Handover {

        @Test
        void callbackExecutesTheOperationWhetherTheCodeIsRedeemedOrNot() throws Exception {
            // The customer confirmed in itsme: nothing more is asked of the browser
            confirm(alex, t -> t).andExpect(redirectedToConfirmationHandover());

            assertThat(orders("C-1001")).isOne();
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void callbackRedirectsToTheSpaWithOnlyAOneTimeCodeInTheFragment() throws Exception {
            String location = confirm(alex, t -> t).andReturn().getResponse().getRedirectedUrl();

            assertThat(location).matches("/#confirmation=[A-Za-z0-9_-]{43}").doesNotContain("O-", "C-1001");
        }

        @Test
        void redemptionNeedsNoTokenAndReturnsANewOneWithTheReferenceOfTheOrder() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            String answer = redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.customerId").value("C-1001"))
                    .andExpect(jsonPath("$.operationType").value("order"))
                    .andReturn().getResponse().getContentAsString();

            String reference = json.readTree(answer).get("operationReference").asString();
            assertThat(reference).isEqualTo(confirmedOperations.forCustomer("C-1001").get(0).reference());
            portal(json.readTree(answer).get("accessToken").asString())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value("C-1001"));
        }

        @Test
        void newTokenOutlivesTheTokenOfTheLogin() throws Exception {
            clock.advance(Duration.ofMinutes(10));
            String newToken = confirmed(alex).get("accessToken").asString();

            clock.advance(Duration.ofMinutes(10));

            portal(alex).andExpect(status().isUnauthorized());        // 20 minutes after the login
            portal(newToken).andExpect(status().isOk());              // 10 minutes after the confirmation
        }

        @Test
        void redemptionAnswerIsNotCached() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                    .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                            .contains("no-store"));
        }

        @Test
        void redemptionWithTheVerifierOfAnotherBrowserIsRefusedAndConsumesTheCode() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            redeemConfirmation(code, ANOTHER_BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest());
            assertThat(orders("C-1001")).isOne();                    // executed at the callback: not undone
        }

        @Test
        void browserCodeChallengeAloneCannotRedeemTheCode() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            redeemConfirmation(code, BROWSER_CODE_CHALLENGE)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationCodeWithoutBrowserCodeVerifierIsRefused() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            mvc.perform(post(CONFIRMATION_HANDOVER).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("handover", code))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationCodeCannotBeRedeemedTwice() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());
            redeemConfirmation(code, BROWSER_CODE_VERIFIER).andExpect(status().isOk());

            redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationCodeExpiresAfterSixtySecondsAndIsPurged() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            clock.advance(Duration.ofSeconds(61));

            redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
            handover.purgeExpired();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM itsme_handover").query(Long.class).single()).isZero();
            assertThat(orders("C-1001")).isOne();
        }

        @Test
        void loginCodeCannotBeRedeemedAsAConfirmation() throws Exception {
            String loginCode = handoverCode(login(t -> t.sub("stub-sub-alex")).andReturn());

            redeemConfirmation(loginCode, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void confirmationCodeCannotBeRedeemedAsALogin() throws Exception {
            String code = handoverCode(confirm(alex, t -> t).andReturn());

            redeemLogin(code, BROWSER_CODE_VERIFIER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_handover"));
        }

        @Test
        void longestApprovalTextIsKeptIntact() throws Exception {
            String longest = "\"".repeat(ItsmeFreeText.MAX_LENGTH);
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_confirmation_request SET approval_text = :text").param("text", longest).update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state()).andExpect(redirectedToConfirmationHandover());

            assertThat(confirmedOperations.forCustomer("C-1001")).singleElement()
                    .satisfies(executed -> assertThat(executed.evidence().approvalText()).isEqualTo(longest));
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
            StartedConfirmation started = startConfirmation(alex, ACCEPT_OFFER, NOTHING);
            assertThat(decode(query(started).getFirst("redirect_uri"))).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
            assertThat(json.readTree(storedData())).isEqualTo(json.readTree("{\"offerId\": \"F-3001\"}"));
            stubTokenEndpoint(idToken(started.nonce()).build());

            String code = handoverCode(confirmationCallback(started.state())
                    .andExpect(redirectedToConfirmationHandover()).andReturn());

            redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                    .andExpect(jsonPath("$.operationType").value("offer"))
                    .andExpect(jsonPath("$.operationReference").value("F-3001"));
            assertThat(offerStatus("F-3001")).isEqualTo("ACCEPTED");
            assertThat(orders("C-1001")).isZero();
            assertThat(confirmedOperations.forCustomer("C-1001")).singleElement().satisfies(executed -> {
                assertThat(executed.evidence().operationType()).isEqualTo("offer");
                assertThat(executed.evidence().approvalText()).contains("Accept offer F-3001", "EUR 0.2850 per kWh");
            });
        }

        @Test
        void offerOfAnotherCustomerCannotBeSentForConfirmation() throws Exception {
            start(alex, "/api/offers/F-3002/accept", NOTHING, BROWSER_CODE_CHALLENGE)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("operation_not_found"));

            assertThat(pendingConfirmations()).isZero();
        }

        @Test
        void acceptedOfferCannotBeSentForConfirmationAgain() throws Exception {
            acceptOffer().andExpect(redirectedToConfirmationHandover());

            start(alex, ACCEPT_OFFER, NOTHING, BROWSER_CODE_CHALLENGE)
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
                    .andExpect(redirectedToConfirmationHandover());

            assertThat(orders("C-1001")).isOne();                              // the stored operation
            assertThat(offerStatus("F-3001")).isEqualTo("OPEN");
        }

        @Test
        void persistedOfferOfAnotherCustomerIsNotExecuted() throws Exception {
            // Even with tampered data, the business logic only accepts an offer of the customer
            StartedConfirmation started = startConfirmation(alex, ACCEPT_OFFER, NOTHING);
            jdbc.sql("UPDATE pending_operation SET data = '{\"offerId\":\"F-3002\"}'").update();
            stubTokenEndpoint(idToken(started.nonce()).build());

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=operation_not_found"));

            assertThat(offerStatus("F-3002")).isEqualTo("OPEN");
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void offerAcceptedInTheMeantimeIsNotExecutedTwice() throws Exception {
            // Two confirmations of the same offer, started before either is approved: the execution checks it again
            StartedConfirmation first = startConfirmation(alex, ACCEPT_OFFER, NOTHING);
            StartedConfirmation second = startConfirmation(alex, ACCEPT_OFFER, NOTHING);
            stubTokenEndpoint(idToken(first.nonce()).build());
            confirmationCallback(first.state()).andExpect(redirectedToConfirmationHandover());
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

            assertThat(orders("C-1001")).isZero();
            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void orderOfAnotherCustomerCannotBeReloaded() throws Exception {
            String reference = confirmed(alex).get("operationReference").asString();
            givenLinked("C-1002", "stub-sub-sam");
            String sam = tokenOf("stub-sub-sam");

            mvc.perform(withToken(get("/api/orders/" + reference), sam)).andExpect(status().isNotFound());
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
            assertThat(orders("C-1001")).isOne();                                              // executed once
        }

        @Test
        void confirmationExpiresAfterFiveMinutesAndIsPurged() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            clock.advance(JpaConfirmationRequestRepository.TTL.plus(Duration.ofSeconds(1)));

            confirmationCallback(started.state())
                    .andExpect(redirectedUrl("/confirmation-error?error=confirmation_not_found"));
            confirmationRequests.purgeExpired();
            assertThat(pendingConfirmations()).isZero();
            assertThat(orders("C-1001")).isZero();
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

    @Autowired JpaConfirmationRequestRepository confirmationRequests;
    @Autowired ItsmeHandover handover;
    @Autowired JpaPendingOperationRepository pendingOperationRepository;

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

    /** A confirmed operation ends with a redirect to the SPA carrying only a one-time code in the fragment. */
    private static ResultMatcher redirectedToConfirmationHandover() {
        return result -> assertThat(result.getResponse().getRedirectedUrl())
                .matches("/#confirmation=[A-Za-z0-9_-]{43}");
    }

    /** The JSON the SPA posts to a start endpoint: what it asks for, and its browser code challenge. */
    private String body(Map<String, Object> request, String browserCodeChallenge) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        if (browserCodeChallenge != null) {
            body.put("browserCodeChallenge", browserCodeChallenge);
        }
        return json.writeValueAsString(body);
    }

    /** Step 1: the customer clicks "Order"; the SPA calls the start endpoint with its token. */
    private StartedConfirmation startConfirmation(String token) throws Exception {
        return startConfirmation(token, START, ORDER);
    }

    /** Step 1 for the operation behind this start endpoint. */
    private StartedConfirmation startConfirmation(String token, String startPath, Map<String, Object> request)
            throws Exception {
        String answer = start(token, startPath, request, BROWSER_CODE_CHALLENGE)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String location = json.readTree(answer).get("redirect").asString();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new StartedConfirmation(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")));
    }

    private ResultActions start(String token, String startPath, Map<String, Object> request,
                                String browserCodeChallenge) throws Exception {
        return mvc.perform(withToken(post(startPath), token).contentType(MediaType.APPLICATION_JSON)
                .content(body(request, browserCodeChallenge)));
    }

    /** Step 2: itsme redirects the browser back with a code. The navigation carries no credential. */
    private ResultActions confirmationCallback(String state) throws Exception {
        return mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code").param("state", state));
    }

    /** Steps 1 and 2: the callback executes the operation, or refuses. */
    private ResultActions confirm(String token, UnaryOperator<ItsmeWireMock.IdTokenBuilder> idToken) throws Exception {
        StartedConfirmation started = startConfirmation(token);
        stubTokenEndpoint(idToken.apply(idToken(started.nonce())).build());
        return confirmationCallback(started.state());
    }

    /** Steps 1 and 2 for the offer F-3001. */
    private ResultActions acceptOffer() throws Exception {
        StartedConfirmation started = startConfirmation(alex, ACCEPT_OFFER, NOTHING);
        stubTokenEndpoint(idToken(started.nonce()).build());
        return confirmationCallback(started.state());
    }

    /** Step 3: the SPA redeems the one-time code with a browser code verifier. No token is sent. */
    private ResultActions redeemConfirmation(String code, String verifier) throws Exception {
        return mvc.perform(post(CONFIRMATION_HANDOVER).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("handover", code, "browserCodeVerifier", verifier))));
    }

    /** The three steps of a valid order: what the SPA receives at the end. */
    private JsonNode confirmed(String token) throws Exception {
        String code = handoverCode(confirm(token, t -> t).andExpect(redirectedToConfirmationHandover()).andReturn());
        return json.readTree(redeemConfirmation(code, BROWSER_CODE_VERIFIER)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Portal API, the three steps, and the reload of the record, as the SPA makes them. */
    private List<MvcResult> wholeConfirmation() throws Exception {
        MvcResult portal = portal(alex).andExpect(status().isOk()).andReturn();
        MvcResult start = start(alex, START, ORDER, BROWSER_CODE_CHALLENGE).andExpect(status().isOk()).andReturn();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(json.readTree(
                start.getResponse().getContentAsString()).get("redirect").asString()).build().getQueryParams();
        stubTokenEndpoint(idToken(decode(query.getFirst("nonce"))).build());
        MvcResult callback = confirmationCallback(decode(query.getFirst("state")))
                .andExpect(redirectedToConfirmationHandover()).andReturn();
        MvcResult redeem = redeemConfirmation(handoverCode(callback), BROWSER_CODE_VERIFIER)
                .andExpect(status().isOk()).andReturn();
        JsonNode answer = json.readTree(redeem.getResponse().getContentAsString());
        MvcResult reload = mvc.perform(withToken(get("/api/orders/" + answer.get("operationReference").asString()),
                answer.get("accessToken").asString())).andExpect(status().isOk()).andReturn();
        return List.of(portal, start, callback, redeem, reload);
    }

    private long pendingConfirmations() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_confirmation_request").query(Long.class).single();
    }

    /** The data persisted by prepare, as JSON. */
    private String storedData() {
        return jdbc.sql("SELECT data FROM pending_operation").query(String.class).single();
    }

    private long pendingOperations() {
        return jdbc.sql("SELECT COUNT(*) FROM pending_operation").query(Long.class).single();
    }

    private long orders(String customerId) {
        return jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE customer_id = :id AND status = 'CONFIRMED'")
                .param("id", customerId).query(Long.class).single();
    }

    private String offerStatus(String offerId) {
        return jdbc.sql("SELECT status FROM offer WHERE offer_id = :id").param("id", offerId)
                .query(String.class).single();
    }
}
