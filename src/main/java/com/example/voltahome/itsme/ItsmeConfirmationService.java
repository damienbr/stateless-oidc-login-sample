package com.example.voltahome.itsme;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.voltahome.customer.CustomerRepository;
import com.example.voltahome.fraud.FraudService;
import com.example.voltahome.fraud.FraudSignal;
import com.example.voltahome.itsme.JdbcConfirmationRequestRepository.PendingConfirmation;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * README: "Confirmation of an operation". A logged-in customer approves one operation in the itsme app.
 * Same protocol as the login (state, nonce, PKCE, acr_values, token exchange, ID token checks: Spring's
 * OidcAuthorizationCodeAuthenticationProvider with our token client and decoder), with three differences:
 * - the "claims" parameter in the URL carries the WYSIWYS template with the text of the operation, and the "sub" of
 *   the itsme account linked to the customer, so that itsme skips its identification page;
 * - the confirmation authenticates nobody: the result is never put in a security context;
 * - itsme returns no proof of what was approved, only an ID token. What makes the approval count is checked here:
 *   the itsme account that approved it is the one linked to the customer who started it.
 * Three steps: start (the SPA's API call, with the customer's token), complete (the itsme callback) and the
 * hand-over (ItsmeConfirmationHandoverController). The callback carries no credential: what it relies on is the
 * single-use state, which only a call with the customer's token can create, and the itsme account that approved.
 * Once every check has passed, the operation is executed there and then: the customer confirmed it in itsme. What
 * is handed over to the SPA afterwards is only a new token and the reference of what was executed.
 */
@Component
public class ItsmeConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ItsmeConfirmationService.class);

    private final ItsmeProperties properties;
    private final ClientRegistration registration;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final ObjectMapper json;
    private final JdbcConfirmationRequestRepository confirmations;
    private final OidcAuthorizationCodeAuthenticationProvider itsmeAnswer;
    private final ItsmeLinkRepository links;
    private final CustomerRepository customers;
    private final FraudService fraud;
    private final ConfirmedOperationExecutor executor;
    private final ItsmeHandover handover;
    private final Clock clock;

    public ItsmeConfirmationService(ItsmeProperties properties, ObjectMapper json,
            JdbcConfirmationRequestRepository confirmations, ConfirmedOperationExecutor executor,
            ItsmeHandover handover,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> itsmeTokenResponseClient,
            JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory,
            ItsmeLinkRepository links, CustomerRepository customers, FraudService fraud, Clock clock) {
        this.properties = properties;
        this.registration = ItsmeClientConfig.confirmationRegistration(properties);
        this.resolver = new DefaultOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration), "/itsme/confirmation");
        this.resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(params -> params.put("acr_values", properties.acr()))));
        this.json = json;
        this.confirmations = confirmations;
        // Pass-through user service: the ID token is checked below, and nothing is kept as a principal
        this.itsmeAnswer = new OidcAuthorizationCodeAuthenticationProvider(itsmeTokenResponseClient,
                userRequest -> new DefaultOidcUser(List.of(), userRequest.getIdToken()));
        this.itsmeAnswer.setJwtDecoderFactory(idTokenDecoderFactory);
        this.links = links;
        this.customers = customers;
        this.fraud = fraud;
        this.executor = executor;
        this.handover = handover;
        this.clock = clock;
    }

    /**
     * Step 1: the customer clicks "Confirm" in the SPA, which calls its API. Returns the itsme URL the SPA then sends
     * the browser to.
     * @param browserCodeChallenge hand-over step 1 (README: "Hand-over"): the S256 hash of the browser code verifier
     *                             the SPA keeps, and sends when it redeems the one-time code
     */
    public URI start(HttpServletRequest request, String customerId, ItsmeOperation operation,
                     String browserCodeChallenge) {
        if (!ItsmeHandover.isBrowserCodeChallenge(browserCodeChallenge)) {
            throw new ItsmeConfirmationException("invalid_request");            // nothing could be handed over
        }
        String linkedSub = links.findSubByCustomer(customerId)
                .orElseThrow(() -> new ItsmeConfirmationException("not_linked"));     // nobody could confirm it
        // state, nonce, PKCE, acr_values; then the text to approve and the itsme account expected to approve it.
        // from() rebuilds the URI with the claims in it.
        OAuth2AuthorizationRequest resolved = resolver.resolve(request, registration.getRegistrationId());
        String claims = json.writeValueAsString(ItsmeClaims.confirmation(operation.text(), linkedSub));
        OAuth2AuthorizationRequest authorization = OAuth2AuthorizationRequest.from(resolved)
                .additionalParameters(params -> params.put("claims", claims))
                .build();
        confirmations.save(new PendingConfirmation(authorization.getState(), customerId, operation,
                authorization.getAttribute(OidcParameterNames.NONCE),
                authorization.getAttribute(PkceParameterNames.CODE_VERIFIER)));
        handover.begin(ItsmeHandover.Flow.CONFIRMATION, authorization.getState(), browserCodeChallenge);
        return URI.create(authorization.getAuthorizationRequestUri());
    }

    /**
     * Step 2: itsme redirects the browser back. Checks itsme's answer, has the operation executed, and returns the
     * one-time code the SPA redeems for its new token (hand-over step 2). Throws an ItsmeConfirmationException if a
     * check fails or the operation cannot be executed: nothing was executed then.
     */
    public String complete(HttpServletRequest request) {
        PendingConfirmation pending = confirmations.remove(request.getParameter(OAuth2ParameterNames.STATE))
                .orElseThrow(() -> failure("confirmation_not_found", null));
        String customerId = pending.customerId();

        // 1. itsme's answer: error, state, token exchange, ID token (decryption, signature, iss, aud, exp, acr), nonce
        OidcIdToken idToken = itsmeAnswer(request, pending);
        String sub = idToken.getSubject();

        // 2. Approved by the right person: the itsme account linked to this customer now, not another one
        Optional<String> linkedSub = links.findSubByCustomer(customerId);
        if (linkedSub.isEmpty() || !linkedSub.get().equals(sub)) {
            fraud.report(FraudSignal.WRONG_ITSME_ACCOUNT, customerId, sub);
            throw failure("wrong_person", null);
        }
        if (customers.findActiveById(customerId).isEmpty()) {
            throw failure("no_customer", null);
        }

        ItsmeConfirmationResult approved = new ItsmeConfirmationResult(customerId,
                pending.operation().type(), pending.operation().reference(),
                pending.operation().text().value(),
                properties.acr(),        // enforced by AcrValidator during ID token validation
                clock.instant());
        // 3. Confirmed in itsme by the right person: execute the operation stored at the start
        String reference = executor.execute(approved);

        // 4. Hand over to the SPA: our own data only, behind a one-time code. The operation is executed whether the
        //    code is redeemed or not. A code is always issued: start stores the browser code challenge, for as long
        //    as the confirmation itself.
        ItsmeConfirmedOperation confirmed = new ItsmeConfirmedOperation(customerId, approved.operationType(),
                reference, approved.authenticationLevel(), approved.confirmedAt());
        return handover.complete(ItsmeHandover.Flow.CONFIRMATION, pending.state(), confirmed)
                .orElseThrow(() -> failure("invalid_handover", null));
    }

    private OidcIdToken itsmeAnswer(HttpServletRequest request, PendingConfirmation pending) {
        // The request as it was sent, rebuilt from the static registration and the stored values
        OAuth2AuthorizationRequest sent = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(pending.state())
                .attributes(a -> {
                    a.put(OAuth2ParameterNames.REGISTRATION_ID, registration.getRegistrationId());
                    a.put(OidcParameterNames.NONCE, pending.nonce());
                    a.put(PkceParameterNames.CODE_VERIFIER, pending.codeVerifier());
                })
                .build();
        String code = request.getParameter(OAuth2ParameterNames.CODE);
        String error = request.getParameter(OAuth2ParameterNames.ERROR);
        if (error == null && (code == null || code.isBlank())) {
            throw failure("invalid_request", null);
        }
        OAuth2AuthorizationResponse received = (error != null
                ? OAuth2AuthorizationResponse.error(error)
                        .errorDescription(request.getParameter(OAuth2ParameterNames.ERROR_DESCRIPTION))
                : OAuth2AuthorizationResponse.success(code))
                .redirectUri(registration.getRedirectUri())
                .state(request.getParameter(OAuth2ParameterNames.STATE))
                .build();
        try {
            OAuth2LoginAuthenticationToken checked = (OAuth2LoginAuthenticationToken) itsmeAnswer.authenticate(
                    new OAuth2LoginAuthenticationToken(registration, new OAuth2AuthorizationExchange(sent, received)));
            return ((OidcUser) checked.getPrincipal()).getIdToken();
        } catch (OAuth2AuthenticationException e) {
            throw failure(e.getError().getErrorCode(), e);
        }
    }

    /** Error descriptions are logged; they never contain token content. */
    private static ItsmeConfirmationException failure(String code, OAuth2AuthenticationException cause) {
        log.info("itsme confirmation failed: {} ({})", code, cause == null ? "-" : cause.getError().getDescription());
        return new ItsmeConfirmationException(code, cause);
    }
}
