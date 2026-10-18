package com.example.voltahome.itsme;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.voltahome.demo.DemoSession;
import com.example.voltahome.fraud.FraudSignal;
import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * README: "Confirmation of an operation". Alex (C-1001, itsme account stub-sub-alex) is logged in to the portal and
 * confirms one operation. Each test changes one thing in that valid confirmation and states the expected answer.
 */
class ItsmeConfirmationFlowTest extends ItsmeIntegrationTest {

    private static final String CONFIRMED = "advance-payment-85";

    private Cookie alex;

    @BeforeEach
    void alexIsLoggedIn() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        alex = sessionOf("stub-sub-alex");
    }

    // ------------------------------------------------------------------ what we send to itsme

    @Test
    void portalOffersTheConfirmButtonAsACsrfProtectedForm() throws Exception {
        mvc.perform(get("/portal").cookie(alex))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/portal/confirmation\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void confirmationRequestCarriesTheTextToApproveInASignedAndEncryptedRequestObject() throws Exception {
        StartedConfirmation started = startConfirmation(alex);

        assertThat(started.location()).startsWith(itsme.baseUrl() + "/v2/authorization?");
        assertThat(decode(started.location())).doesNotContain("claims=", "acr_values=", "EUR");

        // What itsme does: decrypt with its private key, verify our signature with our public key
        JWTClaimsSet ro = stub.crypto().readRequestObject(started.requestObject(),
                new ImmutableJWKSet<>(keys.publicJwkSet()));
        assertThat(ro.getStringClaim("scope").split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_CONFIRM");
        assertThat(ro.getStringClaim("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(ro.getStringClaim("acr_values")).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
        assertThat(ro.getStringClaim("state")).isEqualTo(started.state());
        assertThat(ro.getStringClaim("nonce")).isEqualTo(started.nonce());
        assertThat(ro.getStringClaim("code_challenge")).isNotBlank();
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
        confirm(alex, t -> t).andExpect(redirectedUrl("/portal"));

        List<LoggedRequest> requests = itsme.findAll(postRequestedFor(urlEqualTo("/v2/token")));
        Map<String, String> form = formParams(requests.get(requests.size() - 1).getBodyAsString());
        assertThat(form.get("redirect_uri")).isEqualTo("http://localhost" + CONFIRMATION_CALLBACK);
        assertThat(form).containsKey("code_verifier").doesNotContainKey("client_secret");
        stub.crypto().verifyClientAssertion(form.get("client_assertion"), new ImmutableJWKSet<>(keys.publicJwkSet()));
    }

    @Test
    void confirmationCannotBeStartedWithoutCsrfToken() throws Exception {
        mvc.perform(post("/portal/confirmation").cookie(alex)).andExpect(status().isForbidden());
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void confirmationCannotBeStartedWithForgedCsrfToken() throws Exception {
        mvc.perform(post("/portal/confirmation").cookie(alex).cookie(new Cookie("XSRF-TOKEN", "forged"))
                        .param("_csrf", "forged"))
                .andExpect(status().isForbidden());
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void confirmationCannotBeStartedWithoutSession() throws Exception {
        CsrfPair csrf = csrfFromPortal(alex);

        mvc.perform(post("/portal/confirmation").cookie(csrf.cookie()).param("_csrf", csrf.token()))   // no session
                .andExpect(redirectedUrl("/"));
        assertThat(pendingConfirmations()).isZero();
    }

    @Test
    void loginEndpointCannotStartAConfirmation() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/itsme-confirmation")).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(pendingConfirmations()).isZero();
    }

    // ------------------------------------------------------------------ the right person

    @Nested
    class RightPerson {

        @Test
        void linkedItsmeAccountConfirmsTheOperation() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-alex")).andExpect(redirectedUrl("/portal"));

            assertThat(confirmedOperations.forCustomer("C-1001"))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.operationReference()).isEqualTo(CONFIRMED);
                        assertThat(r.approvalText()).contains("C-1001", "EUR 85.00");
                        assertThat(r.authenticationLevel()).isEqualTo(ItsmeWireMock.ACR_ADVANCED);
                    });
            mvc.perform(get("/portal").cookie(alex))
                    .andExpect(content().string(containsString("Set the monthly advance payment to EUR 85.00")));
        }

        @Test
        void unknownItsmeAccountCannotConfirmAndIsReported() throws Exception {
            confirm(alex, t -> t.sub("stub-sub-stranger"))
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=wrong_person"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
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
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=wrong_person"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
            assertThat(confirmedOperations.forCustomer("C-1002")).isEmpty();
        }

        @Test
        void itsmeAccountLinkedWhenTheConfirmationStartedButReplacedSinceCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE itsme_link SET itsme_sub = 'stub-sub-alex-new' WHERE customer_id = 'C-1001'").update();
            stubTokenEndpoint(idToken(started.nonce()).sub("stub-sub-alex").build());

            confirmationCallback(alex, started.state())
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=wrong_person"));
        }

        @Test
        void confirmationStartedByAnotherCustomerCannotBeEndedInThisSession() throws Exception {
            givenLinked("C-1002", "stub-sub-sam");
            Cookie sam = sessionOf("stub-sub-sam");
            StartedConfirmation startedByAlex = startConfirmation(alex);
            stubTokenEndpoint(idToken(startedByAlex.nonce()).sub("stub-sub-sam").build());

            confirmationCallback(sam, startedByAlex.state())
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=wrong_customer"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
            assertThat(confirmedOperations.forCustomer("C-1002")).isEmpty();
        }

        @Test
        void callbackWithoutSessionIsRefused() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            mvc.perform(get(CONFIRMATION_CALLBACK).param("code", "stub-authorization-code")
                            .param("state", started.state()))
                    .andExpect(redirectedUrl("/"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void ongoingCallIsRejectedAndReported() throws Exception {
            confirm(alex, t -> t.ongoingCall(true))
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=fraud_suspected"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
            assertThat(fraud.raisedSignals()).singleElement()
                    .satisfies(s -> assertThat(s.signal()).isEqualTo(FraudSignal.ONGOING_CALL));
        }

        @Test
        void refusalInTheItsmeAppChangesNothing() throws Exception {
            StartedConfirmation started = startConfirmation(alex);

            mvc.perform(get(CONFIRMATION_CALLBACK).cookie(alex)
                            .param("error", "access_denied").param("state", started.state()))
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=access_denied"));

            assertThat(confirmedOperations.forCustomer("C-1001")).isEmpty();
        }

        @Test
        void customerNoLongerActiveCannotConfirm() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            jdbc.sql("UPDATE customer SET active = FALSE WHERE customer_id = 'C-1001'").update();
            try {
                stubTokenEndpoint(idToken(started.nonce()).build());
                confirmationCallback(alex, started.state())
                        .andExpect(redirectedUrl("/portal/confirmation/error?error=no_customer"));
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
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=invalid_id_token"));
        }

        @Test
        void idTokenIssuedForAnotherRequestIsRejected() throws Exception {
            confirm(alex, t -> t.claim("nonce", "nonce-of-another-request"))
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=invalid_nonce"));
        }

        @Test
        void encryptedButUnsignedTokenIsRejected() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).buildEncryptedButUnsigned());

            confirmationCallback(alex, started.state())
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=invalid_id_token"));
        }
    }

    // ------------------------------------------------------------------ confirmation in progress

    @Nested
    class ConfirmationInProgress {

        @Test
        void callbackCannotBeReplayed() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());
            confirmationCallback(alex, started.state()).andExpect(redirectedUrl("/portal"));

            confirmationCallback(alex, started.state())
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=confirmation_not_found"));
            assertThat(confirmedOperations.forCustomer("C-1001")).hasSize(1);
        }

        @Test
        void confirmationExpiresAfterFiveMinutesAndIsPurged() throws Exception {
            StartedConfirmation started = startConfirmation(alex);
            stubTokenEndpoint(idToken(started.nonce()).build());

            clock.advance(JdbcConfirmationRequestRepository.TTL.plus(Duration.ofSeconds(1)));

            confirmationCallback(alex, started.state())
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=confirmation_not_found"));
            confirmationRequests.purgeExpired();
            assertThat(pendingConfirmations()).isZero();
        }

        @Test
        void loginStateCannotEndAConfirmation() throws Exception {
            StartedLogin login = startLogin();
            stubTokenEndpoint(idToken(login.nonce()).build());

            confirmationCallback(alex, login.state())
                    .andExpect(redirectedUrl("/portal/confirmation/error?error=confirmation_not_found"));
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
            MvcResult portal = mvc.perform(get("/portal").cookie(alex)).andExpect(status().isOk()).andReturn();
            MvcResult start = mvc.perform(confirmButton(alex)).andReturn();
            StartedConfirmation started = started(start);
            stubTokenEndpoint(idToken(started.nonce()).build());
            MvcResult callback = confirmationCallback(alex, started.state()).andExpect(redirectedUrl("/portal")).andReturn();

            for (MvcResult result : new MvcResult[] {portal, start, callback}) {
                assertThat(result.getRequest().getSession(false)).isNull();
                assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    @Autowired JdbcConfirmationRequestRepository confirmationRequests;

    private record StartedConfirmation(String location, String state, String nonce, String requestObject) {}

    private Cookie sessionOf(String sub) throws Exception {
        return login(t -> t.sub(sub)).andExpect(redirectedUrl("/portal")).andReturn()
                .getResponse().getCookie(DemoSession.COOKIE);
    }

    /**
     * The "Confirm with itsme" form as a browser submits it: the session cookie, the CSRF cookie set by the portal
     * page, and the CSRF token read from that page. (Spring Security's csrf() test helper is not used: it replaces
     * the token repository of the shared filter chain with one that creates an HttpSession.)
     */
    private MockHttpServletRequestBuilder confirmButton(Cookie session) throws Exception {
        CsrfPair csrf = csrfFromPortal(session);
        return post("/portal/confirmation").cookie(session, csrf.cookie()).param("_csrf", csrf.token());
    }

    private record CsrfPair(Cookie cookie, String token) {}

    private CsrfPair csrfFromPortal(Cookie session) throws Exception {
        MvcResult portal = mvc.perform(get("/portal").cookie(session)).andExpect(status().isOk()).andReturn();
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"")
                .matcher(portal.getResponse().getContentAsString());
        assertThat(token.find()).as("CSRF token in the portal page").isTrue();
        Cookie cookie = portal.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("CSRF cookie").isNotNull();
        return new CsrfPair(cookie, token.group(1));
    }

    /** The customer clicks "Confirm with itsme" on the portal. */
    private StartedConfirmation startConfirmation(Cookie session) throws Exception {
        return started(mvc.perform(confirmButton(session))
                .andExpect(status().is3xxRedirection())
                .andReturn());
    }

    private static StartedConfirmation started(MvcResult result) {
        String location = result.getResponse().getRedirectedUrl();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new StartedConfirmation(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")),
                decode(query.getFirst("request")));
    }

    /** itsme redirects the browser back to the portal with a code. */
    private ResultActions confirmationCallback(Cookie session, String state) throws Exception {
        return mvc.perform(get(CONFIRMATION_CALLBACK).cookie(session)
                .param("code", "stub-authorization-code").param("state", state));
    }

    /** A complete confirmation where the test only describes the ID token returned by itsme. */
    private ResultActions confirm(Cookie session, UnaryOperator<ItsmeWireMock.IdTokenBuilder> token) throws Exception {
        StartedConfirmation started = startConfirmation(session);
        stubTokenEndpoint(token.apply(idToken(started.nonce())).build());
        return confirmationCallback(session, started.state());
    }

    private long pendingConfirmations() {
        return jdbc.sql("SELECT COUNT(*) FROM itsme_confirmation_request").query(Long.class).single();
    }
}
