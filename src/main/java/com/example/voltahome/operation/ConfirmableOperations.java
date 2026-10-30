package com.example.voltahome.operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.itsme.ItsmeOperation;
import org.springframework.stereotype.Component;

/**
 * README: "Several operations, one callback". All the operations a customer can confirm with itsme, by type.
 * Each operation has its own start endpoint, and all of them end at the single confirmation callback and the single
 * completion call (README: "Hand-over"), where the type stored with the confirmation selects the operation to execute.
 * Nothing in the callback URL or in the completion call does.
 */
@Component
public class ConfirmableOperations {

    private final Map<String, ConfirmableOperation> byType = new HashMap<>();

    public ConfirmableOperations(List<ConfirmableOperation> operations) {
        for (ConfirmableOperation operation : operations) {
            if (byType.putIfAbsent(operation.type(), operation) != null) {
                // Two operations with the same type: a confirmation of one could execute the other
                throw new IllegalStateException("Two confirmable operations have the type " + operation.type());
            }
        }
    }

    /**
     * At the start: the operation to send to itsme, built from the stored record of this customer.
     * @throws OperationException unknown_operation, or operation_not_found if the record does not exist, is not this
     *                            customer's, or cannot be confirmed
     */
    public ItsmeOperation prepare(String type, String customerId, String reference) {
        return operation(type).approvalText(customerId, reference)
                .map(text -> new ItsmeOperation(type, reference, text))
                .orElseThrow(() -> new OperationException("operation_not_found"));
    }

    /**
     * After itsme confirmed: executes the operation that was stored when the confirmation started.
     * @throws OperationException unknown_operation, or operation_not_found if the record no longer exists for this
     *                            customer or cannot be confirmed any more; nothing was executed
     */
    public void execute(ItsmeConfirmationResult confirmed) {
        if (!operation(confirmed.operationType()).execute(confirmed.customerId(), confirmed.operationReference())) {
            throw new OperationException("operation_not_found");
        }
    }

    private ConfirmableOperation operation(String type) {
        ConfirmableOperation operation = byType.get(type);
        if (operation == null) {
            throw new OperationException("unknown_operation");
        }
        return operation;
    }
}
