package com.example.voltahome.itsme;

import java.time.Instant;

/**
 * README: "Hand-over". What is handed over to the application's token management once a confirmed operation has been
 * executed: who confirmed, at which level and when, and the reference of the record the execution created or changed.
 * No itsme claim, and no business data: the SPA reloads the record by its reference.
 */
public record ItsmeConfirmedOperation(String customerId, String operationType, String operationReference,
                                      String authenticationLevel, Instant confirmedAt) {}
