package com.example.voltahome.itsme;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * README: "Authorization request". Spring generates state, nonce and PKCE.
 * - Key pairs: acr_values and claims go ONLY inside the signed and encrypted Request Object.
 * - Client secret: no Request Object; acr_values and claims go in the URL, where they can be edited,
 *   so the response is what protects us (AcrValidator).
 * Hand-over step 1 (README: "Hand-over"): the SPA starts the login with a form POST carrying its browser code
 * challenge, which is stored with the login's state. A login started otherwise (no challenge, or a GET such as a
 * link) still goes to itsme, but has nothing to hand over: it ends with invalid_request. (The browser code challenge is not PKCE's code_challenge, generated here by Spring for itsme.)
 */
@Component
public class ItsmeAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final ItsmeRequestObjectFactory requestObjects;
    private final boolean useRequestObject;
    private final ItsmeHandover handover;

    public ItsmeAuthorizationRequestResolver(ClientRegistrationRepository registrations,
                                             ItsmeRequestObjectFactory requestObjects,
                                             ItsmeProperties properties, ObjectMapper json, ItsmeHandover handover) {
        this.handover = handover;
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
        String browserCodeChallenge = request.getParameter(ItsmeHandover.BROWSER_CODE_CHALLENGE_PARAMETER);
        if ("POST".equals(request.getMethod()) && ItsmeHandover.isBrowserCodeChallenge(browserCodeChallenge)) {
            handover.begin(original.getState(), ItsmeHandover.Flow.LOGIN, browserCodeChallenge);
        }
        if (!useRequestObject) {
            return original;
        }
        return requestObjects.withRequestObject(original, ItsmeClaims.REQUESTED);
    }
}
