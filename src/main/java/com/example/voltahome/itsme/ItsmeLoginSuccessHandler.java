package com.example.voltahome.itsme;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * README: "Hand-over". End of the itsme login: ItsmeOidcUserService only lets linked, active customers through.
 * The callback is a browser navigation: the result is stored behind a one-time code, and the browser goes back to
 * the SPA with that code in the URL fragment (never sent to a server, never in a Referer header).
 */
@Component
public class ItsmeLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JdbcLoginHandoverRepository handovers;
    private final ItsmeProperties properties;
    private final Clock clock;
    private final String handoverUrl;

    public ItsmeLoginSuccessHandler(JdbcLoginHandoverRepository handovers, ItsmeProperties properties, Clock clock,
                                    @Value("${app.login-handover-url}") String handoverUrl) {
        this.handovers = handovers;
        this.properties = properties;
        this.clock = clock;
        this.handoverUrl = handoverUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        ItsmeLoginAuthentication login = (ItsmeLoginAuthentication) authentication;
        OidcUser user = (OidcUser) login.getPrincipal();
        String code = handovers.save(login.handoverVerifier(), new ItsmeLoginResult(
                user.getClaimAsString(ItsmeOidcUserService.CUSTOMER_ID),
                "itsme",
                properties.acr(),        // enforced by AcrValidator during ID token validation
                clock.instant()));
        response.sendRedirect(handoverUrl + "#handover=" + code);
    }
}
