package com.example.voltahome.itsme;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The "claims" parameter of the authorization request. README: "Authorization request".
 * The login sends none: it authenticates, it does not identify, and the customer is found through the itsme
 * "sub" (README: "Authentication, not identification"). A confirmation sends the text to approve and the itsme
 * account expected to approve it, nothing else.
 * A new claim needs a consumer and a reason; the tests list the requested claims explicitly.
 */
public final class ItsmeClaims {

    private static final String PREFIX = "http://itsme.services/v2/claim/";

    /** WYSIWYS template of a confirmation (README: "Confirmation of an operation"). */
    public static final String APPROVAL_TEMPLATE_NAME = PREFIX + "claim_approval_template_name";
    public static final String APPROVAL_TEXT = PREFIX + "claim_approval_text_key";
    public static final String FREE_TEXT_TEMPLATE = "free_text";

    /**
     * The "claims" of a confirmation (README: "Confirmation of an operation"):
     * - "id_token": the "free_text" template with the text to approve. The itsme documentation allows "id_token" or
     *   "userinfo"; the sample uses "id_token" and never calls UserInfo (to be confirmed in E2E).
     * - "userinfo": the "sub" of the itsme account linked to the logged-in customer. itsme then skips its
     *   identification page (the customer does not scan a QR code again) and sends the approval to that account.
     *   itsme only reads it under "userinfo". It is a hint for itsme, not a check: the ID token "sub" is still
     *   compared with the linked account at the callback.
     * @param linkedSub the itsme account linked to the customer who starts the confirmation
     */
    public static Map<String, Object> confirmation(ItsmeFreeText text, String linkedSub) {
        Map<String, Object> idToken = new LinkedHashMap<>();
        idToken.put(APPROVAL_TEMPLATE_NAME, Map.of("essential", true, "value", FREE_TEXT_TEMPLATE));
        idToken.put(APPROVAL_TEXT, Map.of("essential", true, "value", text.value()));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("id_token", idToken);
        claims.put("userinfo", Map.of("sub", Map.of("value", linkedSub)));
        return claims;
    }

    private ItsmeClaims() {
    }
}
