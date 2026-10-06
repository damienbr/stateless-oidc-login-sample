package com.example.voltahome.itsme;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.example.voltahome.customer.CustomerRepository;
import com.example.voltahome.fraud.FraudService;
import com.example.voltahome.fraud.FraudSignal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * README: "Customer mapping". Finds the customer through the itsme "sub" (a pseudonym). An unknown "sub" is
 * rejected: the login authenticates, it does not identify (README: "Authentication, not identification").
 * Replaces Spring's OidcUserService, which would copy every claim into the principal. Does not call the
 * UserInfo endpoint.
 */
@Component
public class ItsmeOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    public static final String CUSTOMER_ID = "customer_id";

    private final ItsmeLinkRepository links;
    private final CustomerRepository customers;
    private final FraudService fraud;
    private final Clock clock;

    public ItsmeOidcUserService(ItsmeLinkRepository links, CustomerRepository customers,
                                FraudService fraud, Clock clock) {
        this.links = links;
        this.customers = customers;
        this.fraud = fraud;
        this.clock = clock;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcIdToken idToken = request.getIdToken();
        String sub = idToken.getSubject();
        Optional<String> customerId = links.findCustomerBySub(sub);

        if (Boolean.TRUE.equals(idToken.getClaimAsBoolean(ItsmeClaims.ONGOING_CALL))) {
            fraud.report(FraudSignal.ONGOING_CALL, customerId.orElse(null), sub);
            throw failure("fraud_suspected");
        }

        if (customerId.isEmpty()) {
            throw failure("not_linked");
        }
        if (customers.findActiveById(customerId.get()).isEmpty()) {
            throw failure("no_customer");
        }
        links.touch(customerId.get(), clock.instant());

        // Principal: the sub (a pseudonym) and our own data only, never the raw token or other itsme claims
        OidcIdToken principal = OidcIdToken.withTokenValue("redacted")
                .subject(sub)
                .issuer(idToken.getIssuer().toString())
                .audience(idToken.getAudience())
                .issuedAt(idToken.getIssuedAt())
                .expiresAt(idToken.getExpiresAt())
                .claim(CUSTOMER_ID, customerId.get())
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ITSME_USER")),
                principal, IdTokenClaimNames.SUB);
    }

    private static OAuth2AuthenticationException failure(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
