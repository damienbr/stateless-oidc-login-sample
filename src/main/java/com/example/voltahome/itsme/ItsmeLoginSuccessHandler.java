package com.example.voltahome.itsme;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** README: "Hand-over". End of the itsme login: ItsmeOidcUserService only lets linked, active customers through. */
@Component
public class ItsmeLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final LoginHandover handover;
    private final ItsmeProperties properties;
    private final Clock clock;

    public ItsmeLoginSuccessHandler(LoginHandover handover, ItsmeProperties properties, Clock clock) {
        this.handover = handover;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        handover.complete(request, response, new ItsmeLoginResult(
                user.getClaimAsString(ItsmeOidcUserService.CUSTOMER_ID),
                "itsme",
                properties.acr(),        // enforced by AcrValidator during ID token validation
                clock.instant()));
    }
}
