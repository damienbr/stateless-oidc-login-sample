package com.example.voltahome.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.itsme.ItsmeFreeText;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * README: "Several operations, one callback". The dispatcher that picks the operation by its stored type. What it
 * persists and reloads is tested with the database, in ItsmeConfirmationFlowTest (nested class OperationData).
 */
class ConfirmableOperationDispatcherTest {

    @Test
    void duplicateOperationTypeFailsAtStartup() {
        assertThatThrownBy(() -> dispatcher(new Named("order"), new Named("order")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order");
    }

    @Test
    void unknownOperationTypeIsRefusedAtTheStart() {
        assertThatThrownBy(() -> dispatcher(new Named("order")).prepare("refund", "C-1001", new Named.Data("O-1")))
                .isInstanceOfSatisfying(OperationException.class,
                        e -> assertThat(e.code()).isEqualTo("unknown_operation"));
    }

    @Test
    void unknownOperationTypeIsRefusedAtTheExecution() {
        ItsmeConfirmationResult confirmed =
                new ItsmeConfirmationResult("C-1001", "refund", "a-reference", "text", "acr", Instant.EPOCH);

        assertThatThrownBy(() -> dispatcher(new Named("order")).execute(confirmed))
                .isInstanceOfSatisfying(OperationException.class,
                        e -> assertThat(e.code()).isEqualTo("unknown_operation"));
    }

    /** No repository: these tests stop before anything is persisted or reloaded. */
    private static ConfirmableOperationDispatcher dispatcher(Named... operations) {
        return new ConfirmableOperationDispatcher(List.<ConfirmableOperation<?>>of(operations), null,
                new ObjectMapper());
    }

    /** An operation that only has a type. */
    private record Named(String type) implements ConfirmableOperation<Named.Data> {

        record Data(String id) {}

        @Override
        public Class<Data> dataType() {
            return Data.class;
        }

        @Override
        public Optional<ItsmeFreeText> approvalText(String customerId, Data data) {
            return Optional.of(ItsmeFreeText.builder().text(type + " " + data.id()).build());
        }

        @Override
        public Optional<String> execute(String customerId, Data data) {
            return Optional.of(data.id());
        }
    }
}
