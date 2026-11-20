package com.example.voltahome.demo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.operation.ConfirmableOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Accepting an open offer (README: "Several operations, one callback"). Unlike an order, the offer exists
 * before the confirmation, in the offer table: the data of the confirmation is only its id (OfferData). Every query is
 * limited to the customer, so another customer's offer is never found.
 */
@Component
class DemoOfferAcceptance implements ConfirmableOperation<DemoOfferAcceptance.OfferData> {

    static final String TYPE = "offer";

    /** What is needed to accept the offer: the offer is read again, for the customer, at the execution. */
    record OfferData(String offerId) {}

    record Offer(String offerId, String description, BigDecimal pricePerKwh, String status) {

        boolean isOpen() {
            return "OPEN".equals(status);
        }
    }

    private final JdbcClient jdbc;

    DemoOfferAcceptance(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<OfferData> dataType() {
        return OfferData.class;
    }

    @Override
    public Optional<ItsmeFreeText> approvalText(String customerId, OfferData data) {
        return find(customerId, data.offerId()).filter(Offer::isOpen).map(offer -> ItsmeFreeText.builder()
                .bold("VoltaHome").lineBreak()
                .text("Customer number: " + customerId).lineBreak()
                .text("Accept offer " + offer.offerId() + ": " + offer.description()).lineBreak()
                .text("Price: ").bold("EUR " + offer.pricePerKwh().toPlainString() + " per kWh")
                .build());
    }

    @Override
    public Optional<String> execute(String customerId, OfferData data) {
        int updated = jdbc.sql("""
                UPDATE offer SET status = 'ACCEPTED'
                WHERE offer_id = :id AND customer_id = :customer AND status = 'OPEN'
                """)
                .param("id", data.offerId())
                .param("customer", customerId)
                .update();
        return updated == 1 ? Optional.of(data.offerId()) : Optional.empty();
    }

    /** The offer of this customer as the API returns it: GET /api/offers/{id}. */
    Optional<DemoController.OfferDto> dto(String customerId, String offerId) {
        return find(customerId, offerId)
                .map(o -> new DemoController.OfferDto(o.offerId(), o.description(), o.pricePerKwh(), o.status()));
    }

    List<Offer> openOffers(String customerId) {
        return jdbc.sql("SELECT * FROM offer WHERE customer_id = :customer AND status = 'OPEN' ORDER BY offer_id")
                .param("customer", customerId)
                .query((rs, n) -> new Offer(rs.getString("offer_id"), rs.getString("description"),
                        rs.getBigDecimal("price_per_kwh"), rs.getString("status")))
                .list();
    }

    /** The offer of this customer, whatever its status. */
    Optional<Offer> find(String customerId, String offerId) {
        return jdbc.sql("SELECT * FROM offer WHERE offer_id = :id AND customer_id = :customer")
                .param("id", offerId)
                .param("customer", customerId)
                .query((rs, n) -> new Offer(rs.getString("offer_id"), rs.getString("description"),
                        rs.getBigDecimal("price_per_kwh"), rs.getString("status")))
                .optional();
    }
}
