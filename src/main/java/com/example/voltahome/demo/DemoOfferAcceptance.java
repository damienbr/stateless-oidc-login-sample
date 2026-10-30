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

    record OpenOffer(String offerId, String description, BigDecimal pricePerKwh) {}

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
        return find(customerId, offerId).map(offer -> ItsmeFreeText.builder()
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

    List<OpenOffer> openOffers(String customerId) {
        return jdbc.sql("SELECT * FROM offer WHERE customer_id = :customer AND status = 'OPEN' ORDER BY offer_id")
                .param("customer", customerId)
                .query((rs, n) -> new OpenOffer(rs.getString("offer_id"), rs.getString("description"),
                        rs.getBigDecimal("price_per_kwh")))
                .list();
    }

    private Optional<OpenOffer> find(String customerId, String offerId) {
        return jdbc.sql("""
                SELECT * FROM offer
                WHERE offer_id = :id AND customer_id = :customer AND status = 'OPEN'
                """)
                .param("id", offerId)
                .param("customer", customerId)
                .query((rs, n) -> new OpenOffer(rs.getString("offer_id"), rs.getString("description"),
                        rs.getBigDecimal("price_per_kwh")))
                .optional();
    }
}
