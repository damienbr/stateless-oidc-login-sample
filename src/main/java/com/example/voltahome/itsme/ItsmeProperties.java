package com.example.voltahome.itsme;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** README: "Configuration". */
@ConfigurationProperties("itsme")
public record ItsmeProperties(
        String clientId,
        String clientSecret,
        String serviceCode,
        String redirectUri,
        String acr,
        Endpoints endpoints,
        ProxySettings proxy,
        Confirmation confirmation) {

    public record Endpoints(String issuer, String authorizationUri, String tokenUri,
                            String jwkSetUri, String discoveryUri) {}

    public record ProxySettings(String host, int port) {
        /** Empty when no outbound proxy is configured (local development, tests). */
        public Optional<Proxy> toProxy() {
            if (host == null || host.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        }
    }

    /**
     * README: "Confirmation of an operation". A separate itsme service, registered as a Confirmation service, with
     * its own redirect URI (/itsme/confirmation/callback).
     */
    public record Confirmation(String serviceCode, String redirectUri) {}

    /** Keeps secrets out of logs and actuator output. */
    @Override
    public String toString() {
        return "ItsmeProperties[clientId=" + clientId
                + ", serviceCode=" + serviceCode + ", confirmation=" + confirmation + ", endpoints=" + endpoints
                + ", clientSecret=***]";
    }
}
