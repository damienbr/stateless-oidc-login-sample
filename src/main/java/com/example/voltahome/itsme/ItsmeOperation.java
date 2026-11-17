package com.example.voltahome.itsme;

/**
 * README: "Confirmation of an operation". An operation the customer is asked to approve in the itsme app.
 * Type and reference are the application's own values, stored with the confirmation in progress and returned unchanged
 * when it is confirmed: they tell the application which operation to execute (README: "Several operations, one
 * callback"). The itsme client does not interpret them.
 * @param type      the kind of operation, for example "order"
 * @param reference the id of the record to execute, for example the order id
 * @param text      exactly what the itsme app shows to the customer
 */
public record ItsmeOperation(String type, String reference, ItsmeFreeText text) {}
