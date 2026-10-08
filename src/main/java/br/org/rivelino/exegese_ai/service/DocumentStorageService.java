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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Stores the original uploaded PDFs under {@code exegese.upload-dir}, named by their SHA-256
 * ({@code <sha256>.pdf}), so documents can be processed asynchronously and re-ingested later.
 * <p>
 * The upload is streamed to a temporary file in the same directory while its digest is computed (it is never
 * held fully in memory) and only accepted when it starts with the {@code %PDF-} signature. The client file name
 * is never used to build a path: it is only sanitized for display. Every resolved path is normalized and must
 * stay inside the upload directory.
 *
 * @author Rivelino Patrício
 */
@Service
public class DocumentStorageService {

    /** PDF file signature (magic bytes) every accepted upload must start with. */
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    /** Control and invisible formatting characters (e.g. bidi overrides) removed from display names. */
    private static final Pattern UNSAFE_DISPLAY_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Maximum length of the original file name kept for display. */
    public static final int MAX_FILE_NAME_LENGTH = 200;
    /** Maximum length of a document title (exegese_document.title is VARCHAR(255)). */
    public static final int MAX_TITLE_LENGTH = 255;
    private static final String DEFAULT_FILE_NAME = "document.pdf";

    /**
     * An original PDF stored in the upload directory.
     *
     * @param sha256 Lower-case hexadecimal SHA-256 of the file content
     * @param storagePath Path relative to the upload directory ({@code <sha256>.pdf}), as stored in the database
     * @param size File size in bytes
     */
    public record StoredPdf(String sha256, String storagePath, long size) {}

    private final Path uploadDir;

    public DocumentStorageService(@Value("${exegese.upload-dir:./uploads}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * @return The absolute, normalized upload directory
     */
    public Path uploadDir() {
        return uploadDir;
    }

    /**
     * Streams an upload to the upload directory as {@code <sha256>.pdf}.
     *
     * @param input Upload content (not closed)
     * @return The stored file
     * @throws DocumentRejectedException when the content does not start with {@code %PDF-}
     * @throws IOException when the upload directory cannot be written
     */
    public StoredPdf store(InputStream input) throws IOException {
        Files.createDirectories(uploadDir);
        Path temp = Files.createTempFile(uploadDir, ".upload-", ".tmp");
        try {
            MessageDigest digest = newSha256();
            long size;
            try (OutputStream out = Files.newOutputStream(temp)) {
                DigestInputStream source = new DigestInputStream(input, digest);
                byte[] signature = source.readNBytes(PDF_SIGNATURE.length);
                if (!Arrays.equals(signature, PDF_SIGNATURE)) {
                    throw DocumentRejectedException.notPdf();
                }
                out.write(signature);
                size = signature.length + source.transferTo(out);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            String storagePath = sha256 + ".pdf";
            Path target = resolveInsideUploadDir(storagePath);
            // Same name = same content: an existing file with this hash is kept (it may be open by a worker)
            if (!Files.isRegularFile(target)) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return new StoredPdf(sha256, storagePath, size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Stores in-memory PDF content (tests and small programmatic ingestions).
     *
     * @see #store(InputStream)
     */
    public StoredPdf store(byte[] content) throws IOException {
        return store(new ByteArrayInputStream(content));
    }

    /**
     * Resolves the stored original of a document.
     *
     * @param storagePath Value of {@code exegese_document.storage_path} ({@code <sha256>.pdf})
     * @return The existing file inside the upload directory
     * @throws DocumentRejectedException when the path is not a stored PDF name or the file does not exist
     *                                   (e.g. documents ingested before the originals were kept)
     */
    public Path resolve(String storagePath) {
        if (storagePath == null || !storagePath.endsWith(".pdf")
                || !SHA256_HEX.matcher(storagePath.substring(0, storagePath.length() - 4)).matches()) {
            throw DocumentRejectedException.fileUnavailable();
        }
        Path file = resolveInsideUploadDir(storagePath);
        if (!Files.isRegularFile(file)) {
            throw DocumentRejectedException.fileUnavailable();
        }
        return file;
    }

    private Path resolveInsideUploadDir(String fileName) {
        Path resolved = uploadDir.resolve(fileName).normalize();
        if (!resolved.startsWith(uploadDir) || resolved.equals(uploadDir)) {
            throw new IllegalArgumentException("Resolved upload path escapes the upload directory");
        }
        return resolved;
    }

    /**
     * Sanitizes a client-supplied file name for display only: directory components, control and invisible
     * formatting characters are removed, whitespace is collapsed and the length is limited.
     *
     * @param originalFileName Name sent by the browser (may be null or contain a path)
     * @return A non-blank display name of at most {@link #MAX_FILE_NAME_LENGTH} characters
     */
    public static String sanitizeFileName(String originalFileName) {
        String name = originalFileName == null ? "" : originalFileName;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = cleanDisplayText(name);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return DEFAULT_FILE_NAME;
        }
        return truncate(name, MAX_FILE_NAME_LENGTH);
    }

    /**
     * Sanitizes a document title: control and invisible formatting characters are removed, whitespace is
     * collapsed and the length is limited to the column size.
     *
     * @param title Title typed by the operator (may be null)
     * @param fallback Title used when {@code title} is blank (e.g. the sanitized file name)
     * @return A non-blank title of at most {@link #MAX_TITLE_LENGTH} characters
     */
    public static String sanitizeTitle(String title, String fallback) {
        String cleaned = cleanDisplayText(title == null ? "" : title);
        if (cleaned.isEmpty()) {
            cleaned = cleanDisplayText(fallback == null ? "" : fallback);
        }
        return cleaned.isEmpty() ? DEFAULT_FILE_NAME : truncate(cleaned, MAX_TITLE_LENGTH);
    }

    private static String cleanDisplayText(String text) {
        String cleaned = UNSAFE_DISPLAY_CHARS.matcher(text).replaceAll(" ");
        return WHITESPACE_RUN.matcher(cleaned).replaceAll(" ").trim();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = maxLength;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).trim();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
