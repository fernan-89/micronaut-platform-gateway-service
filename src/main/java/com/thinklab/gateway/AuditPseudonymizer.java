package com.thinklab.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Turns a personal identifier (an email) into a stable pseudonym that can be correlated across entries but cannot be
 * reversed or dictionary-attacked without the key: HMAC-SHA256, truncated to 128 bits. A plain unkeyed hash of an email
 * is not a pseudonym - it can be brute-forced from a list of candidate addresses.
 *
 * <p>Destroying the key makes every pseudonym ever issued unlinkable to a person, which is how an immutable ledger can
 * still honour an erasure request (LGPD art. 18, GDPR art. 17) without rewriting a hash chain.
 */
final class AuditPseudonymizer {

    static final String UNKEYED = "unkeyed";
    private static final String ALGORITHM = "HmacSHA256";

    private AuditPseudonymizer() {
    }

    /** The pseudonym of {@code identifier} under {@code key}, or {@value #UNKEYED} when no key is configured. */
    static String of(String key, String identifier) {
        if (key == null || key.isBlank()) {
            return UNKEYED;
        }
        return hmac(ALGORITHM, key, identifier.trim().toLowerCase(Locale.ROOT));
    }

    /** The algorithm is a parameter only so the impossible failure path is testable. */
    static String hmac(String algorithm, String key, String value) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " is required by the JVM specification.", e);
        }
    }
}
