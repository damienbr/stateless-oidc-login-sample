package com.example.voltahome.itsme;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * README: "ID token validation". Without a Request Object, acr_values can be removed from the browser URL:
 * this check on the ID token is the guarantee that the itsme code was used.
 * Open point: confirm in E2E that itsme returns the "acr" claim.
 */
public final class AcrValidator implements OAuth2TokenValidator<Jwt> {

    private final String requiredAcr;

    public AcrValidator(String requiredAcr) {
        this.requiredAcr = requiredAcr;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return requiredAcr.equals(jwt.getClaimAsString("acr"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_acr", "Required authentication level not met", null));
    }
}
