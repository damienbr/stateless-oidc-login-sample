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
 * README: "Confirmation of an operation". itsme redirects the browser here at the end of a confirmation. Once itsme's
 * answer has passed every check, the operation is executed (ItsmeConfirmationService.complete). The browser is then
 * sent back to the SPA: to the confirmed route, with the type and reference of the operation in the URL fragment so
 * that the SPA can reload that record with its token, or to the error route with an error code.
 * The fragment is a hint for the SPA, never a proof: anybody can type that address. The redirect targets are
 * configuration, never a request parameter (no open redirect).
 */
@RestController
class ItsmeConfirmationCallbackController {

    private final ItsmeConfirmationService confirmation;
    private final String confirmedUrl;
    private final String errorUrl;

    ItsmeConfirmationCallbackController(ItsmeConfirmationService confirmation,
                                        @Value("${app.confirmation-confirmed-url}") String confirmedUrl,
                                        @Value("${app.confirmation-error-url}") String errorUrl) {
        this.confirmation = confirmation;
        this.confirmedUrl = confirmedUrl;
        this.errorUrl = errorUrl;
    }

    @GetMapping("/itsme/confirmation/callback")
    ResponseEntity<Void> callback(HttpServletRequest request) {
        String location;
        try {
            ItsmeConfirmationResult confirmed = confirmation.complete(request);
            location = confirmedUrl + "#confirmed=" + encode(confirmed.operationType())
                    + "&reference=" + encode(confirmed.operationReference());
        } catch (ItsmeConfirmationException e) {
            location = errorUrl + "?error=" + encode(e.code());
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
