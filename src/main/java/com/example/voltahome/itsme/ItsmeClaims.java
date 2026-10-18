package com.example.voltahome.itsme;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claims requested in the ID token. README: "Authorization request".
 * No identity attribute is requested: the login authenticates, it does not identify, and the customer is found
 * through the itsme "sub" (README: "Authentication, not identification"). Only the ongoing call fraud signal is
 * requested; a confirmation adds the text to approve, nothing else.
 * A new claim needs a consumer and a reason; the tests list the requested claims explicitly.
 */
public final class ItsmeClaims {

    private static final String PREFIX = "http://itsme.services/v2/claim/";

    public static final String ONGOING_CALL = PREFIX + "ongoing_call";

    /** WYSIWYS template of a confirmation (README: "Confirmation of an operation"). */
    public static final String APPROVAL_TEMPLATE_NAME = PREFIX + "claim_approval_template_name";
    public static final String APPROVAL_TEXT = PREFIX + "claim_approval_text_key";
    public static final String FREE_TEXT_TEMPLATE = "free_text";

    public static final Map<String, Object> REQUESTED = Map.of(
            ONGOING_CALL, Map.of("essential", false));

    /**
     * A confirmation: the "free_text" template with the text to approve, plus the same fraud signal as the login.
     * The itsme documentation does not say whether the template belongs to the "id_token" or the "userinfo" member;
     * the sample uses "id_token", like the login, and never calls UserInfo (to be confirmed in E2E).
     */
    public static Map<String, Object> confirmation(ItsmeFreeText text) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(APPROVAL_TEMPLATE_NAME, Map.of("essential", true, "value", FREE_TEXT_TEMPLATE));
        claims.put(APPROVAL_TEXT, Map.of("essential", true, "value", text.value()));
        claims.putAll(REQUESTED);
        return claims;
    }

    private ItsmeClaims() {
    }
}
