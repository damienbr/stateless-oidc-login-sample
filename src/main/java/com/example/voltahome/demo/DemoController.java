package com.example.voltahome.demo;

import java.util.List;
import java.util.Map;

import com.example.voltahome.customer.Customer;
import com.example.voltahome.customer.CustomerRepository;
import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.itsme.ItsmeConfirmationService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

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

    private final CustomerRepository customers;
    private final ItsmeConfirmationService confirmation;
    private final DemoConfirmedOperations confirmedOperations;

    DemoController(CustomerRepository customers, ItsmeConfirmationService confirmation,
                   DemoConfirmedOperations confirmedOperations) {
        this.customers = customers;
        this.confirmation = confirmation;
        this.confirmedOperations = confirmedOperations;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    String home() {
        return page("""
                <p>Customer portal of a fictitious energy supplier.</p>
                <p><a class="button" href="/oauth2/authorization/itsme">Log in with itsme</a></p>
                <p><small>For customers whose itsme account is already linked to their VoltaHome account.</small></p>
                """);
    }

    /** Protected page: DemoSecurityConfig only lets a request with a valid demo session cookie through. */
    @GetMapping(value = "/portal", produces = MediaType.TEXT_HTML_VALUE)
    String portal(@AuthenticationPrincipal DemoSession.LoggedInCustomer customer, CsrfToken csrf) {
        String name = customers.findActiveById(customer.customerId())     // our customer data, not itsme's
                .map(Customer::displayName)
                .orElse(customer.customerId());
        return page("""
                <p>Welcome, %s.</p>
                <table>
                <tr><th>Customer number</th><td>%s</td></tr>
                <tr><th>Logged in with</th><td>itsme</td></tr>
                <tr><th>Authentication level</th><td>%s</td></tr>
                <tr><th>Logged in at</th><td>%s</td></tr>
                <tr><th>Session ends at</th><td>%s</td></tr>
                </table>
                <p><small>This page is protected. It is served only with a valid demo session cookie
                (<code>demo/DemoSession</code>), which stands in for the application's own session or token.</small></p>
                """.formatted(escape(name), escape(customer.customerId()), escape(customer.authenticationLevel()),
                customer.loggedInAt(), customer.expiresAt())
                + confirmationSection(customer.customerId(), csrf));
    }

    /** README: "Confirmation of an operation". The button, and the operations this customer confirmed. */
    private String confirmationSection(String customerId, CsrfToken csrf) {
        StringBuilder html = new StringBuilder("<h2>Monthly advance payment</h2>");
        if (confirmation.isAvailable()) {
            html.append("""
                    <p>%s. You approve this change in the itsme app.</p>
                    <form method="post" action="/portal/confirmation">
                    <input type="hidden" name="%s" value="%s">
                    <button class="button small" type="submit">Confirm with itsme</button></form>
                    """.formatted(DemoConfirmationController.DESCRIPTION, escape(csrf.getParameterName()),
                    escape(csrf.getToken())));
        } else {
            html.append("<p><small>Confirming an operation with itsme needs the key pairs method ")
                    .append("(<code>itsme.client-authentication=private-key-jwt</code>).</small></p>");
        }
        List<ItsmeConfirmationResult> confirmed = confirmedOperations.forCustomer(customerId);
        if (!confirmed.isEmpty()) {
            html.append("<table><tr><th>Confirmed operation</th><th>Confirmed at</th></tr>");
            for (ItsmeConfirmationResult result : confirmed) {
                html.append("<tr><td>").append(escape(DemoConfirmationController.describe(result.operationReference())))
                        .append("</td><td>").append(result.confirmedAt()).append("</td></tr>");
            }
            html.append("</table>");
        }
        return html.toString();
    }

    @GetMapping(value = "/login-error", produces = MediaType.TEXT_HTML_VALUE)
    String loginError(@RequestParam(defaultValue = "") String error) {
        String message = MESSAGES.getOrDefault(error, DEFAULT_MESSAGE);   // fixed text only
        return page("<p class='error'>" + message + "</p><p><a href='/'>Back</a></p>");
    }

    static String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }

    static String page(String content) {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>VoltaHome</title>
                <style>body{font-family:sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem}
                .button{display:inline-block;padding:.7rem 1.2rem;background:#2e7d32;color:#fff;border:0;border-radius:6px;text-decoration:none;cursor:pointer}
                .small{padding:.4rem .8rem;font-size:.9rem}
                .error{color:#a00}th{text-align:left;padding-right:1rem}</style></head><body><h1>VoltaHome</h1>
                """ + content + "</body></html>";
    }
}
