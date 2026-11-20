package com.example.voltahome.demo;

import com.example.voltahome.itsme.ConfirmedOperationExecutor;
import com.example.voltahome.itsme.ItsmeConfirmationException;
import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.operation.ConfirmableOperations;
import com.example.voltahome.operation.OperationException;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. What the application does once itsme has confirmed an operation (README: "Confirmation of an
 * operation", "Several operations, one callback"): reload the operation persisted when the confirmation started
 * and execute it (ConfirmableOperations.execute), then keep the result. Called at the itsme callback, whatever the operation.
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
    public String execute(ItsmeConfirmationResult confirmed) {
        String reference;
        try {
            reference = operations.execute(confirmed);
        } catch (OperationException e) {
            throw new ItsmeConfirmationException(e.code(), e);       // nothing was executed
        }
        confirmedOperations.record(reference, confirmed);            // stands in for the audit trail
        return reference;
    }
}
