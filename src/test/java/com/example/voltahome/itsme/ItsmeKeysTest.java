package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** README: "Keys". Throw-away keys must never reach a real itsme environment. */
class ItsmeKeysTest {

    @Test
    void ephemeralKeysAreRefusedWithARealItsmeEndpoint() {
        ItsmeProperties properties = new ItsmeProperties("demo-client", ItsmeProperties.ClientAuthentication.PRIVATE_KEY_JWT,
                null, "VOLTAHOME_LOGIN", "https://portal.voltahome.example/login/oauth2/code/itsme",
                "http://itsme.services/v2/claim/acr_advanced",
                new ItsmeProperties.Endpoints("https://idp.e2e.itsme.services/v2", null, null, null, null),
                new ItsmeProperties.ProxySettings("", 0),
                new ItsmeProperties.Keys(true, null, null, null, List.of()));

        assertThatThrownBy(() -> new ItsmeKeys(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }
}
