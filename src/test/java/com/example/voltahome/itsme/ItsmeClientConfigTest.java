package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

/** README: "Client authentication". A configuration that cannot work, or cannot be safe, is refused at startup. */
class ItsmeClientConfigTest {

    @Test
    void missingClientSecretIsRefused() {
        ItsmeProperties properties = properties(" ");

        assertThatThrownBy(() -> new ItsmeClientConfig().clientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("itsme.client-secret is required");
    }

    @Test
    void clientSecretShorterThan256BitsIsRefused() {
        ItsmeProperties properties = properties("only-31-bytes-0123456789abcdef!");

        // itsme signs the ID token with HS256 and the client secret

        assertThatThrownBy(() -> new ItsmeClientConfig().clientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void requestObjectIsRefusedWithAClientSecretShorterThan256Bits() {
        ItsmeProperties properties = properties("only-31-bytes-0123456789abcdef!");

        // The request object of a confirmation is signed with HS256 and the client secret
        assertThatThrownBy(() -> new ItsmeRequestObject(properties, (selector, context) -> List.of(), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static ItsmeProperties properties(String clientSecret) {
        return new ItsmeProperties("demo-client", clientSecret,
                "VOLTAHOME_LOGIN", "https://portal.voltahome.example/login/oauth2/code/itsme",
                "http://itsme.services/v2/claim/acr_advanced",
                new ItsmeProperties.Endpoints("https://idp.e2e.itsme.services/v2",
                        "https://idp.e2e.itsme.services/v2/authorization", "https://idp.e2e.itsme.services/v2/token",
                        "https://idp.e2e.itsme.services/v2/jwks", null),
                new ItsmeProperties.ProxySettings("", 0),
                new ItsmeProperties.Confirmation("VOLTAHOME_CONFIRM", "https://portal.voltahome.example/itsme/confirmation/callback"));
    }
}
