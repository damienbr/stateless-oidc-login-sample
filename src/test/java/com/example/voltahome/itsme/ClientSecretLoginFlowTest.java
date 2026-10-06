package com.example.voltahome.itsme;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.example.voltahome.support.ItsmeIntegrationTest;
import com.example.voltahome.support.ItsmeWireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * The same login with the client secret method (itsme.client-authentication=client-secret):
 * client_secret_post, no Request Object, ID token encrypted with dir + A256GCM (key derived from the secret).
 * Only what differs from the key pairs method is tested here; customer mapping is shared code.
 */
class ClientSecretLoginFlowTest extends ItsmeIntegrationTest {

    static final String CLIENT_SECRET = "test-client-secret-0123456789";

    @DynamicPropertySource
    static void clientSecretMethod(DynamicPropertyRegistry registry) {
        registry.add("itsme.client-authentication", () -> "client-secret");
        registry.add("itsme.client-secret", () -> CLIENT_SECRET);
    }

    @Autowired ItsmeDiscoveryCheck discoveryCheck;
    @Autowired ObjectMapper json;

    @Override
    protected ItsmeWireMock.IdTokenBuilder idToken(String nonce) {
        return stub.idTokenForClientSecret(itsme.baseUrl() + "/v2", nonce, CLIENT_SECRET);
    }

    @Override
    protected void stubTokenEndpoint(String idToken) {
        stub.stubTokenForClientSecret(itsme, idToken, CLIENT_SECRET);
    }

    @Test
    void authorizationParametersTravelInTheUrlWithoutRequestObject() throws Exception {
        StartedLogin started = startLogin();
        String location = decode(started.location());

        assertThat(started.requestObject()).isNull();
        assertThat(location).contains("acr_values=" + ItsmeWireMock.ACR_ADVANCED, "code_challenge_method=S256");
    }

    @Test
    void authorizationRequestAsksForFraudSignalsOnlyNoIdentityClaim() throws Exception {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(startLogin().location()).build()
                .getQueryParams();

        assertThat(json.readTree(decode(query.getFirst("claims"))).get("id_token").propertyNames())
                .containsExactly(ItsmeClaims.ONGOING_CALL);
        assertThat(decode(query.getFirst("scope")).split(" ")).containsExactlyInAnyOrder("openid", "service:VOLTAHOME_LOGIN");
    }

    @Test
    void tokenRequestUsesClientSecretPostAndPkce() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        login(t -> t.sub("stub-sub-alex")).andExpect(status().isOk());

        Map<String, String> form = formParams(
                itsme.findAll(postRequestedFor(urlEqualTo("/v2/token"))).get(0).getBodyAsString());
        assertThat(form).containsEntry("client_secret", CLIENT_SECRET)
                .containsKey("code_verifier")
                .doesNotContainKey("client_assertion");
    }

    @Test
    void linkedItsmeAccountLogsTheCustomerIn() throws Exception {
        givenLinked("C-1001", "stub-sub-alex");
        login(t -> t.sub("stub-sub-alex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("C-1001"));
    }

    @Test
    void tokenEncryptedWithAnotherSecretIsRejected() throws Exception {
        login(t -> t.encryptedWithSecret("another-secret")).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void encryptedButUnsignedTokenIsRejected() throws Exception {
        // Anyone holding the client secret can encrypt: only itsme's signature proves the origin
        StartedLogin started = startLogin();
        stubTokenEndpoint(idToken(started.nonce()).buildEncryptedButUnsigned());

        callback(started.state()).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void withoutRequestObjectTheAcrCheckOnTheResponseIsTheProtection() throws Exception {
        // As if someone had removed acr_values from the URL: itsme falls back to the basic level
        login(t -> t.acr(ItsmeWireMock.ACR_BASIC)).andExpect(redirectedUrl("/login-error?error=invalid_id_token"));
    }

    @Test
    void noPublicKeysArePublished() throws Exception {
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isNotFound());
    }

    @Test
    void discoveryCheckChecksTheClientSecretAlgorithms() {
        assertThat(discoveryCheck.check().status()).isEqualTo(ItsmeDiscoveryCheck.Status.OK);
    }
}
