package com.example.voltahome.demo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.operation.ConfirmableOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Accepting an open offer (README: "Several operations, one callback"). The offer is in the existing offer
 * table; every query is limited to the customer, so another customer's offer is never found.
 */
@Component
class DemoOfferAcceptance implements ConfirmableOperation {

    static final String TYPE = "offer";

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
    public Optional<ItsmeFreeText> approvalText(String customerId, String offerId) {
        return find(customerId, offerId).filter(Offer::isOpen).map(offer -> ItsmeFreeText.builder()
                .bold("VoltaHome").lineBreak()
                .text("Customer number: " + customerId).lineBreak()
                .text("Accept offer " + offer.offerId() + ": " + offer.description()).lineBreak()
                .text("Price: ").bold("EUR " + offer.pricePerKwh().toPlainString() + " per kWh")
                .build());
    }

    @Override
    public boolean execute(String customerId, String offerId) {
        return jdbc.sql("""
                UPDATE offer SET status = 'ACCEPTED'
                WHERE offer_id = :id AND customer_id = :customer AND status = 'OPEN'
                """)
                .param("id", offerId)
                .param("customer", customerId)
                .update() == 1;
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
