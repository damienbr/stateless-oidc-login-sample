package com.example.voltahome.demo;

import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.itsme.ItsmeConfirmationService;
import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.itsme.ItsmeOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The SPA's side of the itsme confirmation (README: "Confirmation of an operation", "Hand-over"): one
 * fixed operation, step 1 (start) and step 3 (redeem, then execute). Step 2, the itsme callback, is
 * itsme/ItsmeConfirmationCallbackController.
 * Everything is under /api: only a request with a valid demo token reaches it (DemoSecurityConfig), and the customer
 * comes from that token, never from a request parameter. Errors answer 400 with {"error": "<code>"}.
 */
@RestController
@RequestMapping(value = "/api/confirmation", produces = MediaType.APPLICATION_JSON_VALUE)
class DemoConfirmationController {

    static final String OPERATION = "advance-payment-85";
    static final String DESCRIPTION = "Set the monthly advance payment to EUR 85.00";

    /** The S256 hash of the browser code verifier the SPA keeps until step 3. */
    record StartRequest(String browserCodeChallenge) {}

    /** The one-time code from the URL fragment, and the browser code verifier. */
    record RedeemRequest(String handover, String browserCodeVerifier) {}

    private final ItsmeConfirmationService confirmation;
    private final DemoConfirmedOperations confirmedOperations;

    DemoConfirmationController(ItsmeConfirmationService confirmation, DemoConfirmedOperations confirmedOperations) {
        this.confirmation = confirmation;
        this.confirmedOperations = confirmedOperations;
    }

    static String describe(String operationReference) {
        return OPERATION.equals(operationReference) ? DESCRIPTION : operationReference;
    }

    /** Step 1: the "Confirm with itsme" button. The operation and its text are decided here, on the server. */
    @PostMapping(value = "/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> start(HttpServletRequest request, @RequestBody StartRequest body,
                                              @AuthenticationPrincipal Jwt token) {
        try {
            String customerId = token.getSubject();
            return ResponseEntity.ok(Map.of("redirect", confirmation.start(request, customerId,
                    new ItsmeOperation(OPERATION, approvalText(customerId)), body.browserCodeChallenge()).toString()));
        } catch (ItsmeConfirmationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.code()));
        }
    }

    /** Step 3: the SPA redeems the approval. The operation is executed only if the redemption is accepted. */
    @PostMapping(value = "/redeem", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> redeem(@RequestBody RedeemRequest body, @AuthenticationPrincipal Jwt token) {
        try {
            ItsmeConfirmationResult confirmed = confirmation.redeem(body.handover(), body.browserCodeVerifier(),
                    token.getSubject());
            confirmedOperations.record(confirmed);           // stands in for executing the operation
            return ResponseEntity.ok(Map.of("operation", describe(confirmed.operationReference())));
        } catch (ItsmeConfirmationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.code()));
        }
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
}
