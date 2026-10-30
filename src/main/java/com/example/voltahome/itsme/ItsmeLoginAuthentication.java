package com.example.voltahome.itsme;

import java.io.Serial;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;

/**
 * README: "Hand-over". The result of a successful itsme login, plus the hand-over verifier of the login that just
 * ended, so that ItsmeLoginSuccessHandler can bind the hand-over code to it. Built by the login filter's result
 * converter (SecurityConfig) from the authorization request that Spring read back from the database.
 */
public class ItsmeLoginAuthentication extends OAuth2AuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String handoverVerifier;

    private ItsmeLoginAuthentication(OAuth2LoginAuthenticationToken login, String handoverVerifier) {
        super(login.getPrincipal(), login.getAuthorities(), login.getClientRegistration().getRegistrationId());
        this.handoverVerifier = handoverVerifier;
    }

    /** Converter for OAuth2LoginAuthenticationFilter.setAuthenticationResultConverter. */
    public static ItsmeLoginAuthentication from(OAuth2LoginAuthenticationToken login) {
        return new ItsmeLoginAuthentication(login, login.getAuthorizationExchange().getAuthorizationRequest()
                .getAttribute(ItsmeHandover.VERIFIER_PARAMETER));
    }

    public String handoverVerifier() {
        return handoverVerifier;
    }
}
