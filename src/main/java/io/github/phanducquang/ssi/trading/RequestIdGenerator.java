package io.github.phanducquang.ssi.trading;

import java.security.SecureRandom;

final class RequestIdGenerator {
    static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final int SIZE = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestIdGenerator() {
    }

    static String generate() {
        StringBuilder result = new StringBuilder(SIZE);
        for (int i = 0; i < SIZE; i++) {
            result.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }
}
