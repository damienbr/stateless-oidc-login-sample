package com.example.voltahome.itsme;

/**
 * README: "Confirmation of an operation". Boundary with the application. Called at the itsme callback, once itsme's
 * answer has passed every check (ItsmeConfirmationService.complete): the customer has approved the operation in the
 * itsme app, with the itsme account linked to them. The implementation reloads the operation it persisted when the
 * confirmation started, executes it now, and keeps the result as evidence of the approval.
 */
public interface ConfirmedOperationExecutor {

    /**
     * Executes the operation (type and reference of the result) for the customer of the result.
     * @return the reference of the record the execution created or changed, for example the id of the new order
     * @throws ItsmeConfirmationException if the operation was not executed; its code goes to the error page
     */
    String execute(ItsmeConfirmationResult confirmed);
}
