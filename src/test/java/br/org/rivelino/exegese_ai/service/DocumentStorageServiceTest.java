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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DocumentStorageService}: {@code %PDF-} signature check, {@code <sha256>.pdf} naming,
 * confinement of resolved paths to the upload directory and display-name sanitization.
 *
 * @author Rivelino Patrício
 */
class DocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A PDF is stored as <sha256>.pdf inside a created upload directory")
    void testStoresPdfByHash() throws Exception {
        Path uploadDir = tempDir.resolve("nested/uploads");
        DocumentStorageService storage = new DocumentStorageService(uploadDir.toString());
        byte[] content = "%PDF-1.7\nfake body".getBytes(StandardCharsets.US_ASCII);

        DocumentStorageService.StoredPdf stored = storage.store(content);

        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(stored.sha256()).isEqualTo(expectedHash);
        assertThat(stored.storagePath()).isEqualTo(expectedHash + ".pdf");
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(storage.resolve(stored.storagePath())).isEqualTo(uploadDir.toAbsolutePath().normalize()
                .resolve(expectedHash + ".pdf")).hasBinaryContent(content);

        // Storing the same content again keeps a single file
        assertThat(storage.store(content).storagePath()).isEqualTo(stored.storagePath());
        try (Stream<Path> files = Files.list(uploadDir)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    @DisplayName("Content without the %PDF- signature is rejected and no file (nor temporary file) is left")
    void testRejectsNonPdf() throws IOException {
        DocumentStorageService storage = new DocumentStorageService(tempDir.toString());

        assertThatThrownBy(() -> storage.store("<html>%PDF-</html>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(DocumentRejectedException.Reason.NOT_PDF));
        assertThatThrownBy(() -> storage.store(new byte[]{'%', 'P'}))
                .isInstanceOf(DocumentRejectedException.class);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("Only stored <sha256>.pdf names inside the upload directory can be resolved")
    void testResolveIsConfinedToUploadDir() throws IOException {
        DocumentStorageService storage = new DocumentStorageService(tempDir.resolve("uploads").toString());
        Files.writeString(tempDir.resolve("secret.pdf"), "%PDF-secret");

        assertThatThrownBy(() -> storage.resolve("../secret.pdf")).isInstanceOf(DocumentRejectedException.class);
        assertThatThrownBy(() -> storage.resolve("/etc/passwd")).isInstanceOf(DocumentRejectedException.class);
        assertThatThrownBy(() -> storage.resolve("local://manual.pdf")).isInstanceOf(DocumentRejectedException.class);
        assertThatThrownBy(() -> storage.resolve(null)).isInstanceOf(DocumentRejectedException.class);
        assertThatThrownBy(() -> storage.resolve("a".repeat(64) + ".pdf"))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(DocumentRejectedException.Reason.FILE_UNAVAILABLE));
    }

    @Test
    @DisplayName("Client file names are reduced to a safe, bounded display name")
    void testSanitizeFileName() {
        assertThat(DocumentStorageService.sanitizeFileName("C:\\Users\\x\\..\\manual.pdf")).isEqualTo("manual.pdf");
        assertThat(DocumentStorageService.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(DocumentStorageService.sanitizeFileName("relat\u202Eorio\r\n.pdf")).isEqualTo("relat orio .pdf");
        assertThat(DocumentStorageService.sanitizeFileName("..")).isEqualTo("document.pdf");
        assertThat(DocumentStorageService.sanitizeFileName(null)).isEqualTo("document.pdf");
        assertThat(DocumentStorageService.sanitizeFileName("a".repeat(500) + ".pdf"))
                .hasSize(DocumentStorageService.MAX_FILE_NAME_LENGTH);

        assertThat(DocumentStorageService.sanitizeTitle("  Manual\tIRPF  ", "x.pdf")).isEqualTo("Manual IRPF");
        assertThat(DocumentStorageService.sanitizeTitle(" ", "manual.pdf")).isEqualTo("manual.pdf");
        assertThat(DocumentStorageService.sanitizeTitle("t".repeat(300), "x.pdf"))
                .hasSize(DocumentStorageService.MAX_TITLE_LENGTH);
    }
}
