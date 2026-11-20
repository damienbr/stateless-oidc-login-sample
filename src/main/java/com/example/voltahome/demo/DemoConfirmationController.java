package com.example.voltahome.demo;

import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationService;
import com.example.voltahome.itsme.ItsmeHandover;
import com.example.voltahome.operation.ConfirmableOperations;
import com.example.voltahome.operation.OperationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The SPA's side of the itsme confirmation (README: "Confirmation of an operation", "Several operations,
 * one callback"): one start endpoint per operation. The itsme callback (itsme/ItsmeConfirmationCallbackController)
 * ends them all: the operation is executed there (DemoConfirmedOperationExecutor), then the SPA redeems a one-time
 * code for its new token (itsme/ItsmeConfirmationHandoverController, DemoConfirmationHandover).
 * Everything is under /api: only a request with a valid demo token reaches it (DemoSecurityConfig), and the customer
 * comes from that token, never from a request parameter. Errors answer 400 with {"error": "<code>"}.
 */
@RestController
@RequestMapping(value = "/api", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
class DemoConfirmationController {

    /** The SPA says which product; the description and the price come from the catalogue, on the server. */
    record OrderRequest(String productCode, String browserCodeChallenge) {}

    record AcceptRequest(String browserCodeChallenge) {}

    private final ItsmeConfirmationService confirmation;
    private final ConfirmableOperations operations;
    private final DemoOrderConfirmation orders;

    DemoConfirmationController(ItsmeConfirmationService confirmation, ConfirmableOperations operations,
                               DemoOrderConfirmation orders) {
        this.confirmation = confirmation;
        this.operations = operations;
        this.orders = orders;
    }

    /** "Order" button. No order is created here: only its data is prepared, to be stored with the confirmation. */
    @PostMapping("/orders/confirm")
    ResponseEntity<Map<String, String>> confirmOrder(HttpServletRequest request, @RequestBody OrderRequest body,
                                                     @AuthenticationPrincipal Jwt token) {
        return orders.draft(body.productCode())
                .map(order -> start(request, DemoOrderConfirmation.TYPE, order, token.getSubject(),
                        body.browserCodeChallenge()))
                .orElseGet(() -> error("operation_not_found"));
    }

    /** "Accept offer" button. */
    @PostMapping("/offers/{offerId}/accept")
    ResponseEntity<Map<String, String>> acceptOffer(HttpServletRequest request, @PathVariable String offerId,
                                                    @RequestBody AcceptRequest body,
                                                    @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOfferAcceptance.TYPE, new DemoOfferAcceptance.OfferData(offerId),
                token.getSubject(), body.browserCodeChallenge());
    }

    /**
     * The type is fixed by the endpoint, the data is built on the server for the token's customer, and the text is
     * built from that data. prepare persists the data; the callback reloads it and executes it as persisted.
     * A pending operation whose confirmation never ends (refused in itsme, abandoned) is never executed, and purged.
     */
    private ResponseEntity<Map<String, String>> start(HttpServletRequest request, String type, Object data,
                                                      String customerId, String browserCodeChallenge) {
        if (!ItsmeHandover.isBrowserCodeChallenge(browserCodeChallenge)) {
            return error("invalid_request");            // checked first: nothing is persisted for a refused start
        }
        try {
            return ResponseEntity.ok(Map.of("redirect", confirmation.start(request, customerId,
                    operations.prepare(type, customerId, data), browserCodeChallenge).toString()));
        } catch (ItsmeConfirmationException e) {
            return error(e.code());
        } catch (OperationException e) {
            return error(e.code());
        }
    }

    private static ResponseEntity<Map<String, String>> error(String code) {
        return ResponseEntity.badRequest().body(Map.of("error", code));
    }
}
