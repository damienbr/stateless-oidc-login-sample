package com.example.voltahome.itsme;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * README: "Hand-over". End of the itsme login: ItsmeOidcUserService only lets linked, active customers through.
 * Hand-over step 2: the result is stored behind a one-time code, and the browser goes back to the SPA with the code in
 * the URL fragment. A fragment is never sent to a server and never appears in a Referer header.
 */
@Component
public class ItsmeLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final ItsmeHandover handover;
    private final ItsmeProperties properties;
    private final Clock clock;
    private final String handoverUrl;
    private final String loginErrorUrl;

    public ItsmeLoginSuccessHandler(ItsmeHandover handover, ItsmeProperties properties, Clock clock,
                                    @Value("${app.login-handover-url}") String handoverUrl,
                                    @Value("${app.login-error-url}") String loginErrorUrl) {
        this.handover = handover;
        this.properties = properties;
        this.clock = clock;
        this.handoverUrl = handoverUrl;
        this.loginErrorUrl = loginErrorUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        ItsmeLoginResult result = new ItsmeLoginResult(
                user.getClaimAsString(ItsmeOidcUserService.CUSTOMER_ID),
                "itsme",
                properties.acr(),        // enforced by AcrValidator during ID token validation
                clock.instant());
        // The state of this callback: Spring has just found the login in progress with it and checked it
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        Optional<String> code = handover.complete(state, ItsmeHandover.Flow.LOGIN, result);
        if (code.isEmpty()) {
            // Not started by the SPA with a browser code challenge: nobody can redeem this login
            response.sendRedirect(loginErrorUrl + "?error=invalid_request");
            return;
        }
        response.sendRedirect(handoverUrl + "#handover=" + code.get());
    }
}
