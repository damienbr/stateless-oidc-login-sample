package com.example.voltahome.demo;

import java.io.IOException;

import com.example.voltahome.itsme.ItsmeLoginResult;
import com.example.voltahome.itsme.LoginHandover;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Stands in for the application's token management, which is out of scope.
 * Like a real implementation, it issues the application's own credential (here, the demo session cookie) and
 * redirects to the portal. The callback is a browser navigation: the answer is a redirect, and the credential
 * travels in a cookie, never in the URL.
 */
@Component
class DemoLoginHandover implements LoginHandover {

    private final DemoSession session;

    DemoLoginHandover(DemoSession session) {
        this.session = session;
    }

    @Override
    public void complete(HttpServletRequest request, HttpServletResponse response, ItsmeLoginResult result)
            throws IOException {
        response.addHeader(HttpHeaders.SET_COOKIE, session.issue(result).toString());
        response.sendRedirect("/portal");
    }
}
