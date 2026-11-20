package com.example.voltahome.itsme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Login in progress". Stores the login in progress (state, nonce, code_verifier) in the database,
 * so that the callback can be handled by any instance of a stateless backend.
 * Read + checked delete makes each stored request single-use, without database-specific SQL.
 */
@Component
public class JpaAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final Duration TTL = Duration.ofMinutes(5);

    private final StoredAuthorizationRequestRepository requests;
    private final Clock clock;

    public JpaAuthorizationRequestRepository(StoredAuthorizationRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest r,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (r == null) {
            return;
        }
        Instant now = clock.instant();
        requests.save(new StoredAuthorizationRequest(
                r.getState(),
                r.getAttribute(OAuth2ParameterNames.REGISTRATION_ID),
                r.getAuthorizationUri(),
                r.getClientId(),
                r.getRedirectUri(),
                String.join(" ", r.getScopes()),
                r.getAttribute(OidcParameterNames.NONCE),        // raw nonce; Spring hashes it to compare
                r.getAttribute(PkceParameterNames.CODE_VERIFIER),
                now,
                now.plus(TTL)));
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        return state == null ? null : find(state).orElse(null);
    }

    @Override
    @Transactional
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        Optional<OAuth2AuthorizationRequest> found = find(state);
        if (found.isEmpty()) {
            return null;
        }
        int deleted = requests.deleteByState(state);
        return deleted == 1 ? found.get() : null;   // 0 = already consumed by a concurrent callback
    }

    /** Removes abandoned logins. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        requests.deleteExpired(clock.instant());
    }

    private Optional<OAuth2AuthorizationRequest> find(String state) {
        return requests.findById(state)
                .filter(stored -> stored.isValidAt(clock.instant()))
                .map(stored -> OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri(stored.authorizationUri())
                        .clientId(stored.clientId())
                        .redirectUri(stored.redirectUri())
                        .scopes(new LinkedHashSet<>(List.of(stored.scopes().split(" "))))
                        .state(stored.state())
                        .attributes(a -> {
                            a.put(OAuth2ParameterNames.REGISTRATION_ID, stored.registrationId());
                            a.put(OidcParameterNames.NONCE, stored.nonce());
                            a.put(PkceParameterNames.CODE_VERIFIER, stored.codeVerifier());
                        })
                        .build());
    }
}
