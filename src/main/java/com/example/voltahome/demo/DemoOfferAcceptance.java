package com.example.voltahome.demo;

import java.util.List;
import java.util.Optional;

import com.example.voltahome.itsme.ItsmeFreeText;
import com.example.voltahome.operation.ConfirmableOperation;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Accepting an open offer (README: "Several operations, one callback"). Unlike an order, the offer exists
 * before the confirmation, in the offer table (DemoOffer): the data of the confirmation is only its id (OfferData). Every query is
 * limited to the customer, so another customer's offer is never found.
 */
@Component
class DemoOfferAcceptance implements ConfirmableOperation<DemoOfferAcceptance.OfferData> {

    static final String TYPE = "offer";

    /** What is needed to accept the offer: the offer is read again, for the customer, at the execution. */
    record OfferData(String offerId) {}

    private final DemoOfferRepository offers;

    DemoOfferAcceptance(DemoOfferRepository offers) {
        this.offers = offers;
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
        return find(customerId, data.offerId()).filter(DemoOffer::isOpen).map(offer -> ItsmeFreeText.builder()
                .bold("VoltaHome").lineBreak()
                .text("Customer number: " + customerId).lineBreak()
                .text("Accept offer " + offer.offerId() + ": " + offer.description()).lineBreak()
                .text("Price: ").bold("EUR " + offer.pricePerKwh().toPlainString() + " per kWh")
                .build());
    }

    @Override
    public Optional<String> execute(String customerId, OfferData data) {
        int updated = offers.accept(data.offerId(), customerId);
        return updated == 1 ? Optional.of(data.offerId()) : Optional.empty();
    }

    /** The offer of this customer as the API returns it: GET /api/offers/{id}. */
    Optional<DemoController.OfferDto> dto(String customerId, String offerId) {
        return find(customerId, offerId)
                .map(o -> new DemoController.OfferDto(o.offerId(), o.description(), o.pricePerKwh(), o.status()));
    }

    List<DemoOffer> openOffers(String customerId) {
        return offers.findByCustomerIdAndStatusOrderByOfferId(customerId, "OPEN");
    }

    /** The offer of this customer, whatever its status. */
    Optional<DemoOffer> find(String customerId, String offerId) {
        return offers.findByOfferIdAndCustomerId(offerId, customerId);
    }
}
