package com.example.voltahome.demo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationService;
import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.itsme.ItsmeOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The portal side of the itsme confirmation (README: "Confirmation of an operation"): one fixed operation,
 * a button that starts the confirmation, and the callback that executes the operation once itsme confirmed it.
 * Everything is under /portal: only a logged-in customer reaches it (DemoSecurityConfig), and the customer comes from
 * the demo session, never from a request parameter.
 */
@RestController
@RequestMapping("/portal/confirmation")
class DemoConfirmationController {

    static final String OPERATION = "advance-payment-85";
    static final String DESCRIPTION = "Set the monthly advance payment to EUR 85.00";

    /** README: "Confirmation of an operation". Fixed messages only; the raw "error" parameter is never displayed. */
    private static final Map<String, String> MESSAGES = Map.of(
            "access_denied", "The operation was refused in itsme. Nothing was changed.",
            "confirmation_not_found", "This confirmation has expired or was already used. Nothing was changed.",
            "wrong_customer", "This confirmation was not started in your session. Nothing was changed.",
            "wrong_person", "The itsme account used is not the one linked to your VoltaHome account. Nothing was changed.",
            "fraud_suspected", "For your security, this operation has been blocked. Never share your codes over the phone.",
            "not_linked", "No itsme account is linked to your VoltaHome account.",
            "no_customer", "Your VoltaHome account is no longer active.",
            "confirmation_unavailable", "Confirmation with itsme is not available in this configuration.");
    private static final String DEFAULT_MESSAGE = "The operation could not be confirmed. Nothing was changed.";

    private final ItsmeConfirmationService confirmation;
    private final DemoConfirmedOperations confirmedOperations;

    DemoConfirmationController(ItsmeConfirmationService confirmation, DemoConfirmedOperations confirmedOperations) {
        this.confirmation = confirmation;
        this.confirmedOperations = confirmedOperations;
    }

    static String describe(String operationReference) {
        return OPERATION.equals(operationReference) ? DESCRIPTION : operationReference;
    }

    /** The button: a POST with a CSRF token. The operation and its text are decided here, on the server. */
    @PostMapping
    ResponseEntity<Void> start(HttpServletRequest request,
                               @AuthenticationPrincipal DemoSession.LoggedInCustomer customer) {
        try {
            return redirect(confirmation.start(request, customer.customerId(),
                    new ItsmeOperation(OPERATION, approvalText(customer.customerId()))));
        } catch (ItsmeConfirmationException e) {
            return error(e);
        }
    }

    /** itsme redirects the browser here. The operation is executed only if the confirmation is accepted. */
    @GetMapping("/callback")
    ResponseEntity<Void> callback(HttpServletRequest request,
                                  @AuthenticationPrincipal DemoSession.LoggedInCustomer customer) {
        try {
            confirmedOperations.record(confirmation.complete(request, customer.customerId()));
            return redirect(URI.create("/portal"));
        } catch (ItsmeConfirmationException e) {
            return error(e);
        }
    }

    @GetMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    String errorPage(@RequestParam(defaultValue = "") String error) {
        String message = MESSAGES.getOrDefault(error, DEFAULT_MESSAGE);   // fixed text only
        return DemoController.page("<p class='error'>" + message + "</p><p><a href='/portal'>Back to the portal</a></p>");
    }

    /** What the customer reads in the itsme app. */
    private static ItsmeFreeText approvalText(String customerId) {
        return ItsmeFreeText.builder()
                .bold("VoltaHome").lineBreak()
                .text("Customer number: " + customerId).lineBreak()
                .text("New monthly advance payment: ").bold("EUR 85.00").lineBreak()
                .text("From the next advance invoice.")
                .build();
    }

    private static ResponseEntity<Void> error(ItsmeConfirmationException e) {
        return redirect(URI.create("/portal/confirmation/error?error="
                + URLEncoder.encode(e.code(), StandardCharsets.UTF_8)));
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
