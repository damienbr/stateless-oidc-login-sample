package com.example.voltahome.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeConfirmationResult;
import com.example.voltahome.itsme.ItsmeFreeText;
import org.junit.jupiter.api.Test;

/** README: "Several operations, one callback". The registry that picks the operation by its stored type. */
class ConfirmableOperationsTest {

    @Test
    void duplicateOperationTypeFailsAtStartup() {
        assertThatThrownBy(() -> new ConfirmableOperations(List.of(new Recording("order"), new Recording("order"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order");
    }

    @Test
    void confirmationIsExecutedByTheOperationOfItsStoredType() {
        Recording order = new Recording("order");
        Recording offer = new Recording("offer");

        new ConfirmableOperations(List.of(order, offer)).execute(confirmed("offer", "F-3001"));

        assertThat(offer.executed).containsExactly("C-1001/F-3001");
        assertThat(order.executed).isEmpty();
    }

    @Test
    void unknownOperationTypeIsRefusedAtTheStart() {
        assertThatThrownBy(() -> new ConfirmableOperations(List.of(new Recording("order")))
                        .prepare("refund", "C-1001", "O-2001"))
                .isInstanceOfSatisfying(OperationException.class,
                        e -> assertThat(e.code()).isEqualTo("unknown_operation"));
    }

    private static ItsmeConfirmationResult confirmed(String type, String reference) {
        return new ItsmeConfirmationResult("C-1001", type, reference, "text", "acr", Instant.EPOCH);
    }

    /** An operation that only records what it was asked to execute. */
    private static final class Recording implements ConfirmableOperation {

        private final String type;
        private final List<String> executed = new ArrayList<>();

        private Recording(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public Optional<ItsmeFreeText> approvalText(String customerId, String reference) {
            return Optional.of(ItsmeFreeText.builder().text(type + " " + reference).build());
        }

        @Override
        public boolean execute(String customerId, String reference) {
            executed.add(customerId + "/" + reference);
            return true;
        }
    }
}
