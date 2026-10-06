package com.example.voltahome.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.example.voltahome.fraud.FraudService;
import com.example.voltahome.itsme.ItsmeKeys;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;   // Spring Boot 4 package
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Starts the application with H2 and a WireMock itsme. Each test starts from a clean state.
 * Default method: key pairs, with ephemeral keys (generated at startup), as in local development.
 * ClientSecretLoginFlowTest overrides the method and the two hooks below.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class ItsmeIntegrationTest {

    protected static final String CLIENT_ID = "test-client";
    protected static final String CALLBACK = "/login/oauth2/code/itsme";

    /**
     * One WireMock server for the whole test run. A JUnit extension per test class would restart it on a new port,
     * while Spring reuses the same cached application context, still pointing at the old port.
     */
    protected static final WireMockServer itsme = startItsme();

    private static WireMockServer startItsme() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    protected static final ItsmeWireMock stub = new ItsmeWireMock(CLIENT_ID);

    @DynamicPropertySource
    static void itsmeProperties(DynamicPropertyRegistry registry) {
        registry.add("itsme.client-id", () -> CLIENT_ID);
        registry.add("itsme.redirect-uri", () -> "http://localhost" + CALLBACK);   // MockMvc host
        registry.add("itsme.endpoints.issuer", () -> itsme.baseUrl() + "/v2");
        registry.add("itsme.endpoints.authorization-uri", () -> itsme.baseUrl() + "/v2/authorization");
        registry.add("itsme.endpoints.token-uri", () -> itsme.baseUrl() + "/v2/token");
        registry.add("itsme.endpoints.jwk-set-uri", () -> itsme.baseUrl() + "/v2/jwks");
        registry.add("itsme.endpoints.discovery-uri", () -> itsme.baseUrl() + "/v2/.well-known/openid-configuration");
        registry.add("itsme.proxy.host", () -> "");
        registry.add("itsme.keys.ephemeral", () -> "true");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected FraudService fraud;
    @Autowired protected MutableClock clock;
    @Autowired protected ItsmeKeys keys;

    @BeforeEach
    void resetState() {
        jdbc.sql("DELETE FROM itsme_link").update();
        jdbc.sql("DELETE FROM itsme_authorization_request").update();
        fraud.clear();
        clock.reset();
        itsme.resetAll();
        stub.stubJwks(itsme);
        stub.stubDiscovery(itsme);
    }

    // ------------------------------------------------------------------ helpers

    /** Step 1: the user clicks "Log in with itsme". */
    protected record StartedLogin(String location, String state, String nonce, String requestObject) {}

    protected StartedLogin startLogin() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/itsme"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getRequest().getSession(false)).as("no HttpSession").isNull();
        String location = result.getResponse().getRedirectedUrl();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return new StartedLogin(location, decode(query.getFirst("state")), decode(query.getFirst("nonce")),
                decode(query.getFirst("request")));
    }

    /** itsme redirects back with a code; the backend exchanges it and validates the ID token. */
    protected ResultActions callback(String state) throws Exception {
        return mvc.perform(get(CALLBACK).param("code", "stub-authorization-code").param("state", state));
    }

    /** A complete itsme login where the test only describes the ID token returned by itsme. */
    protected ResultActions login(UnaryOperator<ItsmeWireMock.IdTokenBuilder> token) throws Exception {
        StartedLogin started = startLogin();
        stubTokenEndpoint(token.apply(idToken(started.nonce())).build());
        return callback(started.state());
    }

    /** ID token as itsme sends it for the client's method. */
    protected ItsmeWireMock.IdTokenBuilder idToken(String nonce) {
        return stub.idToken(itsme.baseUrl() + "/v2", nonce, ourPublicEncryptionKey());
    }

    /** Token endpoint that only answers the client authentication of the client's method. */
    protected void stubTokenEndpoint(String idToken) {
        stub.stubToken(itsme, idToken);
    }

    protected RSAKey ourPublicEncryptionKey() {
        return keys.encryptionKeys().get(0).toPublicJWK();
    }

    protected void givenLinked(String customerId, String sub) {
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(30L * 24 * 3600));
        jdbc.sql("INSERT INTO itsme_link (customer_id, itsme_sub, linked_at, last_login_at) VALUES (:c, :s, :t, :t)")
                .param("c", customerId).param("s", sub).param("t", past).update();
    }

    protected static String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Parses an application/x-www-form-urlencoded body. */
    protected static Map<String, String> formParams(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return params;
    }
}
