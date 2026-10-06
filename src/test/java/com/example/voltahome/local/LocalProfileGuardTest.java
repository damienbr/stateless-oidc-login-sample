package com.example.voltahome.local;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.example.voltahome.itsme.ItsmeProperties;
import org.junit.jupiter.api.Test;

/** README: "Run the login in a browser". The itsme stub must never run next to a real itsme environment. */
class LocalProfileGuardTest {

    @Test
    void localProfileIsRefusedWithARealItsmeEndpoint() {
        ItsmeProperties properties = new ItsmeProperties("demo-client", ItsmeProperties.ClientAuthentication.PRIVATE_KEY_JWT,
                null, "VOLTAHOME_LOGIN", "https://portal.voltahome.example/login/oauth2/code/itsme",
                "http://itsme.services/v2/claim/acr_advanced",
                new ItsmeProperties.Endpoints("https://idp.e2e.itsme.services/v2", null, null, null, null),
                new ItsmeProperties.ProxySettings("", 0),
                new ItsmeProperties.Keys(true, null, null, null, List.of()));

        assertThatThrownBy(() -> new LocalProfileGuard(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }
}
