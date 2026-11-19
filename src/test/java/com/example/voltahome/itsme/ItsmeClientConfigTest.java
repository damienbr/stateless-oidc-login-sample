package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** README: "Client authentication". A configuration that cannot work, or cannot be safe, is refused at startup. */
class ItsmeClientConfigTest {

    @Test
    void missingClientSecretIsRefused() {
        ItsmeProperties properties = properties(" ", ItsmeProperties.IdTokenSignature.RS256);

        assertThatThrownBy(() -> new ItsmeClientConfig().clientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("itsme.client-secret is required");
    }

    @Test
    void hs256IsRefusedWithAClientSecretShorterThan256Bits() {
        ItsmeProperties properties = properties("only-31-bytes-0123456789abcdef!", ItsmeProperties.IdTokenSignature.HS256);

        assertThatThrownBy(() -> new ItsmeClientConfig().clientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static ItsmeProperties properties(String clientSecret, ItsmeProperties.IdTokenSignature signature) {
        return new ItsmeProperties("demo-client", clientSecret, signature,
                "VOLTAHOME_LOGIN", "https://portal.voltahome.example/login/oauth2/code/itsme",
                "http://itsme.services/v2/claim/acr_advanced",
                new ItsmeProperties.Endpoints("https://idp.e2e.itsme.services/v2",
                        "https://idp.e2e.itsme.services/v2/authorization", "https://idp.e2e.itsme.services/v2/token",
                        "https://idp.e2e.itsme.services/v2/jwks", null),
                new ItsmeProperties.ProxySettings("", 0),
                new ItsmeProperties.Confirmation("VOLTAHOME_CONFIRM", "https://portal.voltahome.example/itsme/confirmation/callback"));
    }
}
