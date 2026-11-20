package com.example.voltahome.demo;

import com.example.voltahome.itsme.ConfirmedOperationExecutor;
import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.operation.ConfirmableOperations;
import com.example.voltahome.operation.OperationException;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. What the application does once itsme has confirmed an operation (README: "Confirmation of an
 * operation", "Several operations, one callback"): execute the operation stored when the confirmation started, then
 * keep the result. Called at the itsme callback, whatever the operation.
 */
@Component
class DemoConfirmedOperationExecutor implements ConfirmedOperationExecutor {

    private final ConfirmableOperations operations;
    private final DemoConfirmedOperations confirmedOperations;

    DemoConfirmedOperationExecutor(ConfirmableOperations operations, DemoConfirmedOperations confirmedOperations) {
        this.operations = operations;
        this.confirmedOperations = confirmedOperations;
    }

    @Override
    public void execute(ItsmeConfirmationResult confirmed) {
        try {
            operations.execute(confirmed);
        } catch (OperationException e) {
            throw new ItsmeConfirmationException(e.code(), e);       // nothing was executed
        }
        confirmedOperations.record(confirmed);                       // stands in for the audit trail
    }
}
