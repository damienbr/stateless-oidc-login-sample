package com.example.voltahome.itsme;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * README: "Hand-over". The SPA redeems the hand-over code of a successful itsme login with its verifier, on an AJAX
 * call, and receives what LoginHandover returns. A wrong verifier consumes the code: there is no second try.
 */
@RestController
class ItsmeLoginHandoverController {

    private static final Logger log = LoggerFactory.getLogger(ItsmeLoginHandoverController.class);

    record HandoverRequest(String handover, String verifier) {}

    private final JdbcLoginHandoverRepository handovers;
    private final LoginHandover loginHandover;

    ItsmeLoginHandoverController(JdbcLoginHandoverRepository handovers, LoginHandover loginHandover) {
        this.handovers = handovers;
        this.loginHandover = loginHandover;
    }

    @PostMapping(value = "/itsme/login/handover", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> handover(@RequestBody HandoverRequest body) {
        Optional<JdbcLoginHandoverRepository.StoredLogin> stored = handovers.remove(body.handover());
        if (stored.isEmpty() || !ItsmeHandover.matches(stored.get().handoverVerifier(), body.verifier())) {
            // Unknown, expired, already redeemed, or redeemed by another browser: one answer for all
            log.info("itsme login hand-over refused");
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_handover"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())        // the body carries the application's token
                .body(loginHandover.complete(stored.get().result()));
    }
}
