package com.example.voltahome.itsme;

/**
 * README: "Hand-over". Boundary with the application's token management (out of scope of the sample), as LoginHandover
 * is for the login. Called when the SPA redeems the one-time code of a confirmation
 * (ItsmeConfirmationHandoverController); the operation was already executed at the callback (ConfirmedOperationExecutor).
 * The implementation issues a new token for the customer and returns it as the JSON body of that AJAX call.
 * The token travels in that body only: never in a URL, never in a cookie.
 */
public interface ConfirmationHandover {

    /** The JSON body returned to the SPA: typically the application's new token and the reference of the operation. */
    Object complete(ItsmeConfirmedOperation confirmed);
}
