package com.example.voltahome.itsme;

import java.util.Map;

/**
 * Claims requested in the ID token. README: "Authorization request".
 * No identity attribute is requested: the login authenticates, it does not identify, and the customer is found
 * through the itsme "sub" (README: "Authentication, not identification"). Only the ongoing call fraud signal is
 * requested.
 * A new claim needs a consumer and a reason; the tests list the requested claims explicitly.
 */
public final class ItsmeClaims {

    public static final String ONGOING_CALL = "http://itsme.services/v2/claim/ongoing_call";

    public static final Map<String, Object> REQUESTED = Map.of(
            ONGOING_CALL, Map.of("essential", false));

    private ItsmeClaims() {
    }
}
