package com.example.voltahome.demo;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** DEMO ONLY. In a real application, the frontend renders these pages. */
@RestController
class DemoController {

    /**
     * README: "Error codes". The frontend maps each code to a message written by the team.
     * The raw "error" parameter is never displayed: it comes from the URL and could be manipulated.
     */
    private static final Map<String, String> MESSAGES = Map.of(
            "access_denied", "Login cancelled.",
            "authorization_request_not_found", "Your session has expired. Please try again.",
            "invalid_grant", "Your session has expired. Please try again.",
            "not_linked", "This itsme account is not linked to a VoltaHome account.",
            "no_customer", "This itsme account is linked to a VoltaHome account that is no longer active.",
            "fraud_suspected", "For your security, this login has been blocked. Never share your codes over the phone.");
    private static final String DEFAULT_MESSAGE = "An error occurred. Please try again.";

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    String home() {
        return page("""
                <p>Customer portal of a fictitious energy supplier.</p>
                <p><a class="button" href="/oauth2/authorization/itsme">Log in with itsme</a></p>
                <p><small>For customers whose itsme account is already linked to their VoltaHome account.</small></p>
                """);
    }

    @GetMapping(value = "/login-error", produces = MediaType.TEXT_HTML_VALUE)
    String loginError(@RequestParam(defaultValue = "") String error) {
        String message = MESSAGES.getOrDefault(error, DEFAULT_MESSAGE);   // fixed text only
        return page("<p class='error'>" + message + "</p><p><a href='/'>Back</a></p>");
    }

    private static String page(String content) {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>VoltaHome</title>
                <style>body{font-family:sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem}
                .button{display:inline-block;padding:.7rem 1.2rem;background:#2e7d32;color:#fff;border-radius:6px;text-decoration:none}
                .error{color:#a00}</style></head><body><h1>VoltaHome</h1>
                """ + content + "</body></html>";
    }
}
