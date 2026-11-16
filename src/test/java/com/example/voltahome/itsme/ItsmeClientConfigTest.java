package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** README: "Two client authentication methods". An HS256 configuration that cannot be safe is refused at startup. */
class ItsmeClientConfigTest {

    @Test
    void hs256IsRefusedWithKeyPairs() {
        ItsmeProperties properties = properties(ItsmeProperties.ClientAuthentication.PRIVATE_KEY_JWT, null);

        assertThatThrownBy(() -> new ItsmeClientConfig().clientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-authentication=client-secret");
    }

    @Test
    void hs256IsRefusedWithAClientSecretShorterThan256Bits() {
        ItsmeProperties properties = properties(ItsmeProperties.ClientAuthentication.CLIENT_SECRET,
                "only-31-bytes-0123456789abcdef!");

        assertThatThrownBy(() -> new ItsmeClientConfig().clientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static ItsmeProperties properties(ItsmeProperties.ClientAuthentication method, String clientSecret) {
        return new ItsmeProperties("demo-client", method, clientSecret, ItsmeProperties.IdTokenSignature.HS256,
                "VOLTAHOME_LOGIN", "https://portal.voltahome.example/login/oauth2/code/itsme",
                "http://itsme.services/v2/claim/acr_advanced",
                new ItsmeProperties.Endpoints("https://idp.e2e.itsme.services/v2",
                        "https://idp.e2e.itsme.services/v2/authorization", "https://idp.e2e.itsme.services/v2/token",
                        "https://idp.e2e.itsme.services/v2/jwks", null),
                new ItsmeProperties.ProxySettings("", 0),
                new ItsmeProperties.Keys(false, null, null, null, List.of()),
                new ItsmeProperties.Confirmation("VOLTAHOME_CONFIRM", "https://portal.voltahome.example/portal/confirmation/callback"));
    }
}
