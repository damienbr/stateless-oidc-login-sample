package com.example.voltahome.demo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.operation.ConfirmableOperation;
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

    private final DemoProductRepository products;
    private final DemoOrderRepository orders;

    DemoOrderConfirmation(DemoProductRepository products, DemoOrderRepository orders) {
        this.products = products;
        this.orders = orders;
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
        return products.findByProductCode(productCode)
                .map(product -> new OrderData(product.productCode(), product.description(), product.price()));
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
        orders.save(new DemoOrder(orderId, customerId, order.description(), order.amount(), "CONFIRMED"));
        return Optional.of(orderId);
    }

    /** The order of this customer as the API returns it: GET /api/orders/{id}. */
    Optional<DemoController.OrderDto> dto(String customerId, String orderId) {
        return find(customerId, orderId)
                .map(o -> new DemoController.OrderDto(o.orderId(), o.description(), o.amount(), o.status()));
    }

    List<DemoProduct> products() {
        return products.findAllByOrderByProductCode();
    }

    /** The order of this customer. Another customer's order is never found. */
    Optional<DemoOrder> find(String customerId, String orderId) {
        return orders.findByOrderIdAndCustomerId(orderId, customerId);
    }
}
