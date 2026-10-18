package com.example.voltahome.itsme;

import java.net.URI;
import java.time.Clock;
import java.util.List;
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

/**
 * README: "Confirmation of an operation". A logged-in customer approves one operation in the itsme app.
 * Same protocol as the login (state, nonce, PKCE, Request Object, token exchange, ID token checks: Spring's
 * OidcAuthorizationCodeAuthenticationProvider with our token client and decoder), with three differences:
 * - the Request Object carries the WYSIWYS text of the operation;
 * - the confirmation authenticates nobody: the result is never put in a security context;
 * - itsme returns no proof of what was approved, only an ID token. What makes the approval count is checked here:
 *   the customer who ends the confirmation is the one who started it, and the itsme account that approved it is the
 *   one linked to that customer.
 */
@Component
public class ItsmeConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ItsmeConfirmationService.class);

    private final ItsmeProperties properties;
    private final ClientRegistration registration;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final ItsmeRequestObjectFactory requestObjects;
    private final JdbcConfirmationRequestRepository confirmations;
    private final OidcAuthorizationCodeAuthenticationProvider itsmeAnswer;
    private final ItsmeLinkRepository links;
    private final CustomerRepository customers;
    private final FraudService fraud;
    private final Clock clock;

    public ItsmeConfirmationService(ItsmeProperties properties, ItsmeRequestObjectFactory requestObjects,
            JdbcConfirmationRequestRepository confirmations,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> itsmeTokenResponseClient,
            JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory,
            ItsmeLinkRepository links, CustomerRepository customers, FraudService fraud, Clock clock) {
        this.properties = properties;
        this.registration = ItsmeClientConfig.confirmationRegistration(properties);
        this.resolver = new DefaultOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration), "/portal/confirmation");
        this.resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        this.requestObjects = requestObjects;
        this.confirmations = confirmations;
        // Pass-through user service: the ID token is checked below, and nothing is kept as a principal
        this.itsmeAnswer = new OidcAuthorizationCodeAuthenticationProvider(itsmeTokenResponseClient,
                userRequest -> new DefaultOidcUser(List.of(), userRequest.getIdToken()));
        this.itsmeAnswer.setJwtDecoderFactory(idTokenDecoderFactory);
        this.links = links;
        this.customers = customers;
        this.fraud = fraud;
        this.clock = clock;
    }

    /**
     * Key pairs only. itsme requires a signed Request Object for a confirmation, and without one the text to approve
     * would travel in the URL, where it can be edited, with nothing in the answer to detect it.
     */
    public boolean isAvailable() {
        return properties.usesKeyPairs();
    }

    /** Step 1: the customer clicks "Confirm". Returns the itsme URL to redirect the browser to. */
    public URI start(HttpServletRequest request, String customerId, ItsmeOperation operation) {
        if (!isAvailable()) {
            throw new ItsmeConfirmationException("confirmation_unavailable");
        }
        if (links.findSubByCustomer(customerId).isEmpty()) {
            throw new ItsmeConfirmationException("not_linked");     // nobody could confirm it
        }
        OAuth2AuthorizationRequest authorization = requestObjects.withRequestObject(
                resolver.resolve(request, registration.getRegistrationId()),     // state, nonce, PKCE
                ItsmeClaims.confirmation(operation.text()));
        confirmations.save(new PendingConfirmation(authorization.getState(), customerId, operation,
                authorization.getAttribute(OidcParameterNames.NONCE),
                authorization.getAttribute(PkceParameterNames.CODE_VERIFIER)));
        return URI.create(authorization.getAuthorizationRequestUri());
    }

    /**
     * Step 2: itsme redirects the browser back. Returns the confirmed operation, or throws an
     * ItsmeConfirmationException: the operation must then not be executed.
     * @param customerId the customer logged in on the request that carries the callback
     */
    public ItsmeConfirmationResult complete(HttpServletRequest request, String customerId) {
        PendingConfirmation pending = confirmations.remove(request.getParameter(OAuth2ParameterNames.STATE))
                .orElseThrow(() -> failure("confirmation_not_found", null));

        // 1. Ended by the customer who started it, not in somebody else's session
        if (!pending.customerId().equals(customerId)) {
            throw failure("wrong_customer", null);
        }

        // 2. itsme's answer: error, state, token exchange, ID token (decryption, signature, iss, aud, exp, acr), nonce
        OidcIdToken idToken = itsmeAnswer(request, pending);
        String sub = idToken.getSubject();

        if (Boolean.TRUE.equals(idToken.getClaimAsBoolean(ItsmeClaims.ONGOING_CALL))) {
            fraud.report(FraudSignal.ONGOING_CALL, customerId, sub);
            throw failure("fraud_suspected", null);
        }

        // 3. Approved by the right person: the itsme account linked to this customer now, not another one
        Optional<String> linkedSub = links.findSubByCustomer(customerId);
        if (linkedSub.isEmpty() || !linkedSub.get().equals(sub)) {
            fraud.report(FraudSignal.WRONG_ITSME_ACCOUNT, customerId, sub);
            throw failure("wrong_person", null);
        }
        if (customers.findActiveById(customerId).isEmpty()) {
            throw failure("no_customer", null);
        }

        return new ItsmeConfirmationResult(customerId, pending.operation().reference(),
                pending.operation().text().value(),
                properties.acr(),        // enforced by AcrValidator during ID token validation
                clock.instant());
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
