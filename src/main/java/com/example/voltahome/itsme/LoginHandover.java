package com.example.voltahome.itsme;

/**
 * README: "Hand-over". Boundary with the application's token management (out of scope of the sample).
 * Called when the SPA redeems the one-time code of a successful login (ItsmeLoginHandoverController): the
 * implementation issues the application's own token and returns it as the JSON body of that AJAX call.
 * The token travels in that body only: never in a URL, never in a cookie.
 */
public interface LoginHandover {

    /** The JSON body returned to the SPA: typically the application's token and the customer's own data. */
    Object complete(ItsmeLoginResult result);
}
