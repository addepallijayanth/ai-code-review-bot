package com.example.reviewbot.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/** An RSA key pair generated per test run, so no private key is ever committed. */
public final class TestKeys {

    private static final KeyPair KEY_PAIR = generate();

    private TestKeys() {
    }

    public static KeyPair keyPair() {
        return KEY_PAIR;
    }

    /** PKCS#8 PEM ({@code BEGIN PRIVATE KEY}). */
    public static String pkcs8Pem() {
        return pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
    }

    /** PKCS#1 PEM ({@code BEGIN RSA PRIVATE KEY}), the format GitHub hands out. */
    public static String pkcs1Pem() {
        return pem("RSA PRIVATE KEY", pkcs1(KEY_PAIR.getPrivate().getEncoded()));
    }

    /** Extracts the RSAPrivateKey structure from the OCTET STRING at the end of a PKCS#8 PrivateKeyInfo. */
    static byte[] pkcs1(byte[] pkcs8) {
        int index = 0;
        index = skipHeader(pkcs8, index);          // outer SEQUENCE
        index = skipElement(pkcs8, index);         // version INTEGER
        index = skipElement(pkcs8, index);         // AlgorithmIdentifier SEQUENCE
        int[] octets = readHeader(pkcs8, index);   // privateKey OCTET STRING
        return Arrays.copyOfRange(pkcs8, octets[0], octets[0] + octets[1]);
    }

    private static int skipHeader(byte[] der, int index) {
        return readHeader(der, index)[0];
    }

    private static int skipElement(byte[] der, int index) {
        int[] header = readHeader(der, index);
        return header[0] + header[1];
    }

    /** Returns {contentStart, contentLength} of the element starting at {@code index}. */
    private static int[] readHeader(byte[] der, int index) {
        int lengthByte = der[index + 1] & 0xff;
        if (lengthByte < 0x80) {
            return new int[] {index + 2, lengthByte};
        }
        int count = lengthByte & 0x7f;
        int length = 0;
        for (int i = 0; i < count; i++) {
            length = (length << 8) | (der[index + 2 + i] & 0xff);
        }
        return new int[] {index + 2 + count, length};
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
