package com.example.voltahome.itsme;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * README: "Keys". itsme downloads our public keys here: to verify our signatures (Request Object,
 * client assertion) and to encrypt ID tokens for us. itsme requirements: public HTTPS URL with an OV/EV
 * certificate, no authentication, response under one second, at least one "sig" and one "enc" key.
 */
@RestController
class JwksController {

    private final ItsmeKeys keys;
    private final ItsmeProperties properties;

    JwksController(ItsmeKeys keys, ItsmeProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> jwks() {
        if (!properties.usesKeyPairs()) {
            return ResponseEntity.notFound().build();   // client secret method: no public keys to publish
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic())   // itsme caches 30 min to 24 h
                .body(keys.publicJwkSet().toJSONObject());
    }
}
