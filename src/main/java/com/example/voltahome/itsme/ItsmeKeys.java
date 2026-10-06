package com.example.voltahome.itsme;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * README: "Keys". The application owns two RSA key pairs (itsme supports RSA only):
 * - signing (RS256): signs the Request Object and the client assertion; itsme verifies with our public key;
 * - encryption (RSA-OAEP-256): itsme encrypts ID tokens with our public key; we decrypt with the private key.
 * Private keys never leave this class. Public keys are published by JwksController.
 * With the client secret method, there are no key pairs.
 */
@Component
public class ItsmeKeys {

    private static final Logger log = LoggerFactory.getLogger(ItsmeKeys.class);

    private final RSAKey signingKey;
    private final List<RSAKey> encryptionKeys;   // more than one during a key rotation

    public ItsmeKeys(ItsmeProperties properties) throws Exception {
        ItsmeProperties.Keys k = properties.keys();
        if (!properties.usesKeyPairs()) {
            // Client secret method: no key pair, nothing to publish
            this.signingKey = null;
            this.encryptionKeys = List.of();
            return;
        }
        if (k.ephemeral()) {
            requireLocalEndpoints(properties);
            this.signingKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).keyID("voltahome-sig-ephemeral").generate();
            this.encryptionKeys = List.of(new RSAKeyGenerator(2048).keyUse(KeyUse.ENCRYPTION)
                    .algorithm(JWEAlgorithm.RSA_OAEP_256).keyID("voltahome-enc-ephemeral").generate());
            log.warn("Ephemeral itsme keys generated at startup: local development and tests only");
            return;
        }
        if (k.keystore() == null || k.keystore().isBlank()) {
            throw new IllegalStateException("itsme.keys.keystore is not configured. "
                    + "For local development, start the application with the 'local' profile.");
        }
        char[] password = k.keystorePassword().toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        Resource resource = new DefaultResourceLoader().getResource(k.keystore());
        try (InputStream in = resource.getInputStream()) {
            keyStore.load(in, password);
        }
        this.signingKey = new RSAKey.Builder(RSAKey.load(keyStore, k.signingAlias(), password))
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID(k.signingAlias()).build();
        List<RSAKey> enc = new ArrayList<>();
        for (String alias : k.encryptionAliases()) {
            enc.add(new RSAKey.Builder(RSAKey.load(keyStore, alias, password))
                    .keyUse(KeyUse.ENCRYPTION).algorithm(JWEAlgorithm.RSA_OAEP_256).keyID(alias).build());
        }
        this.encryptionKeys = List.copyOf(enc);
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public List<RSAKey> encryptionKeys() {
        return encryptionKeys;
    }

    /** Private encryption keys, for the ID token decoder. */
    public JWKSet encryptionKeySet() {
        return new JWKSet(new ArrayList<JWK>(encryptionKeys));
    }

    /** Public keys only: safe to publish. */
    public JWKSet publicJwkSet() {
        List<JWK> all = new ArrayList<>(encryptionKeys);
        if (signingKey != null) {
            all.add(signingKey);
        }
        return new JWKSet(all).toPublicJWKSet();
    }

    private static void requireLocalEndpoints(ItsmeProperties properties) {
        String issuer = properties.endpoints().issuer();
        if (!issuer.startsWith("http://localhost:") && !issuer.startsWith("http://127.0.0.1:")) {
            throw new IllegalStateException("Ephemeral keys are only allowed with localhost endpoints. Issuer was: " + issuer);
        }
    }
}
