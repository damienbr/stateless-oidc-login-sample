package com.example.voltahome.demo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.operation.ConfirmableOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Placing an order (README: "Several operations, one callback"). The order does not exist before the
 * confirmation: the customer picks a product, the product and its price at that moment are persisted as the data of the
 * pending operation (OrderData), and the order is created in customer_order from that data once itsme has confirmed.
 * What the customer read in the itsme app is therefore exactly what is created, even if the catalogue changed in
 * between.
 */
@Component
class DemoOrderConfirmation implements ConfirmableOperation<DemoOrderConfirmation.OrderData> {

    static final String TYPE = "order";

    /** Everything needed to create the order. Built here from the catalogue, never copied from the request. */
    record OrderData(String productCode, String description, BigDecimal amount) {}

    record Product(String productCode, String description, BigDecimal price) {}

    record Order(String orderId, String description, BigDecimal amount, String status) {}

    private final JdbcClient jdbc;

    DemoOrderConfirmation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<OrderData> dataType() {
        return OrderData.class;
    }

    /** The order the customer is about to confirm: the product as it is in the catalogue now. */
    Optional<OrderData> draft(String productCode) {
        return jdbc.sql("SELECT * FROM product WHERE product_code = :code")
                .param("code", productCode)
                .query((rs, n) -> new OrderData(rs.getString("product_code"), rs.getString("description"),
                        rs.getBigDecimal("price")))
                .optional();
    }

    @Override
    public Optional<ItsmeFreeText> approvalText(String customerId, OrderData order) {
        return Optional.of(ItsmeFreeText.builder()
                .bold("VoltaHome").lineBreak()
                .text("Customer number: " + customerId).lineBreak()
                .text("Order: " + order.description()).lineBreak()
                .text("Total: ").bold("EUR " + order.amount().toPlainString())
                .build());
    }

    /** Creates the order for this customer. Each confirmation creates one order: its state is single use. */
    @Override
    public Optional<String> execute(String customerId, OrderData order) {
        String orderId = "O-" + KeyGenerators.string().generateKey().toUpperCase();      // 16 hex characters
        jdbc.sql("""
                INSERT INTO customer_order (order_id, customer_id, description, amount, status)
                VALUES (:id, :customer, :description, :amount, 'CONFIRMED')
                """)
                .param("id", orderId)
                .param("customer", customerId)
                .param("description", order.description())
                .param("amount", order.amount())
                .update();
        return Optional.of(orderId);
    }

    /** The order of this customer as the API returns it: GET /api/orders/{id}. */
    Optional<DemoController.OrderDto> dto(String customerId, String orderId) {
        return find(customerId, orderId)
                .map(o -> new DemoController.OrderDto(o.orderId(), o.description(), o.amount(), o.status()));
    }

    List<Product> products() {
        return jdbc.sql("SELECT * FROM product ORDER BY product_code")
                .query((rs, n) -> new Product(rs.getString("product_code"), rs.getString("description"),
                        rs.getBigDecimal("price")))
                .list();
    }

    /** The order of this customer. Another customer's order is never found. */
    Optional<Order> find(String customerId, String orderId) {
        return jdbc.sql("SELECT * FROM customer_order WHERE order_id = :id AND customer_id = :customer")
                .param("id", orderId)
                .param("customer", customerId)
                .query((rs, n) -> new Order(rs.getString("order_id"), rs.getString("description"),
                        rs.getBigDecimal("amount"), rs.getString("status")))
                .optional();
    }
}
