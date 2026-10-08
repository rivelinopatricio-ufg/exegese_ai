/*******************************************************************************
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without 
 * restriction, including without limitation the rights to use, copy, modify, merge, publish, 
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or 
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS 
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR 
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN 
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * This software uses third-party components, distributed accordingly to their own licenses.
 *******************************************************************************/
package br.org.rivelino.exegese_ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Service for cryptographic operations including SHA-256 hashing and AES-256-GCM encryption.
 * <p>
 * The master key is read from {@code exegese.security.crypto-key} (environment variable
 * {@code EXEGESE_AES_SECRET}) and must be the Base64 encoding of exactly 32 random bytes
 * ({@code openssl rand -base64 32}); the application refuses to start otherwise.
 * <p>
 * Ciphertexts produced by this service use the versioned format {@code "v1:" + Base64(iv || ciphertext+tag)}.
 * Values without the version prefix were produced by the legacy scheme (hard-coded key) and remain
 * readable through {@link #decrypt(String)} until they are re-encrypted by {@link #reencryptLegacy(String)}.
 *
 * @author Rivelino Patrício
 */
@Service
public class CryptoService {

    /** Prefix identifying ciphertexts produced with the configured master key. */
    public static final String CIPHERTEXT_V1_PREFIX = "v1:";

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final int KEY_LENGTH_BYTE = 32;

    /**
     * Key material of the legacy scheme (before version prefixes existed): the UTF-8 bytes of this
     * public constant. Used for decryption of pre-existing data only, never for new ciphertexts.
     */
    private static final byte[] LEGACY_KEY_BYTES =
            Arrays.copyOf("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), KEY_LENGTH_BYTE);

    private static final String KEY_HELP = "Set EXEGESE_AES_SECRET (property exegese.security.crypto-key) to the Base64 "
            + "encoding of 32 random bytes, e.g. generated with: openssl rand -base64 32. Keep the same value across "
            + "restarts, otherwise API keys stored in the database can no longer be decrypted.";

    private final SecretKey masterKey;
    private final SecretKey legacyKey = new SecretKeySpec(LEGACY_KEY_BYTES, "AES");
    private final SecureRandom secureRandom = new SecureRandom();

    public CryptoService(@Value("${exegese.security.crypto-key:}") String base64MasterKey) {
        this.masterKey = parseMasterKey(base64MasterKey);
    }

    private static SecretKey parseMasterKey(String base64MasterKey) {
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            throw new IllegalStateException("AES master key is not configured. " + KEY_HELP);
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64MasterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AES master key is not valid Base64. " + KEY_HELP);
        }
        if (keyBytes.length != KEY_LENGTH_BYTE) {
            int length = keyBytes.length;
            Arrays.fill(keyBytes, (byte) 0);
            throw new IllegalStateException("AES master key must decode to exactly " + KEY_LENGTH_BYTE
                    + " bytes but decodes to " + length + " bytes. " + KEY_HELP);
        }
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        return key;
    }

    public String sha256(String input) {
        if (input == null) return "";
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Encrypts a secret with the configured master key.
     *
     * @return {@code "v1:" + Base64(iv || ciphertext+tag)}, or {@code null} for a blank input
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return CIPHERTEXT_V1_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String)} or by the legacy (unversioned) scheme.
     *
     * @throws IllegalStateException if the payload is malformed, was tampered with, or was encrypted with another key
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) return null;
        if (cipherText.startsWith(CIPHERTEXT_V1_PREFIX)) {
            return decryptWithKey(cipherText.substring(CIPHERTEXT_V1_PREFIX.length()), masterKey);
        }
        return decryptWithKey(cipherText, legacyKey);
    }

    /**
     * Tells whether a stored value was produced by the legacy (unversioned, hard-coded key) scheme.
     */
    public boolean isLegacyCiphertext(String cipherText) {
        return cipherText != null && !cipherText.isBlank() && !cipherText.startsWith(CIPHERTEXT_V1_PREFIX);
    }

    /**
     * Re-encrypts a legacy ciphertext with the configured master key.
     *
     * @return the new {@code v1:} ciphertext, or the input unchanged when it is not a legacy value
     * @throws IllegalStateException if the legacy value cannot be decrypted
     */
    public String reencryptLegacy(String cipherText) {
        if (!isLegacyCiphertext(cipherText)) {
            return cipherText;
        }
        return encrypt(decryptWithKey(cipherText, legacyKey));
    }

    private String decryptWithKey(String cipherTextBase64, SecretKey key) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherTextBase64);
            if (combined.length <= IV_LENGTH_BYTE) {
                throw new IllegalArgumentException("Invalid encrypted payload length");
            }

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, combined, 0, IV_LENGTH_BYTE));

            byte[] plainTextBytes = cipher.doFinal(combined, IV_LENGTH_BYTE, combined.length - IV_LENGTH_BYTE);
            return new String(plainTextBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }
}
