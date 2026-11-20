package com.example.voltahome.operation;

import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;

/**
 * README: "Several operations, one callback". One kind of operation that a customer confirms with itsme, such as
 * confirming an order or accepting an offer. The record to confirm already exists in the application's own table;
 * its id is the reference.
 * Both methods take the customer: a record that does not belong to that customer must be treated as not found.
 */
public interface ConfirmableOperation {

    /** A stable name, stored with the confirmation in progress. Unique among all operations. */
    String type();

    /**
     * Before the confirmation: what the customer reads in the itsme app, built from the stored record.
     * Empty if the record does not exist, is not this customer's, or cannot be confirmed any more.
     */
    Optional<ItsmeFreeText> approvalText(String customerId, String reference);

    /**
     * After itsme confirmed: executes the operation on the stored record.
     * @return false if the record does not exist, is not this customer's, or cannot be confirmed any more;
     *         nothing was executed
     */
    boolean execute(String customerId, String reference);
}
