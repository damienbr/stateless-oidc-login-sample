package com.example.voltahome.itsme;

import java.time.Instant;

/**
 * README: "Confirmation of an operation". A confirmed operation, as stored when it was started: nothing in it comes
 * from the callback URL, and it carries no itsme claim. itsme returns no proof of what was shown, so this record
 * (who, what, when, at which level) is the application's evidence; keep it in the audit trail of the operation.
 */
public record ItsmeConfirmationResult(String customerId, String operationReference, String approvalText,
                                      String authenticationLevel, Instant confirmedAt) {}
