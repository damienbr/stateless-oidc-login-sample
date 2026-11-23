package com.example.voltahome.operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.itsme.ItsmeOperation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * README: "Several operations, one callback". All the operations a customer can confirm with itsme, by type.
 * Each operation has its own start endpoint, and all of them end at the single confirmation callback.
 * Two steps:
 * - prepare, at the start: persists the data of the operation (pending_operation) and returns what is sent to itsme;
 * - execute, once itsme has confirmed: reloads that data, then applies the business logic of the operation.
 * The type stored with the confirmation selects the operation, and the stored reference its data. Nothing in the
 * callback URL does.
 */
@Component
public class ConfirmableOperationDispatcher {

    private final Map<String, ConfirmableOperation<?>> byType = new HashMap<>();
    private final JpaPendingOperationRepository pending;
    private final ObjectMapper json;

    public ConfirmableOperationDispatcher(List<ConfirmableOperation<?>> operations,
                                          JpaPendingOperationRepository pending, ObjectMapper json) {
        this.pending = pending;
        this.json = json;
        for (ConfirmableOperation<?> operation : operations) {
            if (byType.putIfAbsent(operation.type(), operation) != null) {
                // Two operations with the same type: a confirmation of one could execute the other
                throw new IllegalStateException("Two confirmable operations have the type " + operation.type());
            }
        }
    }

    /**
     * At the start: persists the operation for this customer and returns what to send to itsme. The reference of
     * the returned operation is the id of the persisted one.
     * @param data the data of the operation, of the type the operation declares; built by the application, never
     *             copied unchecked from the request
     * @throws OperationException unknown_operation, or operation_not_found if the operation cannot be confirmed by
     *                            this customer; nothing was persisted
     */
    public ItsmeOperation prepare(String type, String customerId, Object data) {
        return prepare(operation(type), customerId, data);
    }

    /**
     * After itsme confirmed: reloads the operation persisted when the confirmation started, then applies its
     * business logic. One transaction: the pending operation is deleted only if the operation is executed.
     * @return the reference of the record created or changed
     * @throws OperationException unknown_operation, or operation_not_found if the pending operation does not exist
     *                            for this customer and this type, was already executed, or can no longer be
     *                            executed; nothing was executed
     */
    @Transactional
    public String execute(ItsmeConfirmationResult confirmed) {
        return execute(operation(confirmed.operationType()), confirmed.customerId(), confirmed.operationReference());
    }

    private <D> ItsmeOperation prepare(ConfirmableOperation<D> operation, String customerId, Object data) {
        D typed = operation.dataType().cast(data);
        return operation.approvalText(customerId, typed)
                .map(text -> new ItsmeOperation(operation.type(),
                        pending.save(customerId, operation.type(), json.writeValueAsString(typed)), text))
                .orElseThrow(() -> new OperationException("operation_not_found"));
    }

    private <D> String execute(ConfirmableOperation<D> operation, String customerId, String reference) {
        // 1. Reload, for this customer and this type only; single use
        String data = pending.take(reference, customerId, operation.type())
                .orElseThrow(() -> new OperationException("operation_not_found"));
        D typed;
        try {
            typed = json.readValue(data, operation.dataType());
        } catch (JacksonException e) {
            throw new OperationException("operation_not_found");     // not the data this operation persisted
        }
        // 2. Apply the business logic
        return operation.execute(customerId, typed)
                .orElseThrow(() -> new OperationException("operation_not_found"));
    }

    private ConfirmableOperation<?> operation(String type) {
        ConfirmableOperation<?> operation = byType.get(type);
        if (operation == null) {
            throw new OperationException("unknown_operation");
        }
        return operation;
    }
}
