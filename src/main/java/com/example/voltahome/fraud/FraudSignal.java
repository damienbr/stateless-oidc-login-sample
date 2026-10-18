package com.example.voltahome.fraud;

/** Fraud signals raised by the itsme login and the itsme confirmation. */
public enum FraudSignal {
    /** A phone call was in progress while the user approved in the itsme app (phone scam where a fake advisor talks the victim through the approval). */
    ONGOING_CALL,
    /** A confirmation was approved with an itsme account that is not the one linked to the logged-in customer. */
    WRONG_ITSME_ACCOUNT
}
