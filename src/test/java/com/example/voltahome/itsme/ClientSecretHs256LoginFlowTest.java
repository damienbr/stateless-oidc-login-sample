package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The client secret method when the itsme registration signs ID tokens with HS256 (itsme.id-token-signature=HS256):
 * the JWS inside the encryption is an HMAC made with the client secret (OIDC Core §10.1). Only what differs from
 * ClientSecretLoginFlowTest is tested here. README: "Two client authentication methods".
 */
class ClientSecretHs256LoginFlowTest extends ItsmeIntegrationTest {

    static final String CLIENT_SECRET = "test-client-secret-hs256-0123456789abcdef";

    @DynamicPropertySource
    static void clientSecretMethodWithHs256(DynamicPropertyRegistry registry) {
        registry.add("itsme.client-authentication", () -> "client-secret");
        registry.add("itsme.client-secret", () -> CLIENT_SECRET);
        registry.add("itsme.id-token-signature", () -> "HS256");
    }

    @Autowired ItsmeDiscoveryCheck discoveryCheck;

    @Override
    protected ItsmeWireMock.IdTokenBuilder idToken(String nonce) {
        return stub.idTokenForClientSecret(itsme.baseUrl() + "/v2", nonce, CLIENT_SECRET).signedWithSecret(CLIENT_SECRET);
    }

    @Override
    protected void stubTokenEndpoint(String idToken) {
        stub.stubTokenForClientSecret(itsme, idToken, CLIENT_SECRET);
    }

    @Test
    void hs256TokenLogsTheLinkedCustomerIn() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        MvcResult result = login(t -> t.sub("stub-sub-alex")).andExpect(redirectedToLoginHandover()).andReturn();

        portal(tokenAfter(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("C-1001")));
    }

    @Test
    void rs256TokenIsRejectedWhenHs256IsConfigured() throws Exception {
        // Exactly one signature algorithm is accepted: the one configured
        login(t -> t.signedWithItsmeKey()).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        login(t -> t.signedWithSecret("another-client-secret-0123456789abcdef"))
                .andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void encryptedButUnsignedTokenIsRejected() throws Exception {
        StartedLogin started = startLogin();
        stubTokenEndpoint(idToken(started.nonce()).buildEncryptedButUnsigned());

        callback(started.state()).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void basicAcrIsRejected() throws Exception {
        login(t -> t.acr(ItsmeWireMock.ACR_BASIC)).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void discoveryCheckChecksTheHs256Signature() {
        assertThat(discoveryCheck.check().status()).isEqualTo(ItsmeDiscoveryCheck.Status.OK);
    }
}
