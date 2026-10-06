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
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWEDecryptionKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
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

    /** README: "Configuration". Static endpoints, no discovery fetch at startup. */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(ItsmeProperties p) {
        ItsmeProperties.Endpoints e = p.endpoints();
        ClientRegistration.Builder itsme = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(p.clientId());
        if (p.usesKeyPairs()) {
            itsme.clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
        } else {
            if (p.clientSecret() == null || p.clientSecret().isBlank()) {
                throw new IllegalStateException("itsme.client-secret is required with itsme.client-authentication=client-secret");
            }
            itsme.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)   // client_id + client_secret in the body
                    .clientSecret(p.clientSecret());
        }
        itsme
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(p.redirectUri())
                .scope("openid", "service:" + p.serviceCode())
                .issuerUri(e.issuer())                 // checked against "iss" by OidcIdTokenValidator
                .authorizationUri(e.authorizationUri())
                .tokenUri(e.tokenUri())
                .jwkSetUri(e.jwkSetUri())
                .userNameAttributeName(IdTokenClaimNames.SUB);
        return new InMemoryClientRegistrationRepository(itsme.build());
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
     * README: "Token request". Spring adds the PKCE code_verifier in both methods.
     * - Key pairs (private_key_jwt): Spring builds and signs the client assertion (iss, sub, aud = token endpoint,
     *   jti, iat, exp) with our signing key.
     * - Client secret: Spring sends client_id and client_secret in the body; nothing to add.
     */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> itsmeTokenResponseClient(
            RestClient itsmeRestClient, ItsmeKeys keys, ItsmeProperties properties) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(itsmeRestClient);
        if (properties.usesKeyPairs()) {
            client.addParametersConverter(new NimbusJwtClientAuthenticationParametersConverter<>(
                    registration -> REGISTRATION_ID.equals(registration.getRegistrationId()) ? keys.signingKey() : null));
        }
        return client;
    }

    /** itsme public keys (signature of ID tokens, encryption of Request Objects), through the proxy, cached by Nimbus. */
    @Bean
    JWKSource<SecurityContext> itsmeJwkSource(ItsmeProperties p) throws MalformedURLException {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(5_000, 5_000, 512 * 1024);
        p.proxy().toProxy().ifPresent(retriever::setProxy);
        return JWKSourceBuilder.create(URI.create(p.endpoints().jwkSetUri()).toURL(), retriever)
                .retrying(true)
                .build();
    }

    /**
     * README: "ID token validation". Spring's default decoder cannot decrypt JWE tokens. Expected formats:
     * - key pairs: JWE (RSA-OAEP-256, A128CBC-HS256, our public key) containing a JWS (RS256, itsme's key);
     * - client secret: JWE (dir, A256GCM, key derived from the secret) containing a JWS (RS256, itsme's key).
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(ItsmeProperties properties, ItsmeKeys keys,
                                                                JWKSource<SecurityContext> itsmeJwkSource) {
        Map<String, JwtDecoder> cache = new ConcurrentHashMap<>();
        return registration -> cache.computeIfAbsent(registration.getRegistrationId(), id -> {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            if (properties.usesKeyPairs()) {
                processor.setJWEKeySelector(new JWEDecryptionKeySelector<>(          // 1. decrypt with our private key
                        JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A128CBC_HS256,
                        new ImmutableJWKSet<>(keys.encryptionKeySet())));
            } else {
                processor.setJWEKeySelector(new JWEDecryptionKeySelector<>(          // 1. decrypt with the shared key
                        JWEAlgorithm.DIR, EncryptionMethod.A256GCM,
                        new ImmutableSecret<>(ItsmeCrypto.idTokenDecryptionKey(properties.clientSecret()))));
            }
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(            // 2. verify itsme's signature
                    JWSAlgorithm.RS256, itsmeJwkSource));
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
