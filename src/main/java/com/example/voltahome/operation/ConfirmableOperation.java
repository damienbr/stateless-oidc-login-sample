package com.example.voltahome.operation;

import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;

/**
 * README: "Several operations, one callback". One kind of operation that a customer confirms with itsme, such as
 * placing an order or accepting an offer.
 * The record may not exist before the confirmation: an order is only created once it is confirmed. So an operation
 * works from its data, everything needed to execute it: ConfirmableOperations persists it as JSON when the
 * confirmation starts (schema.sql, pending_operation), reloads it once itsme has confirmed, and gives it back to
 * execute, which only holds the business logic.
 * Both methods take the customer: data that does not apply to that customer must be treated as not found.
 * @param <D> the data of the operation: a record that Jackson can write and read
 */
public interface ConfirmableOperation<D> {

    /** A stable name, stored with the confirmation in progress. Unique among all operations. */
    String type();

    /** The class of the data, to read it back from the stored JSON. */
    Class<D> dataType();

    /**
     * Before the confirmation: what the customer reads in the itsme app, built from the data.
     * Empty if the operation cannot be confirmed by this customer (for example, an offer that is not theirs or is no
     * longer open).
     */
    Optional<ItsmeFreeText> approvalText(String customerId, D data);

    /**
     * After itsme confirmed: the business logic of the operation, applied to the reloaded data.
     * @return the reference of the record created or changed (the id of the new order, of the accepted offer);
     *         empty if the operation can no longer be executed for this customer: nothing was executed
     */
    Optional<String> execute(String customerId, D data);
}
