package com.example.voltahome.demo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.voltahome.customer.Customer;
import com.example.voltahome.customer.CustomerRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMO ONLY. The protected portal, as a JSON API for the SPA (static/index.html). DemoSecurityConfig only lets a
 * request with a valid demo token through; the customer comes from that token, never from a request parameter, and
 * every record is read for that customer only (another customer's order or offer answers 404).
 */
@RestController
class DemoController {

    record Portal(String customerId, String displayName, String authenticationLevel, Instant loggedInAt,
                  Instant expiresAt, List<Operation> operations,
                  List<Confirmed> confirmed) {}

    /**
     * README: "Several operations, one callback". One button per product to order and per open offer. The SPA posts
     * "request" to "action", with its browser code challenge added.
     */
    record Operation(String description, String action, String label, Map<String, String> request) {}

    record Confirmed(String operationType, String operationReference, Instant confirmedAt) {}

    record OrderDto(String orderId, String description, BigDecimal amount, String status) {}

    record OfferDto(String offerId, String description, BigDecimal pricePerKwh, String status) {}

    private final CustomerRepository customers;
    private final DemoConfirmedOperations confirmedOperations;
    private final DemoOrderConfirmation orders;
    private final DemoOfferAcceptance offers;

    DemoController(CustomerRepository customers, DemoConfirmedOperations confirmedOperations,
                   DemoOrderConfirmation orders, DemoOfferAcceptance offers) {
        this.customers = customers;
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
        for (DemoOrderConfirmation.Product product : orders.products()) {
            operations.add(new Operation(product.description() + ", EUR " + product.price().toPlainString(),
                    "/api/orders/confirm", "Order", Map.of("productCode", product.productCode())));
        }
        for (DemoOfferAcceptance.Offer offer : offers.openOffers(customerId)) {
            operations.add(new Operation("Offer " + offer.offerId() + ": " + offer.description() + ", EUR "
                    + offer.pricePerKwh().toPlainString() + " per kWh", "/api/offers/" + offer.offerId() + "/accept",
                    "Accept offer", Map.of()));
        }
        List<Confirmed> confirmed = confirmedOperations.forCustomer(customerId).stream()
                .map(e -> new Confirmed(e.evidence().operationType(), e.reference(), e.evidence().confirmedAt()))
                .toList();
        return new Portal(customerId, name, token.getClaimAsString("acr"), token.getIssuedAt(), token.getExpiresAt(),
                operations, confirmed);
    }

    /** One order, created by a confirmation: the SPA shows this, not a copy it kept (README: "Hand-over"). */
    @GetMapping(value = "/api/orders/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrderDto> order(@PathVariable String orderId, @AuthenticationPrincipal Jwt token) {
        return ResponseEntity.of(orders.dto(token.getSubject(), orderId));
    }

    /** One offer, as stored now. */
    @GetMapping(value = "/api/offers/{offerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OfferDto> offer(@PathVariable String offerId, @AuthenticationPrincipal Jwt token) {
        return ResponseEntity.of(offers.dto(token.getSubject(), offerId));
    }
}
