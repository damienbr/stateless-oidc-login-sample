package com.example.voltahome.itsme;

/**
 * README: "Hand-over". Boundary with the application's token management (out of scope of the sample).
 * Called when the SPA redeems the hand-over code of a successful itsme login (ItsmeLoginHandoverController), on an
 * AJAX call: the implementation issues the application's own token and returns the JSON body of the answer.
 * The token travels in that body only, never in a URL and never in a cookie.
 */
public interface LoginHandover {

    /** The JSON body returned to the SPA: typically the application's token and the customer's own data. */
    Object complete(ItsmeLoginResult result);
}
