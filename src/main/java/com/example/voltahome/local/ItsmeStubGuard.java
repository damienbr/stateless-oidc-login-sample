package com.example.voltahome.local;

import com.example.voltahome.itsme.ItsmeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY ("local" profile). Stops the application at startup if the itsme stub would run with
 * endpoints that are not localhost: the stub must never be used anywhere else than on a developer machine.
 */
@Component
@Profile("local")
class ItsmeStubGuard {

    private static final Logger log = LoggerFactory.getLogger(ItsmeStubGuard.class);

    ItsmeStubGuard(ItsmeProperties properties) {
        String issuer = properties.endpoints().issuer();
        if (!issuer.startsWith("http://localhost:") && !issuer.startsWith("http://127.0.0.1:")) {
            throw new IllegalStateException(
                    "The 'local' profile serves an itsme STUB and only runs with localhost endpoints. Issuer was: " + issuer);
        }
        log.warn("LOCAL PROFILE ACTIVE: itsme stub served at /itsme-stub — never use this profile in a shared environment");
    }
}
