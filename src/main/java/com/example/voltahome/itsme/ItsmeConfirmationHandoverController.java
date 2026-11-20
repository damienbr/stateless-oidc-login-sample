package com.example.voltahome.itsme;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * README: "Hand-over". Step 3 of a confirmation: the SPA posts the one-time code from the URL fragment and its browser
 * code verifier, on an AJAX call, and receives what ConfirmationHandover returns (the application's new token).
 * Nothing is executed here: the operation was executed at the callback. A refused or missing redemption does not undo
 * it.
 */
@RestController
class ItsmeConfirmationHandoverController {

    private static final Logger log = LoggerFactory.getLogger(ItsmeConfirmationHandoverController.class);

    record HandoverRequest(String handover, String browserCodeVerifier) {}

    private final ItsmeHandover handover;
    private final ConfirmationHandover confirmationHandover;

    ItsmeConfirmationHandoverController(ItsmeHandover handover, ConfirmationHandover confirmationHandover) {
        this.handover = handover;
        this.confirmationHandover = confirmationHandover;
    }

    @PostMapping(value = "/itsme/confirmation/handover", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> redeem(@RequestBody HandoverRequest body) {
        return handover.redeem(ItsmeHandover.Flow.CONFIRMATION, body.handover(), body.browserCodeVerifier(),
                        ItsmeConfirmedOperation.class)
                .<ResponseEntity<Object>>map(confirmed -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())        // the body carries the application's token
                        .body(confirmationHandover.complete(confirmed)))
                .orElseGet(() -> {
                    // Unknown, expired, already used, or another browser's browser code verifier: one answer for all
                    log.info("itsme confirmation hand-over refused");
                    return ResponseEntity.badRequest().body(Map.of("error", "invalid_handover"));
                });
    }
}
