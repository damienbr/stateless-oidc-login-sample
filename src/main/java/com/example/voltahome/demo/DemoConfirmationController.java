package com.example.voltahome.demo;

import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationResult;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The SPA's side of the itsme confirmation (README: "Confirmation of an operation", "Several operations,
 * one callback", "Hand-over"): one start endpoint per operation, which returns the itsme URL, and one completion
 * endpoint for all of them, which executes the operation once the SPA redeems the approval.
 * Everything is under /api: only a request with a valid demo token reaches it (DemoSecurityConfig), and the customer
 * comes from that token, never from a request parameter. Errors answer 400 with {"error": "<code>"}.
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class DemoConfirmationController {

    /** The random value the SPA keeps until the completion; in the body, never in a URL. */
    record StartRequest(String handoverVerifier) {}

    /** The code from the URL fragment, and the verifier. */
    record CompleteRequest(String handover, String verifier) {}

    record Completed(String operationType, String operationReference) {}

    private final ItsmeConfirmationService confirmation;
    private final ConfirmableOperations operations;
    private final DemoConfirmedOperations confirmedOperations;

    DemoConfirmationController(ItsmeConfirmationService confirmation, ConfirmableOperations operations,
                               DemoConfirmedOperations confirmedOperations) {
        this.confirmation = confirmation;
        this.operations = operations;
        this.confirmedOperations = confirmedOperations;
    }

    /** The "Confirm order" button. */
    @PostMapping(value = "/orders/{orderId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> confirmOrder(HttpServletRequest request, @PathVariable String orderId,
                                                     @RequestBody StartRequest body, @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOrderConfirmation.TYPE, orderId, body, token.getSubject());
    }

    /** The "Accept offer" button. */
    @PostMapping(value = "/offers/{offerId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> acceptOffer(HttpServletRequest request, @PathVariable String offerId,
                                                    @RequestBody StartRequest body, @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOfferAcceptance.TYPE, offerId, body, token.getSubject());
    }

    /**
     * The SPA redeems the approval, whatever the operation. The operation to execute is the one stored when the
     * confirmation started; it is executed only if the approval is redeemed by the customer who started it.
     */
    @PostMapping(value = "/confirmation/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> complete(@RequestBody CompleteRequest body, @AuthenticationPrincipal Jwt token) {
        try {
            ItsmeConfirmationResult confirmed = confirmation.redeem(body.handover(), body.verifier(),
                    token.getSubject());
            operations.execute(confirmed);
            confirmedOperations.record(confirmed);
            return ResponseEntity.ok(new Completed(confirmed.operationType(), confirmed.operationReference()));
        } catch (ItsmeConfirmationException e) {
            return error(e.code());
        } catch (OperationException e) {
            return error(e.code());
        }
    }

    /**
     * The type is fixed by the endpoint, the record is read for the token's customer only, and the text is built
     * from that record, on the server.
     */
    private ResponseEntity<Map<String, String>> start(HttpServletRequest request, String type, String reference,
                                                      StartRequest body, String customerId) {
        try {
            return ResponseEntity.ok(Map.of("redirect", confirmation.start(request, customerId,
                    operations.prepare(type, customerId, reference), body.handoverVerifier()).toString()));
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
