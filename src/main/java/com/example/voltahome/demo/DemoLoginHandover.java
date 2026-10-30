package com.example.voltahome.demo;

import com.example.voltahome.customer.Customer;
import com.example.voltahome.customer.CustomerRepository;
import com.example.voltahome.itsme.ItsmeLoginResult;
import com.example.voltahome.itsme.LoginHandover;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Stands in for the application's token management, which is out of scope.
 * Like a real implementation, it issues the application's own credential (here, the demo token) and returns it in the
 * JSON answer to the SPA, with the customer's own data. The token never travels in a URL or a cookie.
 */
@Component
class DemoLoginHandover implements LoginHandover {

    /** What the SPA receives: our token and our customer data, no itsme claim. */
    record LoginResponse(String accessToken, String tokenType, long expiresIn, String customerId,
                         String displayName) {}

    private final DemoToken token;
    private final CustomerRepository customers;

    DemoLoginHandover(DemoToken token, CustomerRepository customers) {
        this.token = token;
        this.customers = customers;
    }

    @Override
    public Object complete(ItsmeLoginResult result) {
        String displayName = customers.findActiveById(result.customerId())     // our customer data, not itsme's
                .map(Customer::displayName)
                .orElse(result.customerId());
        return new LoginResponse(token.issue(result), "Bearer", DemoToken.TTL.toSeconds(), result.customerId(),
                displayName);
    }
}
