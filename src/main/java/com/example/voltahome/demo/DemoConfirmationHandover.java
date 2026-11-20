package com.example.voltahome.demo;

import com.example.voltahome.customer.Customer;
import com.example.voltahome.customer.CustomerRepository;
import com.example.voltahome.itsme.ConfirmationHandover;
import com.example.voltahome.itsme.ItsmeConfirmedOperation;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Stands in for the application's token management at the end of a confirmation (README: "Hand-over"):
 * issues a new demo token for the customer and returns it in the JSON answer to the SPA, with the type and reference
 * of the executed operation, so that the SPA can reload that record. The token never travels in a URL or a cookie.
 */
@Component
class DemoConfirmationHandover implements ConfirmationHandover {

    /** What the SPA receives: our new token, our customer data and where to find the record. No itsme claim. */
    record ConfirmationResponse(String accessToken, String tokenType, long expiresIn, String customerId,
                                String displayName, String operationType, String operationReference) {}

    private final DemoToken token;
    private final CustomerRepository customers;

    DemoConfirmationHandover(DemoToken token, CustomerRepository customers) {
        this.token = token;
        this.customers = customers;
    }

    @Override
    public Object complete(ItsmeConfirmedOperation confirmed) {
        String displayName = customers.findActiveById(confirmed.customerId())     // our customer data, not itsme's
                .map(Customer::displayName)
                .orElse(confirmed.customerId());
        String accessToken = token.issue(confirmed.customerId(), confirmed.authenticationLevel(),
                confirmed.confirmedAt());
        return new ConfirmationResponse(accessToken, "Bearer", DemoToken.TTL.toSeconds(), confirmed.customerId(),
                displayName, confirmed.operationType(), confirmed.operationReference());
    }
}
