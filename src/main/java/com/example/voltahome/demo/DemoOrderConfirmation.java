package com.example.voltahome.demo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.operation.ConfirmableOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Confirming an open order (README: "Several operations, one callback"). The order is in the existing
 * customer_order table; every query is limited to the customer, so another customer's order is never found.
 */
@Component
class DemoOrderConfirmation implements ConfirmableOperation {

    static final String TYPE = "order";

    record Order(String orderId, String description, BigDecimal amount, String status) {

        boolean isOpen() {
            return "OPEN".equals(status);
        }
    }

    private final JdbcClient jdbc;

    DemoOrderConfirmation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Optional<ItsmeFreeText> approvalText(String customerId, String orderId) {
        return find(customerId, orderId).filter(Order::isOpen).map(order -> ItsmeFreeText.builder()
                .bold("VoltaHome").lineBreak()
                .text("Customer number: " + customerId).lineBreak()
                .text("Confirm order " + order.orderId() + ": " + order.description()).lineBreak()
                .text("Total: ").bold("EUR " + order.amount().toPlainString())
                .build());
    }

    @Override
    public boolean execute(String customerId, String orderId) {
        return jdbc.sql("""
                UPDATE customer_order SET status = 'CONFIRMED'
                WHERE order_id = :id AND customer_id = :customer AND status = 'OPEN'
                """)
                .param("id", orderId)
                .param("customer", customerId)
                .update() == 1;
    }

    /** The same DTO as GET /api/orders/{id}. */
    @Override
    public Optional<DemoController.OrderDto> dto(String customerId, String orderId) {
        return find(customerId, orderId)
                .map(o -> new DemoController.OrderDto(o.orderId(), o.description(), o.amount(), o.status()));
    }

    List<Order> openOrders(String customerId) {
        return jdbc.sql("""
                SELECT * FROM customer_order WHERE customer_id = :customer AND status = 'OPEN' ORDER BY order_id
                """)
                .param("customer", customerId)
                .query((rs, n) -> new Order(rs.getString("order_id"), rs.getString("description"),
                        rs.getBigDecimal("amount"), rs.getString("status")))
                .list();
    }

    /** The order of this customer, whatever its status. */
    Optional<Order> find(String customerId, String orderId) {
        return jdbc.sql("SELECT * FROM customer_order WHERE order_id = :id AND customer_id = :customer")
                .param("id", orderId)
                .param("customer", customerId)
                .query((rs, n) -> new Order(rs.getString("order_id"), rs.getString("description"),
                        rs.getBigDecimal("amount"), rs.getString("status")))
                .optional();
    }
}
