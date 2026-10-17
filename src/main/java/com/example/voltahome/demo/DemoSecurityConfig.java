package com.example.voltahome.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * DEMO ONLY. Filter chain of the protected portal: a request is authenticated by the demo session cookie,
 * never by the itsme login itself. Evaluated before the main filter chain (security/SecurityConfig).
 */
@Configuration
class DemoSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain portalFilterChain(HttpSecurity http, DemoSession session) throws Exception {
        http.securityMatcher("/portal", "/portal/**")
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(rc -> rc.requestCache(new NullRequestCache()))
                // The portal only serves GET pages. A portal with forms needs CSRF protection.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new DemoSessionFilter(session), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Not logged in, or session expired: back to the home page and its login button
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, exception) -> response.sendRedirect("/")));
        return http.build();
    }
}
