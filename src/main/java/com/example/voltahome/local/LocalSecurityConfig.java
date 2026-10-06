package com.example.voltahome.local;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/** LOCAL DEVELOPMENT ONLY. Opens /itsme-stub/** without authentication; evaluated before the main filter chain. */
@Configuration
@Profile("local")
class LocalSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain itsmeStubFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/itsme-stub/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(rc -> rc.requestCache(new NullRequestCache()));
        return http.build();
    }
}
