package com.example.voltahome.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * DEMO ONLY. Filter chain of the demo API (/api/**), used by the SPA: a request is authenticated by the demo bearer
 * token (DemoToken), never by the itsme login itself. Evaluated before the main filter chain (security/SecurityConfig).
 */
@Configuration
class DemoSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(HttpSecurity http, DemoToken token) throws Exception {
        http.securityMatcher("/api/**")
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(rc -> rc.requestCache(new NullRequestCache()))
                // The token travels in the Authorization header, set by the SPA's code, and no cookie exists: a forged
                // cross-site request carries no credential, so no CSRF token is needed.
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Missing, forged or expired token: 401 with WWW-Authenticate: Bearer
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.decoder(token.decoder())));
        return http.build();
    }
}
