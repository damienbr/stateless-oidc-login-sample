package com.example.voltahome.demo;

import java.time.Instant;
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
                  Instant expiresAt, boolean confirmationAvailable, String operation, List<Confirmed> confirmed) {}

    record Confirmed(String operation, Instant confirmedAt) {}

    private final CustomerRepository customers;
    private final ItsmeConfirmationService confirmation;
    private final DemoConfirmedOperations confirmedOperations;

    DemoController(CustomerRepository customers, ItsmeConfirmationService confirmation,
                   DemoConfirmedOperations confirmedOperations) {
        this.customers = customers;
        this.confirmation = confirmation;
        this.confirmedOperations = confirmedOperations;
    }

    @GetMapping(value = "/api/portal", produces = MediaType.APPLICATION_JSON_VALUE)
    Portal portal(@AuthenticationPrincipal Jwt token) {
        String customerId = token.getSubject();
        String name = customers.findActiveById(customerId)     // our customer data, not itsme's
                .map(Customer::displayName)
                .orElse(customerId);
        List<Confirmed> confirmed = confirmedOperations.forCustomer(customerId).stream()
                .map(r -> new Confirmed(DemoConfirmationController.describe(r.operationReference()), r.confirmedAt()))
                .toList();
        return new Portal(customerId, name, token.getClaimAsString("acr"), token.getIssuedAt(), token.getExpiresAt(),
                confirmation.isAvailable(), DemoConfirmationController.DESCRIPTION, confirmed);
    }
}
