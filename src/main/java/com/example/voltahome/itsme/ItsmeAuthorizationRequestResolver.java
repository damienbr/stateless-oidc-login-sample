package com.example.voltahome.itsme;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * README: "Authorization request". Spring generates state, nonce and PKCE.
 * - Key pairs: acr_values and claims go ONLY inside the signed and encrypted Request Object.
 * - Client secret: no Request Object; acr_values and claims go in the URL, where they can be edited,
 *   so the response is what protects us (AcrValidator).
 * The SPA starts the login with a form POST carrying its hand-over verifier (README: "Hand-over"), kept with the login
 * in progress and never sent to itsme. Without a valid verifier, the login does not start.
 */
@Component
public class ItsmeAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final ItsmeRequestObjectFactory requestObjects;
    private final boolean useRequestObject;

    public ItsmeAuthorizationRequestResolver(ClientRegistrationRepository registrations,
                                             ItsmeRequestObjectFactory requestObjects,
                                             ItsmeProperties properties, ObjectMapper json) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        this.requestObjects = requestObjects;
        this.useRequestObject = properties.usesKeyPairs();
        if (useRequestObject) {
            this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        } else {
            String claims = json.writeValueAsString(Map.of("id_token", ItsmeClaims.REQUESTED));
            this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                    .andThen(builder -> builder.additionalParameters(params -> {
                        params.put("acr_values", properties.acr());
                        params.put("claims", claims);
                    })));
        }
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return finish(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return finish(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest finish(HttpServletRequest request, OAuth2AuthorizationRequest original) {
        if (original == null) {
            return null;
        }
        // A form POST: the verifier is in the body, never in a URL (history, logs, Referer)
        String verifier = request.getParameter(ItsmeHandover.VERIFIER_PARAMETER);
        if (!"POST".equals(request.getMethod()) || !ItsmeHandover.isVerifier(verifier)) {
            // Reported by ItsmeLoginFailureHandler, like any other login error
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
        }
        OAuth2AuthorizationRequest withVerifier = OAuth2AuthorizationRequest.from(original)
                .attributes(a -> a.put(ItsmeHandover.VERIFIER_PARAMETER, verifier))   // stored, not sent to itsme
                .build();
        if (!useRequestObject) {
            return withVerifier;
        }
        return requestObjects.withRequestObject(withVerifier, ItsmeClaims.REQUESTED);
    }
}
