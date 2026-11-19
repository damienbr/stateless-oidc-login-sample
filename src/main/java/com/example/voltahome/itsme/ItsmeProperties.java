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
        IdTokenSignature idTokenSignature,
        String serviceCode,
        String redirectUri,
        String acr,
        Endpoints endpoints,
        ProxySettings proxy,
        Confirmation confirmation) {

    /**
     * How itsme signs the ID token, as chosen when the client is registered at itsme.
     * README: "Client authentication". RS256 (default, recommended): itsme's private key, verified with
     * itsme's JWKS. HS256: an HMAC made with the client secret itself (OIDC Core §10.1); anyone who holds the secret
     * can make one, so use it only when the itsme registration cannot be changed to RS256.
     */
    public enum IdTokenSignature { RS256, HS256 }

    /** True only when HS256 is configured explicitly; anything else means RS256. */
    public boolean idTokenSignedWithSecret() {
        return idTokenSignature == IdTokenSignature.HS256;
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
     * README: "Confirmation of an operation". A separate itsme service, registered as a Confirmation service, with
     * its own redirect URI (/itsme/confirmation/callback).
     */
    public record Confirmation(String serviceCode, String redirectUri) {}

    /** Keeps secrets out of logs and actuator output. */
    @Override
    public String toString() {
        return "ItsmeProperties[clientId=" + clientId + ", idTokenSignature=" + idTokenSignature
                + ", serviceCode=" + serviceCode + ", confirmation=" + confirmation + ", endpoints=" + endpoints
                + ", clientSecret=***]";
    }
}
