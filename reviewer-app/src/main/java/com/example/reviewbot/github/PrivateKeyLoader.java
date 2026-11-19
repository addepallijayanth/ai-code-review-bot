package com.example.reviewbot.github;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.util.StringUtils;

/**
 * Loads the App's RSA private key. GitHub hands out PKCS#1 PEM files
 * ({@code BEGIN RSA PRIVATE KEY}), which the JDK cannot read directly, so those are wrapped into
 * a PKCS#8 structure first. PKCS#8 keys ({@code BEGIN PRIVATE KEY}) are read as they are.
 */
final class PrivateKeyLoader {

    /** DER encoding of AlgorithmIdentifier { rsaEncryption (1.2.840.113549.1.1.1), NULL }. */
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private PrivateKeyLoader() {
    }

    static PrivateKey load(String pem, String path) {
        String source;
        if (StringUtils.hasText(pem)) {
            source = pem;
        } else if (StringUtils.hasText(path)) {
            try {
                source = Files.readString(Path.of(path));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read GitHub App private key from " + path, e);
            }
        } else {
            throw new IllegalStateException(
                    "Set reviewbot.github.private-key (GITHUB_PRIVATE_KEY) or reviewbot.github.private-key-path");
        }
        return parse(source);
    }

    static PrivateKey parse(String pem) {
        // Environment variables often carry the PEM on one line with literal "\n" separators.
        String text = pem.replace("\\n", "\n").trim();
        boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
        String base64 = text
                .replaceAll("-----(BEGIN|END) (RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            byte[] pkcs8 = pkcs1 ? wrapPkcs1(der) : der;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("GitHub App private key is not a valid RSA PEM key", e);
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { version INTEGER (0), algorithm AlgorithmIdentifier, privateKey OCTET STRING }. */
    static byte[] wrapPkcs1(byte[] pkcs1) {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes(new byte[] {0x02, 0x01, 0x00});
        info.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        info.write(0x04);
        writeLength(info, pkcs1.length);
        info.writeBytes(pkcs1);

        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.write(0x30);
        writeLength(sequence, info.size());
        sequence.writeBytes(info.toByteArray());
        return sequence.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((length >>> (8 * i)) & 0xff);
        }
    }
}
