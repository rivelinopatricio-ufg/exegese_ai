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
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.segmentation.SegmentationStrategy;
import br.org.rivelino.exegese_ai.service.segmentation.SegmentationStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating high-fidelity document ingestion, extraction, chunking, and idempotent indexing.
 * <p>
 * Embeddings for all new chunks are computed before any chunk is written. When they cannot be produced
 * (no Gemini key, provider failure, wrong dimension) the document is kept with status {@code FAILED} and an
 * {@link EmbeddingException} is raised; the transaction is not rolled back for that exception so the
 * status survives, and no chunk (and never a zero vector) is stored.
 *
 * @author Rivelino Patrício
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final ExegeseDocumentRepository documentRepository;
    private final ExegeseChunkRepository chunkRepository;
    private final ExegeseSubjectRepository subjectRepository;
    private final PdfTextExtractor pdfTextExtractor;
    private final SegmentationStrategyFactory strategyFactory;
    private final CryptoService cryptoService;
    private final EmbeddingService embeddingService;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbcTemplate;
    private final boolean isPostgres;

    public DocumentIngestionService(ExegeseDocumentRepository documentRepository,
                                  ExegeseChunkRepository chunkRepository,
                                  ExegeseSubjectRepository subjectRepository,
                                  PdfTextExtractor pdfTextExtractor,
                                  SegmentationStrategyFactory strategyFactory,
                                  CryptoService cryptoService,
                                  EmbeddingService embeddingService,
                                  JsonMapper jsonMapper,
                                  JdbcTemplate jdbcTemplate,
                                  DataSource dataSource) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.subjectRepository = subjectRepository;
        this.pdfTextExtractor = pdfTextExtractor;
        this.strategyFactory = strategyFactory;
        this.cryptoService = cryptoService;
        this.embeddingService = embeddingService;
        this.jsonMapper = jsonMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.isPostgres = checkPostgreSql(dataSource);
    }

    /**
     * Ingests a PDF document from an InputStream, performing idempotent hashing and segmentation.
     *
     * @param title Document title
     * @param fileName Original file name
     * @param inputStream Document input stream
     * @param subjectIds Associated subject IDs
     * @param strategyType Desired chunking strategy
     * @return The persisted ExegeseDocument entity
     * @throws IOException If PDF reading or parsing fails
     * @throws EmbeddingException If chunk embeddings cannot be generated (document kept as FAILED)
     */
    @Transactional(noRollbackFor = EmbeddingException.class)
    public ExegeseDocument ingestDocument(String title,
                                          String fileName,
                                          InputStream inputStream,
                                          List<UUID> subjectIds,
                                          SegmentationStrategyType strategyType) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return ingestDocument(title, fileName, bytes, subjectIds, strategyType);
    }

    /**
     * Ingests a PDF document from byte array, performing idempotent hashing and segmentation.
     *
     * @param title Document title
     * @param fileName Original file name
     * @param fileBytes Raw PDF byte content
     * @param subjectIds Associated subject IDs
     * @param strategyType Desired chunking strategy
     * @return The persisted ExegeseDocument entity
     * @throws IOException If PDF reading or parsing fails
     * @throws EmbeddingException If chunk embeddings cannot be generated (document kept as FAILED)
     */
    @Transactional(noRollbackFor = EmbeddingException.class)
    public ExegeseDocument ingestDocument(String title,
                                          String fileName,
                                          byte[] fileBytes,
                                          List<UUID> subjectIds,
                                          SegmentationStrategyType strategyType) throws IOException {
        String fileHash = cryptoService.sha256(fileBytes);
        log.info("Starting ingestion of document '{}' ({}), SHA-256: {}", title, fileName, fileHash);

        Optional<ExegeseDocument> existingDoc = documentRepository.findByFileHashSha256(fileHash);
        if (existingDoc.isPresent()) {
            ExegeseDocument existing = existingDoc.get();
            if ("INDEXED".equals(existing.getStatus())) {
                log.info("Document with SHA-256 {} already indexed (ID: {}). Reusing existing record.",
                        fileHash, existing.getId());
                return existing;
            }
            log.warn("Document with SHA-256 {} previously in state '{}'. Removing incomplete record to permit re-indexing.",
                    fileHash, existing.getStatus());
            documentRepository.delete(existing);
            documentRepository.flush();
        }

        PdfTextExtractor.ExtractedPdf extractedPdf;
        try {
            extractedPdf = pdfTextExtractor.extract(fileBytes);
        } catch (IOException e) {
            log.error("Failed to extract text from PDF '{}': {}", fileName, e.getMessage(), e);
            throw e;
        }

        SegmentationStrategyType effectiveStrategy = strategyType != null ? strategyType : SegmentationStrategyType.STRUCTURED_QA;

        ExegeseDocument doc = new ExegeseDocument(
                title,
                fileName,
                "local://" + fileName,
                fileHash,
                (long) fileBytes.length,
                "application/pdf"
        );
        doc.setTotalPages(extractedPdf.totalPages());
        doc.setSegmentationStrategy(effectiveStrategy);
        doc.setStatus("PROCESSING");

        if (subjectIds != null) {
            for (UUID subjectId : subjectIds) {
                Optional<ExegeseSubject> subjectOpt = subjectRepository.findById(subjectId);
                subjectOpt.ifPresent(doc::addSubject);
            }
        }

        doc = documentRepository.saveAndFlush(doc);

        SegmentationStrategy strategy = strategyFactory.getStrategy(effectiveStrategy);
        List<RawChunk> rawChunks = strategy.segment(extractedPdf.fullText(), extractedPdf.pages());
        log.info("Document '{}' segmented into {} raw chunks via strategy {}", title, rawChunks.size(), effectiveStrategy);

        List<RawChunk> newChunks = selectNewChunks(rawChunks);
        List<float[]> embeddings;
        try {
            // Every vector is produced (and validated) before the first chunk is written. Outside PostgreSQL
            // they are not stored (no vector column), but computing them keeps the failure behavior identical.
            embeddings = embeddingService.embedAll(newChunks.stream()
                    .map(c -> EmbeddingService.chunkEmbeddingText(c.title(), c.content()))
                    .toList());
        } catch (EmbeddingException e) {
            log.error("Embedding generation failed for document '{}': {}", title, e.getMessage());
            doc.setStatus("FAILED");
            doc.setErrorMessage(e.isNotConfigured()
                    ? "Embedding provider not configured (GEMINI_API_KEY)"
                    : "Embedding generation failed: " + e.getMessage());
            documentRepository.saveAndFlush(doc);
            throw e;
        }

        try {
            for (int i = 0; i < newChunks.size(); i++) {
                RawChunk rawChunk = newChunks.get(i);
                String metadataJson = serializeMetadata(rawChunk.metadata());

                if (isPostgres) {
                    UUID chunkId = UUID.randomUUID();
                    String vectorStr = EmbeddingService.toPgVector(embeddings.get(i));
                    jdbcTemplate.update("""
                        INSERT INTO exegese_chunk (id, document_id, chunk_hash_sha256, sequence_number, title, content, metadata, embedding, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, cast(? as vector), CURRENT_TIMESTAMP)
                    """, chunkId, doc.getId(), rawChunk.chunkHashSha256(), rawChunk.sequenceNumber(), rawChunk.title(), rawChunk.content(), metadataJson, vectorStr);
                } else {
                    ExegeseChunk chunk = new ExegeseChunk(
                            doc,
                            rawChunk.chunkHashSha256(),
                            rawChunk.sequenceNumber(),
                            rawChunk.title(),
                            rawChunk.content(),
                            metadataJson
                    );
                    chunkRepository.save(chunk);
                }
            }

            doc.setStatus("INDEXED");
            doc = documentRepository.saveAndFlush(doc);
            log.info("Successfully indexed document '{}' with {} total chunks", title, rawChunks.size());
            return doc;
        } catch (DataAccessException e) {
            log.error("Database failure while processing chunks for document '{}': {}", title, e.getMessage(), e);
            doc.setStatus("FAILED");
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            throw new IllegalStateException("Chunk indexing failed for document: " + title, e);
        } catch (RuntimeException e) {
            log.error("Failed to process chunks for document '{}': {}", title, e.getMessage(), e);
            doc.setStatus("FAILED");
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            throw new IllegalStateException("Chunk indexing failed for document: " + title, e);
        }
    }

    /**
     * Chunks to index: blank chunks, chunks whose hash already exists and repeated hashes inside the same
     * document are skipped.
     */
    private List<RawChunk> selectNewChunks(List<RawChunk> rawChunks) {
        Map<String, RawChunk> byHash = new LinkedHashMap<>();
        for (RawChunk rawChunk : rawChunks) {
            if (rawChunk.content() == null || rawChunk.content().isBlank()
                    || byHash.containsKey(rawChunk.chunkHashSha256())
                    || chunkRepository.findByChunkHashSha256(rawChunk.chunkHashSha256()).isPresent()) {
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
