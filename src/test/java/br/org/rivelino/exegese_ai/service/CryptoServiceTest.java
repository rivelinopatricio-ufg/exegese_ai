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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CryptoService}: versioned AES-256-GCM round trip, legacy ciphertext
 * compatibility, fail-fast master key validation and tamper detection.
 *
 * @author Rivelino Patrício
 */
class CryptoServiceTest {

    /** Base64 of 32 bytes, matching the test profile key. */
    static final String TEST_KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final CryptoService cryptoService = new CryptoService(TEST_KEY);

    /**
     * Reproduces the pre-v1 scheme (UTF-8 bytes of the public constant as key, no version prefix)
     * to create fixtures of data stored by earlier releases.
     */
    static String legacyEncrypt(String plainText) throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    @Test
    @DisplayName("Encrypt produces a v1 ciphertext with a random IV that decrypts back to the plain text")
    void testRoundTrip() {
        String first = cryptoService.encrypt("sk-secret-api-key");
        String second = cryptoService.encrypt("sk-secret-api-key");

        assertThat(first).startsWith(CryptoService.CIPHERTEXT_V1_PREFIX).doesNotContain("sk-secret-api-key");
        assertThat(first).isNotEqualTo(second);
        assertThat(cryptoService.decrypt(first)).isEqualTo("sk-secret-api-key");
        assertThat(cryptoService.decrypt(second)).isEqualTo("sk-secret-api-key");
        assertThat(cryptoService.isLegacyCiphertext(first)).isFalse();
        assertThat(cryptoService.encrypt("  ")).isNull();
        assertThat(cryptoService.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("A v1 ciphertext cannot be decrypted with a different master key")
    void testWrongKeyRejected() {
        String cipherText = cryptoService.encrypt("sk-secret-api-key");

        assertThatThrownBy(() -> new CryptoService(OTHER_KEY).decrypt(cipherText))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Legacy ciphertexts (no version prefix) remain readable and are re-encrypted with the master key")
    void testLegacyDecryptAndReencrypt() throws Exception {
        String legacy = legacyEncrypt("legacy-provider-key");

        assertThat(cryptoService.isLegacyCiphertext(legacy)).isTrue();
        assertThat(cryptoService.decrypt(legacy)).isEqualTo("legacy-provider-key");

        String migrated = cryptoService.reencryptLegacy(legacy);
        assertThat(migrated).startsWith(CryptoService.CIPHERTEXT_V1_PREFIX);
        assertThat(cryptoService.decrypt(migrated)).isEqualTo("legacy-provider-key");
        // Idempotent: an already migrated value is returned unchanged
        assertThat(cryptoService.reencryptLegacy(migrated)).isEqualTo(migrated);
        // The new ciphertext is bound to the configured key, not to the public legacy key
        assertThatThrownBy(() -> new CryptoService(OTHER_KEY).decrypt(migrated))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Startup fails fast when the master key is missing, not Base64 or not 32 bytes long")
    void testFailFastOnInvalidKey() {
        assertThatThrownBy(() -> new CryptoService(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXEGESE_AES_SECRET")
                .hasMessageContaining("openssl rand -base64 32");
        assertThatThrownBy(() -> new CryptoService("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> new CryptoService("not*base64!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
        // 32 hex characters (the format generated by older installers) decode to only 24 bytes
        assertThatThrownBy(() -> new CryptoService("0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> new CryptoService(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decodes to 16 bytes");
    }

    @Test
    @DisplayName("Tampered ciphertexts are rejected by the GCM authentication tag")
    void testTamperDetection() {
        String cipherText = cryptoService.encrypt("sk-secret-api-key");
        byte[] payload = Base64.getDecoder().decode(cipherText.substring(CryptoService.CIPHERTEXT_V1_PREFIX.length()));
        payload[payload.length - 1] ^= 0x01;
        String tampered = CryptoService.CIPHERTEXT_V1_PREFIX + Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cryptoService.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decrypt secret");
        assertThatThrownBy(() -> cryptoService.decrypt(CryptoService.CIPHERTEXT_V1_PREFIX + "AAAA"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cryptoService.decrypt("v1:%%%"))
                .isInstanceOf(IllegalStateException.class);
    }
}
