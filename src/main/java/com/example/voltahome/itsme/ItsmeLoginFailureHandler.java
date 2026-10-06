package com.example.voltahome.itsme;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * README: "Error codes". Redirects to the login error page with the error code; the frontend shows the message.
 * Error descriptions are logged; they never contain token content.
 */
@Component
public class ItsmeLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(ItsmeLoginFailureHandler.class);

    private final String loginErrorUrl;

    public ItsmeLoginFailureHandler(@Value("${app.login-error-url}") String loginErrorUrl) {
        this.loginErrorUrl = loginErrorUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : "login_failed";
        log.info("itsme login failed: {} ({})", code,
                exception instanceof OAuth2AuthenticationException oauth2 ? oauth2.getError().getDescription() : "-");
        response.sendRedirect(loginErrorUrl + "?error=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }
}
