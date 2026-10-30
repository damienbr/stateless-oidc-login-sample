package com.example.voltahome.security;

import com.example.voltahome.itsme.ItsmeAuthorizationRequestResolver;
import com.example.voltahome.itsme.ItsmeLoginAuthentication;
import com.example.voltahome.itsme.ItsmeLoginFailureHandler;
import com.example.voltahome.itsme.ItsmeLoginSuccessHandler;
import com.example.voltahome.itsme.ItsmeOidcUserService;
import com.example.voltahome.itsme.JdbcAuthorizationRequestRepository;
import com.example.voltahome.itsme.NoOpAuthorizedClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
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
            // No cookie is set or read anywhere (README: "Hand-over"): a forged cross-site request carries no
            // credential. The itsme callbacks are GET navigations protected by "state"; the hand-over call is protected
            // by the one-time code and the SPA's verifier. Keep your application's existing CSRF setup.
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/login-error", "/confirmation-error", "/error").permitAll()
                .requestMatchers("/.well-known/jwks.json").permitAll()                 // itsme reads our public keys
                .requestMatchers("/itsme/login/handover").permitAll()                  // code + verifier
                .requestMatchers("/itsme/confirmation/callback").permitAll()           // "state"; executes nothing
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                // README: "Hand-over". Errors at the start of the login go to the login error page, like the others
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                    @Override
                    public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler(failureHandler);
                        return filter;
                    }
                })
                // README: "Hand-over". Keeps the hand-over verifier of the login that just ended
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                    @Override
                    public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationResultConverter(ItsmeLoginAuthentication::from);
                        return filter;
                    }
                })
                .authorizationEndpoint(e -> e
                    .authorizationRequestResolver(itsmeAuthorizationRequestResolver)
                    .authorizationRequestRepository(authorizationRequestRepository))
                .tokenEndpoint(t -> t.accessTokenResponseClient(itsmeTokenResponseClient))
                .userInfoEndpoint(u -> u.oidcUserService(itsmeOidcUserService))
                .authorizedClientRepository(new NoOpAuthorizedClientRepository())
                .successHandler(successHandler)
                .failureHandler(failureHandler));
        // The application's own API authentication (its own token, issued by LoginHandover) is out of scope of the
        // sample; the demo stand-in is demo/DemoSecurityConfig.
        return http.build();
    }
}
