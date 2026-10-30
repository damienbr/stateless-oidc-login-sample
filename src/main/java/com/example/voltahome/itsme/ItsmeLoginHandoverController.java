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
 * README: "Hand-over". Step 3 of the login: the SPA posts the one-time code from the URL fragment and its verifier, on
 * an AJAX call, and receives what LoginHandover returns (the application's token).
 */
@RestController
class ItsmeLoginHandoverController {

    private static final Logger log = LoggerFactory.getLogger(ItsmeLoginHandoverController.class);

    record HandoverRequest(String handover, String verifier) {}

    private final ItsmeHandover handover;
    private final LoginHandover loginHandover;

    ItsmeLoginHandoverController(ItsmeHandover handover, LoginHandover loginHandover) {
        this.handover = handover;
        this.loginHandover = loginHandover;
    }

    @PostMapping(value = "/itsme/login/handover", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> redeem(@RequestBody HandoverRequest body) {
        return handover.redeem(ItsmeHandover.Flow.LOGIN, body.handover(), body.verifier(), ItsmeLoginResult.class)
                .<ResponseEntity<Object>>map(result -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())        // the body carries the application's token
                        .body(loginHandover.complete(result)))
                .orElseGet(() -> {
                    // Unknown, expired, already used, or another browser's verifier: one answer for all
                    log.info("itsme login hand-over refused");
                    return ResponseEntity.badRequest().body(Map.of("error", "invalid_handover"));
                });
    }
}
