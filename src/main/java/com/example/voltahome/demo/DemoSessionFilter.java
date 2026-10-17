package com.example.voltahome.demo;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DEMO ONLY. On each portal request, reads the demo session cookie and, if it is valid, marks the request as
 * authenticated. Nothing is stored between requests: the cookie is checked every time.
 * Not a @Component on purpose: Spring Boot would also register it as a servlet filter for every URL.
 */
class DemoSessionFilter extends OncePerRequestFilter {

    private final DemoSession session;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    DemoSessionFilter(DemoSession session) {
        this.session = session;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        session.read(request).ifPresent(customer -> {
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                    customer, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
            contextHolder.setContext(context);
        });
        chain.doFilter(request, response);
    }
}
