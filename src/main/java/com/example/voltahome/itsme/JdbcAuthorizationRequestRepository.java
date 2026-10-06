package com.example.voltahome.itsme;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
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
public class JdbcAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final Duration TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcAuthorizationRequestRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest r,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (r == null) {
            return;
        }
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO itsme_authorization_request
                  (state, registration_id, authorization_uri, client_id, redirect_uri, scopes,
                   nonce, code_verifier, created_at, expires_at)
                VALUES (:state, :registrationId, :authorizationUri, :clientId, :redirectUri, :scopes,
                        :nonce, :codeVerifier, :createdAt, :expiresAt)
                """)
                .param("state", r.getState())
                .param("registrationId", r.getAttribute(OAuth2ParameterNames.REGISTRATION_ID))
                .param("authorizationUri", r.getAuthorizationUri())
                .param("clientId", r.getClientId())
                .param("redirectUri", r.getRedirectUri())
                .param("scopes", String.join(" ", r.getScopes()))
                .param("nonce", r.getAttribute(OidcParameterNames.NONCE))  // raw nonce; Spring hashes it to compare
                .param("codeVerifier", r.getAttribute(PkceParameterNames.CODE_VERIFIER))
                .param("createdAt", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(TTL)))
                .update();
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
        int deleted = jdbc.sql("DELETE FROM itsme_authorization_request WHERE state = :state")
                .param("state", state)
                .update();
        return deleted == 1 ? found.get() : null;   // 0 = already consumed by a concurrent callback
    }

    /** Removes abandoned logins. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        jdbc.sql("DELETE FROM itsme_authorization_request WHERE expires_at < :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private Optional<OAuth2AuthorizationRequest> find(String state) {
        return jdbc.sql("""
                SELECT * FROM itsme_authorization_request
                WHERE state = :state AND expires_at > :now
                """)
                .param("state", state)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> {
                    // Read all columns first: the attributes(...) consumer cannot throw SQLException
                    String registrationId = rs.getString("registration_id");
                    String nonce = rs.getString("nonce");
                    String codeVerifier = rs.getString("code_verifier");
                    return OAuth2AuthorizationRequest.authorizationCode()
                            .authorizationUri(rs.getString("authorization_uri"))
                            .clientId(rs.getString("client_id"))
                            .redirectUri(rs.getString("redirect_uri"))
                            .scopes(new LinkedHashSet<>(List.of(rs.getString("scopes").split(" "))))
                            .state(rs.getString("state"))
                            .attributes(a -> {
                                a.put(OAuth2ParameterNames.REGISTRATION_ID, registrationId);
                                a.put(OidcParameterNames.NONCE, nonce);
                                a.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
                            })
                            .build();
                })
                .optional();
    }
}
