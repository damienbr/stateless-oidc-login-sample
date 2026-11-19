package com.example.voltahome.fraud;

/** Fraud signals raised by the itsme confirmation. */
public enum FraudSignal {
    /** A confirmation was approved with an itsme account that is not the one linked to the logged-in customer. */
    WRONG_ITSME_ACCOUNT
}
