package com.example.voltahome.itsme;

import java.util.LinkedHashMap;
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
        return finish(delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return finish(delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest finish(OAuth2AuthorizationRequest original) {
        if (original == null || !useRequestObject) {
            return original;
        }
        Map<String, Object> parameters = new LinkedHashMap<>(original.getAdditionalParameters());
        parameters.put("request", requestObjects.create(original));

        // Rebuilt from scratch so that the authorization URI includes the new parameter
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(original.getAuthorizationUri())
                .clientId(original.getClientId())
                .redirectUri(original.getRedirectUri())
                .scopes(original.getScopes())
                .state(original.getState())
                .additionalParameters(parameters)      // nonce, code_challenge, code_challenge_method, request
                .attributes(original.getAttributes())  // registration_id, nonce, code_verifier (used at callback)
                .build();
    }
}
