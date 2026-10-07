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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating high-fidelity document ingestion, extraction, chunking, and idempotent indexing.
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
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public DocumentIngestionService(ExegeseDocumentRepository documentRepository,
                                  ExegeseChunkRepository chunkRepository,
                                  ExegeseSubjectRepository subjectRepository,
                                  PdfTextExtractor pdfTextExtractor,
                                  SegmentationStrategyFactory strategyFactory,
                                  CryptoService cryptoService,
                                  EmbeddingModel embeddingModel,
                                  ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.subjectRepository = subjectRepository;
        this.pdfTextExtractor = pdfTextExtractor;
        this.strategyFactory = strategyFactory;
        this.cryptoService = cryptoService;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
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
     */
    @Transactional
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
     */
    @Transactional
    public ExegeseDocument ingestDocument(String title,
                                          String fileName,
                                          byte[] fileBytes,
                                          List<UUID> subjectIds,
                                          SegmentationStrategyType strategyType) throws IOException {
        String fileHash = cryptoService.sha256(fileBytes);
        log.info("Starting ingestion of document '{}' ({}), SHA-256: {}", title, fileName, fileHash);

        Optional<ExegeseDocument> existingDoc = documentRepository.findByFileHashSha256(fileHash);
        if (existingDoc.isPresent()) {
            log.info("Document with SHA-256 {} already indexed (ID: {}). Reusing existing record.",
                    fileHash, existingDoc.get().getId());
            return existingDoc.get();
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

        doc = documentRepository.save(doc);

        try {
            SegmentationStrategy strategy = strategyFactory.getStrategy(effectiveStrategy);
            List<RawChunk> rawChunks = strategy.segment(extractedPdf.fullText(), extractedPdf.pages());
            log.info("Document '{}' segmented into {} raw chunks via strategy {}", title, rawChunks.size(), effectiveStrategy);

            for (RawChunk rawChunk : rawChunks) {
                Optional<ExegeseChunk> existingChunk = chunkRepository.findByChunkHashSha256(rawChunk.chunkHashSha256());
                if (existingChunk.isPresent()) {
                    continue;
                }

                String metadataJson = serializeMetadata(rawChunk.metadata());

                // Generate vector embedding
                embeddingModel.embed(new Document(rawChunk.content()));

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

            doc.setStatus("INDEXED");
            doc = documentRepository.save(doc);
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

    private String serializeMetadata(Object metadata) {
        if (metadata == null) return "{}";
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize chunk metadata to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
