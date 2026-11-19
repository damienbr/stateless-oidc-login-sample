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
 * DEMO ONLY. The SPA's side of the itsme confirmation (README: "Confirmation of an operation", "Hand-over", "Several
 * operations, one callback"): one start endpoint per operation (step 1), and one redemption for all of them (step 3,
 * then the operation is executed). Step 2, the itsme callback, is itsme/ItsmeConfirmationCallbackController.
 * Everything is under /api: only a request with a valid demo token reaches it (DemoSecurityConfig), and the customer
 * comes from that token, never from a request parameter. Errors answer 400 with {"error": "<code>"}.
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class DemoConfirmationController {

    /** The S256 hash of the browser code verifier the SPA keeps until step 3. */
    record StartRequest(String browserCodeChallenge) {}

    /** The one-time code from the URL fragment, and the browser code verifier. */
    record RedeemRequest(String handover, String browserCodeVerifier) {}

    /**
     * What was executed, and the DTO of the record as stored after the execution: the same DTO as GET
     * /api/orders/{id} or GET /api/offers/{id} (README: "Hand-over"). The DTO is null if the record could not be read
     * any more: the SPA then reloads it by type and reference.
     */
    record Redeemed(String operationType, String operationReference, Object dto) {}

    private final ItsmeConfirmationService confirmation;
    private final ConfirmableOperations operations;
    private final DemoConfirmedOperations confirmedOperations;

    DemoConfirmationController(ItsmeConfirmationService confirmation, ConfirmableOperations operations,
                               DemoConfirmedOperations confirmedOperations) {
        this.confirmation = confirmation;
        this.operations = operations;
        this.confirmedOperations = confirmedOperations;
    }

    /** Step 1, "Confirm order" button. */
    @PostMapping(value = "/orders/{orderId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> confirmOrder(HttpServletRequest request, @PathVariable String orderId,
                                                     @RequestBody StartRequest body, @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOrderConfirmation.TYPE, orderId, body, token.getSubject());
    }

    /** Step 1, "Accept offer" button. */
    @PostMapping(value = "/offers/{offerId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> acceptOffer(HttpServletRequest request, @PathVariable String offerId,
                                                    @RequestBody StartRequest body, @AuthenticationPrincipal Jwt token) {
        return start(request, DemoOfferAcceptance.TYPE, offerId, body, token.getSubject());
    }

    /**
     * Step 3, whatever the operation: the SPA redeems the approval. The operation executed is the one stored when the
     * confirmation started, and only if the approval is redeemed by the customer who started it.
     */
    @PostMapping(value = "/confirmation/redeem", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> redeem(@RequestBody RedeemRequest body, @AuthenticationPrincipal Jwt token) {
        try {
            ItsmeConfirmationResult confirmed = confirmation.redeem(body.handover(), body.browserCodeVerifier(),
                    token.getSubject());
            operations.execute(confirmed);
            confirmedOperations.record(confirmed);           // stands in for the audit trail
            return ResponseEntity.ok(new Redeemed(confirmed.operationType(), confirmed.operationReference(),
                    operations.dto(confirmed).orElse(null)));
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
                    operations.prepare(type, customerId, reference), body.browserCodeChallenge()).toString()));
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
