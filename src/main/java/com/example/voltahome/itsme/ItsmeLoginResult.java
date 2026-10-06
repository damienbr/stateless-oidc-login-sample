package com.example.voltahome.itsme;

import java.time.Instant;

/** README: "Hand-over". Data handed over to the application's own token management. No itsme claim. */
public record ItsmeLoginResult(String customerId, String authenticationMethod,
                               String authenticationLevel, Instant authenticationTime) {}
