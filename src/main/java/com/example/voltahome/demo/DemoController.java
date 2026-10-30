package com.example.voltahome.demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.voltahome.customer.Customer;
import com.example.voltahome.customer.CustomerRepository;
import com.example.voltahome.itsme.ItsmeConfirmationService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The protected portal, as a JSON API for the SPA (static/index.html). DemoSecurityConfig only lets a
 * request with a valid demo token through; the customer comes from that token, never from a request parameter.
 */
@RestController
class DemoController {

    record Portal(String customerId, String displayName, String authenticationLevel, Instant loggedInAt,
                  Instant expiresAt, boolean confirmationAvailable, List<Operation> operations,
                  List<Confirmed> confirmed) {}

    /** README: "Several operations, one callback". One button per open order or offer. */
    record Operation(String description, String action, String label) {}

    record Confirmed(String operationType, String operationReference, Instant confirmedAt) {}

    private final CustomerRepository customers;
    private final ItsmeConfirmationService confirmation;
    private final DemoConfirmedOperations confirmedOperations;
    private final DemoOrderConfirmation orders;
    private final DemoOfferAcceptance offers;

    DemoController(CustomerRepository customers, ItsmeConfirmationService confirmation,
                   DemoConfirmedOperations confirmedOperations, DemoOrderConfirmation orders,
                   DemoOfferAcceptance offers) {
        this.customers = customers;
        this.confirmation = confirmation;
        this.confirmedOperations = confirmedOperations;
        this.orders = orders;
        this.offers = offers;
    }

    @GetMapping(value = "/api/portal", produces = MediaType.APPLICATION_JSON_VALUE)
    Portal portal(@AuthenticationPrincipal Jwt token) {
        String customerId = token.getSubject();
        String name = customers.findActiveById(customerId)     // our customer data, not itsme's
                .map(Customer::displayName)
                .orElse(customerId);
        List<Operation> operations = new ArrayList<>();
        for (DemoOrderConfirmation.OpenOrder order : orders.openOrders(customerId)) {
            operations.add(new Operation("Order " + order.orderId() + ": " + order.description() + ", EUR "
                    + order.amount().toPlainString(), "/api/orders/" + order.orderId() + "/confirm", "Confirm order"));
        }
        for (DemoOfferAcceptance.OpenOffer offer : offers.openOffers(customerId)) {
            operations.add(new Operation("Offer " + offer.offerId() + ": " + offer.description() + ", EUR "
                    + offer.pricePerKwh().toPlainString() + " per kWh", "/api/offers/" + offer.offerId() + "/accept",
                    "Accept offer"));
        }
        List<Confirmed> confirmed = confirmedOperations.forCustomer(customerId).stream()
                .map(r -> new Confirmed(r.operationType(), r.operationReference(), r.confirmedAt()))
                .toList();
        return new Portal(customerId, name, token.getClaimAsString("acr"), token.getIssuedAt(), token.getExpiresAt(),
                confirmation.isAvailable(), operations, confirmed);
    }
}
