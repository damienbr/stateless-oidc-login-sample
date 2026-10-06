package com.example.voltahome.itsme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * README: "Configuration". The static configuration is the source of truth; the discovery document is only
 * compared with it. A mismatch is logged at ERROR (alerting rule). Startup is never blocked.
 */
@Component
public class ItsmeDiscoveryCheck {

    public enum Status { OK, MISMATCH, UNREACHABLE }

    public record Result(Status status, List<String> mismatches) {}

    private static final Logger log = LoggerFactory.getLogger(ItsmeDiscoveryCheck.class);

    private final ItsmeProperties properties;
    private final RestClient restClient;

    public ItsmeDiscoveryCheck(ItsmeProperties properties, RestClient itsmeRestClient) {
        this.properties = properties;
        this.restClient = itsmeRestClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        check();
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void daily() {
        check();
    }

    public Result check() {
        Map<String, Object> d;
        try {
            d = restClient.get()
                    .uri(properties.endpoints().discoveryUri())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (d == null) {
                throw new IllegalStateException("empty discovery document");
            }
        } catch (Exception ex) {
            log.warn("itsme discovery check could not run: {}", ex.getMessage());
            return new Result(Status.UNREACHABLE, List.of());
        }

        ItsmeProperties.Endpoints e = properties.endpoints();
        List<String> mismatches = new ArrayList<>();
        compare(mismatches, "issuer", e.issuer(), d.get("issuer"));
        compare(mismatches, "authorization_endpoint", e.authorizationUri(), d.get("authorization_endpoint"));
        compare(mismatches, "token_endpoint", e.tokenUri(), d.get("token_endpoint"));
        compare(mismatches, "jwks_uri", e.jwkSetUri(), d.get("jwks_uri"));
        if (properties.usesKeyPairs()) {
            requireSupported(mismatches, d, "token_endpoint_auth_methods_supported", "private_key_jwt");
            requireSupported(mismatches, d, "id_token_encryption_alg_values_supported", "RSA-OAEP-256");
            requireSupported(mismatches, d, "id_token_encryption_enc_values_supported", "A128CBC-HS256");
        } else {
            requireSupported(mismatches, d, "token_endpoint_auth_methods_supported", "client_secret_post");
            requireSupported(mismatches, d, "id_token_encryption_alg_values_supported", "dir");
            requireSupported(mismatches, d, "id_token_encryption_enc_values_supported", "A256GCM");
        }

        if (mismatches.isEmpty()) {
            log.info("itsme discovery check OK");
            return new Result(Status.OK, List.of());
        }
        log.error("itsme discovery check FAILED: {}", mismatches);
        return new Result(Status.MISMATCH, List.copyOf(mismatches));
    }

    private static void compare(List<String> out, String field, String configured, Object published) {
        if (!Objects.equals(configured, published)) {
            out.add(field + " configured=" + configured + " published=" + published);
        }
    }

    private static void requireSupported(List<String> out, Map<String, Object> d, String field, String value) {
        if (d.get(field) instanceof Collection<?> values && !values.contains(value)) {
            out.add(field + " does not contain " + value);
        }
    }
}
