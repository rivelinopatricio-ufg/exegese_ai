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
package br.org.rivelino.exegese_ai;

import br.org.rivelino.exegese_ai.config.TestEmbeddingModelConfiguration.FakeEmbeddingModel;
import br.org.rivelino.exegese_ai.domain.dto.RawChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.service.CryptoService;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.DocumentRejectedException;
import br.org.rivelino.exegese_ai.service.DocumentStorageService;
import br.org.rivelino.exegese_ai.service.PdfTextExtractor;
import br.org.rivelino.exegese_ai.service.segmentation.SegmentationStrategyFactory;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.mockito.stubbing.Answer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Integration tests for the hardened ingestion pipeline (T4 / M5): asynchronous upload processing, storage of
 * the original PDF as {@code <sha256>.pdf} in the upload directory, {@code %PDF-} signature validation, page
 * limit, sanitized display names and a {@code FAILED} status that survives the rollback of the chunk
 * transaction. Not transactional: uploads are indexed by a background thread and the FAILED status is written
 * in its own transaction, so rows created here are removed explicitly.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUploadIngestionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentStorageService storageService;

    @Autowired
    private PdfTextExtractor pdfTextExtractor;

    @Autowired
    private SegmentationStrategyFactory strategyFactory;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @MockitoSpyBean
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    private final List<String> createdHashes = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        fakeEmbeddingModel.setBeforeEmbedHook(null);
        fakeEmbeddingModel.setUnavailable(false);
        for (String hash : createdHashes) {
            documentRepository.findByFileHashSha256(hash).ifPresent(doc -> {
                chunkRepository.deleteAll(chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId()));
                documentRepository.delete(doc);
            });
        }
    }

    @Test
    @WithMockUser(username = "ingestion.operator@exegese.test", roles = "OPERATOR")
    @DisplayName("Upload returns at once with PROCESSING; the background worker indexes the stored <sha256>.pdf")
    void testUploadIsIndexedInBackground() throws Exception {
        byte[] pdf = createPdf("001 — O que é ingestão assíncrona?\nO arquivo é indexado em segundo plano.");
        String hash = track(pdf);

        mockMvc.perform(multipart("/admin/documents/upload")
                        .file(new MockMultipartFile("file", "../../etc/dir‮manual\u0007.pdf", "application/pdf", pdf))
                        .param("strategy", "STRUCTURED_QA")
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("successMessage"));

        await().atMost(Duration.ofSeconds(20)).until(() -> documentRepository.findByFileHashSha256(hash)
                .map(ExegeseDocument::getStatus).filter("INDEXED"::equals).isPresent());

        ExegeseDocument doc = documentRepository.findByFileHashSha256(hash).orElseThrow();
        assertThat(doc.getStoragePath()).isEqualTo(hash + ".pdf");
        assertThat(doc.getTotalPages()).isEqualTo(1);
        assertThat(doc.getErrorMessage()).isNull();
        // The client file name is only a sanitized display name: no directories, control or bidi characters
        assertThat(doc.getOriginalFileName()).isEqualTo("dir manual .pdf");
        assertThat(doc.getTitle()).isEqualTo("dir manual .pdf");
        assertThat(storageService.uploadDir().resolve(hash + ".pdf")).exists().hasBinaryContent(pdf);
        assertThat(chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId())).hasSize(1);
    }

    @Test
    @WithMockUser(username = "ingestion.operator@exegese.test", roles = "OPERATOR")
    @DisplayName("A file without the %PDF- signature is rejected with a localized message and nothing is stored")
    void testNonPdfUploadIsRejected() throws Exception {
        byte[] notPdf = "GIF89a this is not a PDF document".getBytes(StandardCharsets.US_ASCII);
        String hash = track(notPdf);

        mockMvc.perform(multipart("/admin/documents/upload")
                        .file(new MockMultipartFile("file", "disguised.pdf", "application/pdf", notPdf))
                        .cookie(new Cookie("EXEGESE_LOCALE", "en"))
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attribute("errorMessage",
                        "The uploaded file is not a valid PDF. Please upload a document in PDF format."));

        assertThat(documentRepository.findByFileHashSha256(hash)).isEmpty();
        assertThat(storageService.uploadDir().resolve(hash + ".pdf")).doesNotExist();
        try (Stream<Path> files = Files.list(storageService.uploadDir())) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("A PDF above exegese.ingestion.max-pages is kept as FAILED with a generic message and no chunk")
    void testPageLimitMarksDocumentFailed() throws IOException {
        int pages = pdfTextExtractor.maxPages() + 1;
        List<String> pageTexts = new ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            pageTexts.add(String.format("%03d — Pergunta da página %d?%nResposta da página %d.", p, p, p));
        }
        byte[] pdf = createPdf(pageTexts.toArray(String[]::new));
        String hash = track(pdf);

        assertThatThrownBy(() -> ingestionService.ingestDocument("Manual Longo", "longo.pdf", pdf,
                List.of(), SegmentationStrategyType.STRUCTURED_QA))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(DocumentRejectedException.Reason.TOO_MANY_PAGES));

        ExegeseDocument doc = documentRepository.findByFileHashSha256(hash).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).contains("maximum allowed is " + pdfTextExtractor.maxPages());
        assertThat(chunkRepository.countByDocumentId(doc.getId())).isZero();
    }

    @Test
    @DisplayName("FAILED status and a generic error survive the rollback of the chunk transaction")
    void testFailedStatusSurvivesChunkTransactionRollback() throws IOException {
        byte[] pdf = createPdf("001 — Primeira pergunta da reversão?\nPrimeira resposta.",
                "002 — Segunda pergunta da reversão?\nSegunda resposta.");
        String hash = track(pdf);
        PdfTextExtractor.ExtractedPdf extracted = pdfTextExtractor.extract(pdf);
        List<RawChunk> rawChunks = strategyFactory.getStrategy(SegmentationStrategyType.STRUCTURED_QA)
                .segment(extracted.fullText(), extracted.pages());
        assertThat(rawChunks).hasSize(2);
        String conflictingHash = rawChunks.get(1).chunkHashSha256();

        // Between the duplicate check and the chunk transaction, a concurrent writer stores a chunk with the
        // same (document, hash): the insert of that chunk violates the per-document unique constraint
        AtomicBoolean fired = new AtomicBoolean();
        // The spied repository is an interface proxy: delegate through the spy's default answer
        Answer<?> realRepository = mockingDetails(chunkRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            boolean exists = (boolean) realRepository.answer(invocation);
            if (!exists && conflictingHash.equals(invocation.getArgument(1)) && fired.compareAndSet(false, true)) {
                ExegeseDocument registered = documentRepository.findByFileHashSha256(hash).orElseThrow();
                chunkRepository.save(new ExegeseChunk(registered, conflictingHash, 99, "Concurrent", "Concurrent chunk", "{}"));
            }
            return exists;
        }).when(chunkRepository).existsByDocumentIdAndChunkHashSha256(any(), any());

        assertThatThrownBy(() -> ingestionService.ingestDocument("Manual Reversão", "reversao.pdf", pdf,
                List.of(), SegmentationStrategyType.STRUCTURED_QA))
                .isInstanceOf(IllegalStateException.class);

        ExegeseDocument doc = documentRepository.findByFileHashSha256(hash).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).startsWith("Indexing failed (reference ")
                .doesNotContainIgnoringCase("constraint").doesNotContainIgnoringCase("insert");
        // The first chunk written by the failed transaction was rolled back; only the concurrent one remains
        List<ExegeseChunk> chunks = chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId());
        assertThat(chunks).extracting(ExegeseChunk::getSequenceNumber).containsExactly(99);
    }

    @Test
    @DisplayName("Documents left PROCESSING by a previous run are marked FAILED on startup recovery")
    void testInterruptedIngestionsAreMarkedFailed() {
        String hash = "f".repeat(64);
        createdHashes.add(hash);
        ExegeseDocument doc = new ExegeseDocument("Interrompido", "interrompido.pdf", hash + ".pdf", hash, 10L, "application/pdf");
        doc.setStatus("PROCESSING");
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);

        assertThat(ingestionService.failInterruptedIngestions()).isGreaterThanOrEqualTo(1);

        Optional<ExegeseDocument> recovered = documentRepository.findByFileHashSha256(hash);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().getStatus()).isEqualTo("FAILED");
        assertThat(recovered.get().getErrorMessage()).contains("upload the file again");
    }

    private String track(byte[] content) {
        String hash = cryptoService.sha256(content);
        createdHashes.add(hash);
        return hash;
    }

    /**
     * Builds a PDF with one page per text; lines are separated by '\n'.
     */
    private static byte[] createPdf(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(50, 700);
                    for (String line : text.split("\\R")) {
                        contentStream.showText(line.trim());
                        contentStream.newLineAtOffset(0, -15);
                    }
                    contentStream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
