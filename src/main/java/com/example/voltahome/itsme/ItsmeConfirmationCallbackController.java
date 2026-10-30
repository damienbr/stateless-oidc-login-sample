package com.example.voltahome.itsme;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * README: "Confirmation of an operation", "Hand-over". itsme redirects the browser here at the end of a confirmation.
 * Step 2: back to the SPA with a one-time code in the URL fragment, or with an error code. Nothing is executed here.
 * The redirect targets are configuration, never a request parameter (no open redirect).
 */
@RestController
class ItsmeConfirmationCallbackController {

    private final ItsmeConfirmationService confirmation;
    private final String handoverUrl;
    private final String errorUrl;

    ItsmeConfirmationCallbackController(ItsmeConfirmationService confirmation,
                                        @Value("${app.confirmation-handover-url}") String handoverUrl,
                                        @Value("${app.confirmation-error-url}") String errorUrl) {
        this.confirmation = confirmation;
        this.handoverUrl = handoverUrl;
        this.errorUrl = errorUrl;
    }

    @GetMapping("/itsme/confirmation/callback")
    ResponseEntity<Void> callback(HttpServletRequest request) {
        String location;
        try {
            location = handoverUrl + "#confirmation=" + confirmation.complete(request);
        } catch (ItsmeConfirmationException e) {
            location = errorUrl + "?error=" + URLEncoder.encode(e.code(), StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
