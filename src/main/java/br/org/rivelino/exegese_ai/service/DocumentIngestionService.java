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

import br.org.rivelino.exegese_ai.domain.dto.RawChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.segmentation.SegmentationStrategy;
import br.org.rivelino.exegese_ai.service.segmentation.SegmentationStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Service orchestrating high-fidelity document ingestion, extraction, chunking, and idempotent indexing.
 * <p>
 * Ingestion has two phases:
 * <ol>
 *   <li><b>Registration</b> (request thread, short transaction): the original PDF is streamed to
 *       {@code exegese.upload-dir} as {@code <sha256>.pdf} (only files starting with {@code %PDF-} are accepted)
 *       and the document is saved with status {@code PROCESSING}. A document already {@code INDEXED} (same
 *       SHA-256) is reused; a {@code FAILED} one is reset and processed again.</li>
 *   <li><b>Processing</b> (background virtual thread for uploads, see {@link DocumentIngestionExecutor}): text
 *       extraction (page limit {@code exegese.ingestion.max-pages}), segmentation and embeddings run outside any
 *       database transaction; the chunks and the {@code INDEXED} status are then written in one short
 *       transaction.</li>
 * </ol>
 * When processing fails, the {@code FAILED} status and a generic error message (no SQL or exception detail;
 * those stay in the log, linked by an error reference) are written in a separate {@code REQUIRES_NEW}
 * transaction, so a rollback of the chunk transaction never erases them. Embeddings are computed and validated
 * before the first chunk is written, so no chunk (and never a zero vector) is stored for a failed document.
 * Chunk hashes are unique per document: the same text in two documents is indexed in both.
 *
 * @author Rivelino Patrício
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";

    /** Generic error stored when the embedding provider has no credentials (operator-actionable, no detail). */
    static final String ERROR_EMBEDDING_NOT_CONFIGURED = "Embedding provider not configured (GEMINI_API_KEY)";
    static final String ERROR_INTERRUPTED =
            "Processing was interrupted by an application shutdown; upload the file again";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    /**
     * How an upload was handled.
     */
    public enum UploadState {
        /** Registered with status PROCESSING; extraction and indexing continue in the background. */
        QUEUED,
        /** The same file (SHA-256) is already indexed: nothing to do. */
        ALREADY_INDEXED,
        /** The same file (SHA-256) is already being processed. */
        ALREADY_PROCESSING
    }

    /**
     * Result of {@link #submitDocument}.
     *
     * @param document The registered (or existing) document
     * @param state How the upload was handled
     */
    public record UploadOutcome(ExegeseDocument document, UploadState state) {}

    private final ExegeseDocumentRepository documentRepository;
    private final ExegeseChunkRepository chunkRepository;
    private final ExegeseSubjectRepository subjectRepository;
    private final PdfTextExtractor pdfTextExtractor;
    private final SegmentationStrategyFactory strategyFactory;
    private final EmbeddingService embeddingService;
    private final DocumentStorageService storageService;
    private final DocumentIngestionExecutor ingestionExecutor;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate statusTransactionTemplate;
    private final boolean isPostgres;

    public DocumentIngestionService(ExegeseDocumentRepository documentRepository,
                                  ExegeseChunkRepository chunkRepository,
                                  ExegeseSubjectRepository subjectRepository,
                                  PdfTextExtractor pdfTextExtractor,
                                  SegmentationStrategyFactory strategyFactory,
                                  EmbeddingService embeddingService,
                                  DocumentStorageService storageService,
                                  DocumentIngestionExecutor ingestionExecutor,
                                  JsonMapper jsonMapper,
                                  JdbcTemplate jdbcTemplate,
                                  PlatformTransactionManager transactionManager,
                                  DataSource dataSource) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.subjectRepository = subjectRepository;
        this.pdfTextExtractor = pdfTextExtractor;
        this.strategyFactory = strategyFactory;
        this.embeddingService = embeddingService;
        this.storageService = storageService;
        this.ingestionExecutor = ingestionExecutor;
        this.jsonMapper = jsonMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.statusTransactionTemplate = new TransactionTemplate(transactionManager);
        this.statusTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isPostgres = checkPostgreSql(dataSource);
    }

    /**
     * Accepts an upload: stores the original PDF, registers the document with status {@code PROCESSING} and
     * schedules extraction, segmentation and embeddings on the background executor (after the registration
     * commits). Returns as soon as the file is stored; the catalog shows the final status.
     *
     * @param title Document title (sanitized; the file name is used when blank)
     * @param fileName Original client file name (sanitized, for display only)
     * @param input Upload content (not closed)
     * @param subjectIds Associated subject IDs
     * @param strategyType Desired chunking strategy
     * @return The registered document and how the upload was handled
     * @throws DocumentRejectedException when the file is not a PDF
     * @throws IOException when the file cannot be stored
     */
    public UploadOutcome submitDocument(String title,
                                        String fileName,
                                        InputStream input,
                                        List<UUID> subjectIds,
                                        SegmentationStrategyType strategyType) throws IOException {
        DocumentStorageService.StoredPdf stored = storageService.store(input);
        UploadOutcome outcome = register(title, fileName, stored, subjectIds, strategyType);
        if (outcome.state() == UploadState.QUEUED) {
            UUID documentId = outcome.document().getId();
            runAfterCommit(() -> dispatch(documentId));
        }
        return outcome;
    }

    /**
     * Ingests a PDF document synchronously (same phases as an upload, processed in the calling thread).
     *
     * @param title Document title
     * @param fileName Original file name
     * @param fileBytes Raw PDF byte content
     * @param subjectIds Associated subject IDs
     * @param strategyType Desired chunking strategy
     * @return The persisted ExegeseDocument entity
     * @throws IOException If the file cannot be stored or the PDF cannot be read (document kept as FAILED)
     * @throws DocumentRejectedException If the file is not a PDF or exceeds the page limit
     * @throws EmbeddingException If chunk embeddings cannot be generated (document kept as FAILED)
     */
    public ExegeseDocument ingestDocument(String title,
                                          String fileName,
                                          byte[] fileBytes,
                                          List<UUID> subjectIds,
                                          SegmentationStrategyType strategyType) throws IOException {
        DocumentStorageService.StoredPdf stored = storageService.store(fileBytes);
        UploadOutcome outcome = register(title, fileName, stored, subjectIds, strategyType);
        if (outcome.state() != UploadState.QUEUED) {
            return outcome.document();
        }
        UUID documentId = outcome.document().getId();
        process(documentId);
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document disappeared during ingestion: " + documentId));
    }

    /**
     * Marks documents left in {@code PROCESSING} by a previous run (shutdown or crash during ingestion) as
     * {@code FAILED}, so they can be uploaded again.
     *
     * @return Number of documents marked as failed
     */
    public int failInterruptedIngestions() {
        Integer updated = transactionTemplate.execute(status -> documentRepository.updateStatus(
                STATUS_PROCESSING, STATUS_FAILED, ERROR_INTERRUPTED, Instant.now()));
        return updated != null ? updated : 0;
    }

    private UploadOutcome register(String title,
                                   String fileName,
                                   DocumentStorageService.StoredPdf stored,
                                   List<UUID> subjectIds,
                                   SegmentationStrategyType strategyType) {
        String displayFileName = DocumentStorageService.sanitizeFileName(fileName);
        String docTitle = DocumentStorageService.sanitizeTitle(title, displayFileName);
        SegmentationStrategyType effectiveStrategy = strategyType != null ? strategyType : SegmentationStrategyType.STRUCTURED_QA;

        return transactionTemplate.execute(status -> {
            ExegeseDocument doc;
            Optional<ExegeseDocument> existingDoc = documentRepository.findByFileHashSha256(stored.sha256());
            if (existingDoc.isPresent()) {
                ExegeseDocument existing = existingDoc.get();
                if (STATUS_INDEXED.equals(existing.getStatus())) {
                    log.info("Document with SHA-256 {} already indexed (ID: {}). Reusing existing record.",
                            stored.sha256(), existing.getId());
                    return new UploadOutcome(existing, UploadState.ALREADY_INDEXED);
                }
                if (STATUS_PROCESSING.equals(existing.getStatus())) {
                    log.info("Document with SHA-256 {} is already being processed (ID: {}).", stored.sha256(), existing.getId());
                    return new UploadOutcome(existing, UploadState.ALREADY_PROCESSING);
                }
                log.warn("Document with SHA-256 {} previously in state '{}'. Resetting it for re-indexing.",
                        stored.sha256(), existing.getStatus());
                chunkRepository.deleteByDocumentId(existing.getId());
                doc = existing;
                doc.setTitle(docTitle);
                doc.setOriginalFileName(displayFileName);
                doc.setStoragePath(stored.storagePath());
                doc.setFileSize(stored.size());
                doc.setFileType("application/pdf");
                doc.getSubjects().clear();
            } else {
                doc = new ExegeseDocument(docTitle, displayFileName, stored.storagePath(), stored.sha256(),
                        stored.size(), "application/pdf");
            }
            doc.setSegmentationStrategy(effectiveStrategy);
            doc.setStatus(STATUS_PROCESSING);
            doc.setErrorMessage(null);
            doc.setTotalPages(null);
            doc.setUpdatedAt(Instant.now());
            if (subjectIds != null && !subjectIds.isEmpty()) {
                subjectRepository.findAllById(new HashSet<>(subjectIds)).forEach(doc::addSubject);
            }
            doc = documentRepository.saveAndFlush(doc);
            log.info("Document '{}' registered for ingestion (ID: {}, SHA-256: {}, {} bytes)",
                    docTitle, doc.getId(), stored.sha256(), stored.size());
            return new UploadOutcome(doc, UploadState.QUEUED);
        });
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Called inside an outer transaction: the worker must only start once the document is committed
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void dispatch(UUID documentId) {
        try {
            ingestionExecutor.execute(() -> processInBackground(documentId));
        } catch (RejectedExecutionException e) {
            log.warn("Document {} could not be scheduled for ingestion (application shutting down)", documentId);
            markFailed(documentId, ERROR_INTERRUPTED);
        }
    }

    private void processInBackground(UUID documentId) {
        try {
            process(documentId);
        } catch (IOException | RuntimeException e) {
            // Already logged with its error reference and persisted as FAILED by process()
            log.debug("Background ingestion of document {} ended with status FAILED", documentId);
        }
    }

    /**
     * Extracts, segments, embeds and indexes a registered document. Any failure is persisted as FAILED (in a
     * separate transaction) before the exception is rethrown.
     */
    private void process(UUID documentId) throws IOException {
        ExegeseDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found for ingestion: " + documentId));
        String title = doc.getTitle();
        try {
            Path pdfFile = storageService.resolve(doc.getStoragePath());
            PdfTextExtractor.ExtractedPdf extractedPdf = pdfTextExtractor.extract(pdfFile);

            SegmentationStrategyType effectiveStrategy = doc.getSegmentationStrategy() != null
                    ? doc.getSegmentationStrategy() : SegmentationStrategyType.STRUCTURED_QA;
            SegmentationStrategy strategy = strategyFactory.getStrategy(effectiveStrategy);
            List<RawChunk> rawChunks = strategy.segment(extractedPdf.fullText(), extractedPdf.pages());
            log.info("Document '{}' segmented into {} raw chunks via strategy {}", title, rawChunks.size(), effectiveStrategy);

            List<RawChunk> newChunks = selectNewChunks(documentId, rawChunks);
            // Every vector is produced (and validated) before the first chunk is written. Outside PostgreSQL
            // they are not stored (no vector column), but computing them keeps the failure behavior identical.
            List<float[]> embeddings = embeddingService.embedAll(newChunks.stream()
                    .map(c -> EmbeddingService.chunkEmbeddingText(c.title(), c.content()))
                    .toList());

            transactionTemplate.executeWithoutResult(status -> writeChunks(documentId, newChunks, embeddings,
                    extractedPdf.totalPages()));
            log.info("Successfully indexed document '{}' with {} total chunks", title, newChunks.size());
        } catch (EmbeddingException e) {
            String reference = ErrorReference.newReference();
            log.error("Embedding generation failed for document '{}' [ref={}]: {}", title, reference, e.getMessage());
            markFailed(documentId, e.isNotConfigured()
                    ? ERROR_EMBEDDING_NOT_CONFIGURED
                    : "Embedding generation failed (reference " + reference + ")");
            throw e;
        } catch (DocumentRejectedException e) {
            log.warn("Document '{}' rejected during ingestion: {}", title, e.getMessage());
            markFailed(documentId, e.getMessage());
            throw e;
        } catch (IOException e) {
            String reference = ErrorReference.newReference();
            log.error("Failed to read PDF of document '{}' [ref={}]", title, reference, e);
            markFailed(documentId, "The file could not be read as a PDF (reference " + reference + ")");
            throw e;
        } catch (RuntimeException e) {
            String reference = ErrorReference.newReference();
            log.error("Failed to index chunks of document '{}' [ref={}]", title, reference, e);
            markFailed(documentId, "Indexing failed (reference " + reference + ")");
            throw new IllegalStateException("Chunk indexing failed for document " + documentId + " [ref=" + reference + "]", e);
        }
    }

    private void writeChunks(UUID documentId, List<RawChunk> newChunks, List<float[]> embeddings, int totalPages) {
        ExegeseDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found for ingestion: " + documentId));
        for (int i = 0; i < newChunks.size(); i++) {
            RawChunk rawChunk = newChunks.get(i);
            String metadataJson = serializeMetadata(rawChunk.metadata());

            if (isPostgres) {
                String vectorStr = EmbeddingService.toPgVector(embeddings.get(i));
                jdbcTemplate.update("""
                    INSERT INTO exegese_chunk (id, document_id, chunk_hash_sha256, sequence_number, title, content, metadata, embedding, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, cast(? as vector), CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), documentId, rawChunk.chunkHashSha256(), rawChunk.sequenceNumber(),
                        rawChunk.title(), rawChunk.content(), metadataJson, vectorStr);
            } else {
                chunkRepository.save(new ExegeseChunk(
                        doc,
                        rawChunk.chunkHashSha256(),
                        rawChunk.sequenceNumber(),
                        rawChunk.title(),
                        rawChunk.content(),
                        metadataJson
                ));
            }
        }
        doc.setTotalPages(totalPages);
        doc.setStatus(STATUS_INDEXED);
        doc.setErrorMessage(null);
        doc.setUpdatedAt(Instant.now());
        documentRepository.saveAndFlush(doc);
    }

    /**
     * Persists FAILED and a generic message in its own transaction (REQUIRES_NEW): it survives the rollback of
     * the chunk transaction and of any caller transaction. Never throws, so the original failure is reported.
     */
    private void markFailed(UUID documentId, String errorMessage) {
        String message = errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH
                ? errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH) : errorMessage;
        try {
            statusTransactionTemplate.executeWithoutResult(status ->
                    documentRepository.findById(documentId).ifPresent(doc -> {
                        doc.setStatus(STATUS_FAILED);
                        doc.setErrorMessage(message);
                        doc.setUpdatedAt(Instant.now());
                        documentRepository.saveAndFlush(doc);
                    }));
        } catch (RuntimeException e) {
            log.error("Could not persist FAILED status of document {}", documentId, e);
        }
    }

    /**
     * Chunks to index: blank chunks, repeated hashes inside the document and hashes already indexed for this
     * document are skipped. The same text in another document is indexed again (per-document uniqueness).
     */
    private List<RawChunk> selectNewChunks(UUID documentId, List<RawChunk> rawChunks) {
        Map<String, RawChunk> byHash = new LinkedHashMap<>();
        for (RawChunk rawChunk : rawChunks) {
            if (rawChunk.content() == null || rawChunk.content().isBlank()
                    || byHash.containsKey(rawChunk.chunkHashSha256())
                    || chunkRepository.existsByDocumentIdAndChunkHashSha256(documentId, rawChunk.chunkHashSha256())) {
                continue;
            }
            byHash.put(rawChunk.chunkHashSha256(), rawChunk);
        }
        return new ArrayList<>(byHash.values());
    }

    private boolean checkPostgreSql(DataSource dataSource) {
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Could not determine database product name, assuming non-postgres: {}", e.getMessage());
            return false;
        }
    }

    private String serializeMetadata(Object metadata) {
        if (metadata == null) return "{}";
        try {
            return jsonMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            log.warn("Failed to serialize chunk metadata to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
