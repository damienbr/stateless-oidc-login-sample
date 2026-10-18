package com.example.voltahome.itsme;

/**
 * README: "Confirmation of an operation". An operation the customer is asked to approve in the itsme app.
 * @param reference the application's own identifier of the operation; returned unchanged when it is confirmed
 * @param text      exactly what the itsme app shows to the customer
 */
public record ItsmeOperation(String reference, ItsmeFreeText text) {}
