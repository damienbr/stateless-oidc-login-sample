package com.example.voltahome.itsme;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWEDecryptionKeySelector;
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

/** itsme-specific Spring Security beans. */
@Configuration
public class ItsmeClientConfig {

    public static final String REGISTRATION_ID = "itsme";
    public static final String CONFIRMATION_REGISTRATION_ID = "itsme-confirmation";

    /** README: "Configuration". Static endpoints, no discovery fetch at startup. */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(ItsmeProperties p) {
        return new InMemoryClientRegistrationRepository(
                registration(p, REGISTRATION_ID, p.serviceCode(), p.redirectUri()));
    }

    /**
     * README: "Confirmation of an operation". Same client, other itsme service and redirect URI. Deliberately NOT in
     * the login's ClientRegistrationRepository: /oauth2/authorization/... cannot start a confirmation, and the login
     * callback cannot end one.
     */
    static ClientRegistration confirmationRegistration(ItsmeProperties p) {
        return registration(p, CONFIRMATION_REGISTRATION_ID,
                p.confirmation().serviceCode(), p.confirmation().redirectUri());
    }

    private static ClientRegistration registration(ItsmeProperties p, String registrationId,
                                                   String serviceCode, String redirectUri) {
        ItsmeProperties.Endpoints e = p.endpoints();
        if (p.clientSecret() == null || p.clientSecret().isBlank()) {
            throw new IllegalStateException("itsme.client-secret is required");
        }
        ItsmeCrypto.signatureKey(p.clientSecret());              // refuses a secret too short for HS256, now
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId(p.clientId())
                .clientSecret(p.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)   // client_id + client_secret in the body
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "service:" + serviceCode)
                .issuerUri(e.issuer())                 // checked against "iss" by OidcIdTokenValidator
                .authorizationUri(e.authorizationUri())
                .tokenUri(e.tokenUri())
                .jwkSetUri(e.jwkSetUri())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();
    }

    /** README: "Token request". RestClient with an explicit outbound proxy (token endpoint, discovery check). */
    @Bean
    RestClient itsmeRestClient(ItsmeProperties p) {
        HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        p.proxy().toProxy().ifPresent(proxy ->
                http.proxy(ProxySelector.of((InetSocketAddress) proxy.address())));

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .requestFactory(factory)
                .configureMessageConverters(converters -> converters
                        .disableDefaults()                                       // only these, in this order
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter())
                        .addCustomConverter(new JacksonJsonHttpMessageConverter()))   // Jackson 3 (discovery check)
                .defaultStatusHandler(itsmeErrorHandler())
                .build();
    }

    /**
     * README: "Token request". itsme puts the error description in "detail" instead of the standard
     * "error_description"; keep it, so that ItsmeLoginFailureHandler can log it.
     */
    private static OAuth2ErrorResponseErrorHandler itsmeErrorHandler() {
        OAuth2ErrorHttpMessageConverter errors = new OAuth2ErrorHttpMessageConverter();
        errors.setErrorConverter(parameters -> new OAuth2Error(parameters.get("error"),
                parameters.getOrDefault("error_description", parameters.get("detail")),
                parameters.get("error_uri")));
        OAuth2ErrorResponseErrorHandler handler = new OAuth2ErrorResponseErrorHandler();
        handler.setErrorConverter(errors);
        return handler;
    }

    /**
     * README: "Token request". Used by the login and by the confirmation. Spring sends client_id and client_secret
     * in the body (client_secret_post) and the PKCE code_verifier; nothing to add.
     */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> itsmeTokenResponseClient(
            RestClient itsmeRestClient) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(itsmeRestClient);
        return client;
    }

    /**
     * itsme public keys, through the proxy, cached by Nimbus. Only the encryption key ("use": "enc") is used: it
     * encrypts the request object of a confirmation (ItsmeRequestObject).
     */
    @Bean
    JWKSource<SecurityContext> itsmeJwkSource(ItsmeProperties p) throws MalformedURLException {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(5_000, 5_000, 512 * 1024);
        p.proxy().toProxy().ifPresent(retriever::setProxy);
        return JWKSourceBuilder.create(URI.create(p.endpoints().jwkSetUri()).toURL(), retriever)
                .retrying(true)
                .build();
    }

    /**
     * README: "ID token validation". Spring's default decoder cannot decrypt JWE tokens. Expected format: a JWE
     * (dir, A256GCM, key derived from the client secret) containing a JWS signed with HS256 (the client secret
     * itself). No other signature algorithm is accepted: README: "Client authentication".
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(ItsmeProperties properties) {
        Map<String, JwtDecoder> cache = new ConcurrentHashMap<>();
        return registration -> cache.computeIfAbsent(registration.getRegistrationId(), id -> {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWEKeySelector(new JWEDecryptionKeySelector<>(              // 1. decrypt with the shared key
                    JWEAlgorithm.DIR, EncryptionMethod.A256GCM,
                    new ImmutableSecret<>(ItsmeCrypto.idTokenDecryptionKey(properties.clientSecret()))));
            processor.setJWSKeySelector(new SingleKeyJWSKeySelector<>(               // 2. verify the HMAC (client secret)
                    JWSAlgorithm.HS256, ItsmeCrypto.signatureKey(properties.clientSecret())));
            processor.setJWTClaimsSetVerifier((claims, context) -> { });             // validated by Spring below

            NimbusJwtDecoder nimbus = new NimbusJwtDecoder(processor);
            nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ofSeconds(60)),
                    new OidcIdTokenValidator(registration),
                    new AcrValidator(properties.acr())));

            return token -> {
                ItsmeCrypto.requireNestedSignedJwt(token);
                return nimbus.decode(token);
            };
        });
    }
}
