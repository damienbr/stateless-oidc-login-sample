package com.example.voltahome.security;

import com.example.voltahome.itsme.ItsmeAuthorizationRequestResolver;
import com.example.voltahome.itsme.ItsmeLoginFailureHandler;
import com.example.voltahome.itsme.ItsmeLoginSuccessHandler;
import com.example.voltahome.itsme.ItsmeOidcUserService;
import com.example.voltahome.itsme.JdbcAuthorizationRequestRepository;
import com.example.voltahome.itsme.NoOpAuthorizedClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/** README: "Stateless security configuration". The JwtDecoderFactory bean is picked up automatically. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ItsmeAuthorizationRequestResolver itsmeAuthorizationRequestResolver,
            JdbcAuthorizationRequestRepository authorizationRequestRepository,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> itsmeTokenResponseClient,
            ItsmeOidcUserService itsmeOidcUserService,
            ItsmeLoginSuccessHandler successHandler,
            ItsmeLoginFailureHandler failureHandler) throws Exception {
        http
            // Stateless: nothing reads or creates an HttpSession
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .requestCache(rc -> rc.requestCache(new NullRequestCache()))
            // The sample has no form of its own: the itsme login is a GET redirect and a GET callback protected
            // by "state". Keep your application's existing CSRF setup.
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login-error", "/error").permitAll()
                .requestMatchers("/.well-known/jwks.json").permitAll()                 // itsme reads our public keys
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(e -> e
                    .authorizationRequestResolver(itsmeAuthorizationRequestResolver)
                    .authorizationRequestRepository(authorizationRequestRepository))
                .tokenEndpoint(t -> t.accessTokenResponseClient(itsmeTokenResponseClient))
                .userInfoEndpoint(u -> u.oidcUserService(itsmeOidcUserService))
                .authorizedClientRepository(new NoOpAuthorizedClientRepository())
                .successHandler(successHandler)
                .failureHandler(failureHandler));
        // The application's own API authentication (its own JWT) is out of scope of the sample.
        return http.build();
    }
}
