package com.example.voltahome.itsme;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * README: "Authorization request". Spring generates state, nonce and PKCE; acr_values is added. Everything goes in
 * the URL, where it can be edited, so the response is what protects us (AcrValidator). No claim is requested.
 * Hand-over step 1 (README: "Hand-over"): the SPA starts the login with a form POST carrying its browser code
 * challenge, which is stored with the login's state. A login started otherwise (no challenge, or a GET such as a
 * link) still goes to itsme, but has nothing to hand over: it ends with invalid_request. (The browser code challenge
 * is not PKCE's code_challenge, generated here by Spring for itsme.)
 */
@Component
public class ItsmeAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final ItsmeHandover handover;

    public ItsmeAuthorizationRequestResolver(ClientRegistrationRepository registrations,
                                             ItsmeProperties properties, ItsmeHandover handover) {
        this.handover = handover;
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(params -> params.put("acr_values", properties.acr()))));
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
        String browserCodeChallenge = request.getParameter(ItsmeHandover.BROWSER_CODE_CHALLENGE_PARAMETER);
        if ("POST".equals(request.getMethod()) && ItsmeHandover.isBrowserCodeChallenge(browserCodeChallenge)) {
            handover.begin(original.getState(), browserCodeChallenge);
        }
        return original;
    }
}
