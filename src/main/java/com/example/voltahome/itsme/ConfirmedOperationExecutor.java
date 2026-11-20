package com.example.voltahome.itsme;

/**
 * README: "Confirmation of an operation". Boundary with the application, as LoginHandover is for the login.
 * Called at the itsme callback, once itsme's answer has passed every check (ItsmeConfirmationService.complete): the
 * customer has approved the operation in the itsme app, with the itsme account linked to them. The implementation
 * executes the operation now and keeps the result as evidence of the approval.
 */
public interface ConfirmedOperationExecutor {

    /**
     * Executes the operation stored when the confirmation started (type and reference of the result), for the
     * customer of the result.
     * @throws ItsmeConfirmationException if the operation was not executed; its code goes to the error page
     */
    void execute(ItsmeConfirmationResult confirmed);
}
