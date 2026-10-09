package com.example.gateway;

import java.security.SecureRandom;
import java.util.Base64;

public final class TestJwtSecrets {

    private TestJwtSecrets() {
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
