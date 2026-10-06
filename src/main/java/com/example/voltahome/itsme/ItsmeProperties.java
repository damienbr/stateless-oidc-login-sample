package com.example.voltahome.itsme;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** README: "Configuration". */
@ConfigurationProperties("itsme")
public record ItsmeProperties(
        String clientId,
        ClientAuthentication clientAuthentication,
        String clientSecret,
        String serviceCode,
        String redirectUri,
        String acr,
        Endpoints endpoints,
        ProxySettings proxy,
        Keys keys) {

    /**
     * The two itsme client authentication methods. README: "Two client authentication methods".
     * PRIVATE_KEY_JWT: key pairs, Request Object, ID token encrypted with our public key (recommended by itsme).
     * CLIENT_SECRET: shared secret, parameters in the URL, ID token encrypted with a key derived from the secret.
     */
    public enum ClientAuthentication { PRIVATE_KEY_JWT, CLIENT_SECRET }

    public boolean usesKeyPairs() {
        return clientAuthentication != ClientAuthentication.CLIENT_SECRET;
    }

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
     * The application's own key pairs. In production: a PKCS12 keystore provided by a vault.
     * "ephemeral" generates throw-away keys at startup: local development and tests only.
     */
    public record Keys(boolean ephemeral, String keystore, String keystorePassword,
                       String signingAlias, List<String> encryptionAliases) {}

    /** Keeps secrets out of logs and actuator output. */
    @Override
    public String toString() {
        return "ItsmeProperties[clientId=" + clientId + ", clientAuthentication=" + clientAuthentication
                + ", serviceCode=" + serviceCode + ", endpoints=" + endpoints + ", clientSecret=***, keystorePassword=***]";
    }
}
