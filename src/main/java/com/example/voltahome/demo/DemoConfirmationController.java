package com.example.voltahome.demo;

import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationService;
import com.example.voltahome.operation.ConfirmableOperations;
import com.example.voltahome.operation.OperationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The SPA's side of the itsme confirmation (README: "Confirmation of an operation", "Several operations,
 * one callback"): one start endpoint per operation. The itsme callback (itsme/ItsmeConfirmationCallbackController)
 * ends them all: the operation is executed there (DemoConfirmedOperationExecutor).
 * Everything is under /api: only a request with a valid demo token reaches it (DemoSecurityConfig), and the customer
 * comes from that token, never from a request parameter. Errors answer 400 with {"error": "<code>"}.
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class DemoConfirmationController {

    private final ItsmeConfirmationService confirmation;
    private final ConfirmableOperations operations;

    DemoConfirmationController(ItsmeConfirmationService confirmation, ConfirmableOperations operations) {
        this.confirmation = confirmation;
        this.operations = operations;
    }

    /** "Confirm order" button. */
    @PostMapping("/orders/{orderId}/confirm")
    ResponseEntity<Map<String, String>> confirmOrder(HttpServletRequest request, @PathVariable String orderId,
                                                     @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOrderConfirmation.TYPE, orderId, token.getSubject());
    }

    /** "Accept offer" button. */
    @PostMapping("/offers/{offerId}/accept")
    ResponseEntity<Map<String, String>> acceptOffer(HttpServletRequest request, @PathVariable String offerId,
                                                    @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOfferAcceptance.TYPE, offerId, token.getSubject());
    }

    /**
     * The type is fixed by the endpoint, the record is read for the token's customer only, and the text is built
     * from that record, on the server.
     */
    private ResponseEntity<Map<String, String>> start(HttpServletRequest request, String type, String reference,
                                                      String customerId) {
        try {
            return ResponseEntity.ok(Map.of("redirect", confirmation.start(request, customerId,
                    operations.prepare(type, customerId, reference)).toString()));
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
