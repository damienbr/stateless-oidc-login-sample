package com.example.voltahome.itsme;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

class ItsmeCryptoTest {

    @Test
    void plainJwsIsNotAcceptedAsIdToken() {
        String jws = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln";   // signed only, not encrypted
        assertThatThrownBy(() -> ItsmeCrypto.requireNestedSignedJwt(jws)).isInstanceOf(BadJwtException.class);
    }
}
