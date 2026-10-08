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
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.service.CryptoService;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.EmbeddingException;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for embedding administration (T1): ingestion without a working embedding provider keeps
 * the document as FAILED with no chunk stored, and the CSRF-protected, role-restricted "reindex embeddings"
 * action. Not transactional: the FAILED status must survive the ingestion transaction, so rows created here
 * are removed explicitly.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmbeddingAdministrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    private final List<String> createdHashes = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        fakeEmbeddingModel.setUnavailable(false);
        for (String hash : createdHashes) {
            documentRepository.findByFileHashSha256(hash).ifPresent(doc -> {
                chunkRepository.deleteAll(chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId()));
                documentRepository.delete(doc);
            });
        }
    }

    @Test
    @DisplayName("Without embeddings the document is kept as FAILED and no chunk (nor zero vector) is stored")
    void testIngestionFailsWithoutEmbeddings() throws IOException {
        byte[] pdf = createPdf("001 — Pergunta sem embeddings disponíveis\nResposta que não pode ser indexada.");
        createdHashes.add(cryptoService.sha256(pdf));
        fakeEmbeddingModel.setUnavailable(true);

        assertThatThrownBy(() -> ingestionService.ingestDocument("Sem Embeddings", "sem-embeddings.pdf", pdf,
                List.of(), SegmentationStrategyType.STRUCTURED_QA))
                .isInstanceOfSatisfying(EmbeddingException.class, e -> assertThat(e.isNotConfigured()).isTrue());

        Optional<ExegeseDocument> stored = documentRepository.findByFileHashSha256(cryptoService.sha256(pdf));
        assertThat(stored).isPresent();
        assertThat(stored.get().getStatus()).isEqualTo("FAILED");
        assertThat(stored.get().getErrorMessage()).contains("GEMINI_API_KEY");
        assertThat(chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(stored.get().getId())).isEmpty();

        // Once embeddings work again, the same file is re-ingested from scratch
        fakeEmbeddingModel.setUnavailable(false);
        ExegeseDocument retried = ingestionService.ingestDocument("Sem Embeddings", "sem-embeddings.pdf", pdf,
                List.of(), SegmentationStrategyType.STRUCTURED_QA);
        assertThat(retried.getStatus()).isEqualTo("INDEXED");
        assertThat(chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(retried.getId())).isNotEmpty();
    }

    @Test
    @WithMockUser(username = "embedding.operator@exegese.test", roles = "OPERATOR")
    @DisplayName("Upload without embeddings shows the localized 'configure GEMINI_API_KEY' error")
    void testUploadShowsEmbeddingUnavailableMessage() throws Exception {
        byte[] pdf = createPdf("001 — Upload sem embeddings\nTexto do upload sem embeddings.");
        createdHashes.add(cryptoService.sha256(pdf));
        fakeEmbeddingModel.setUnavailable(true);

        mockMvc.perform(multipart("/admin/documents/upload")
                        .file(new MockMultipartFile("file", "upload.pdf", "application/pdf", pdf))
                        .param("strategy", "STRUCTURED_QA")
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attribute("errorMessage", containsString("GEMINI_API_KEY")));
    }

    @Test
    @WithMockUser(username = "embedding.admin@exegese.test", roles = "ADMIN")
    @DisplayName("Reindex action requires a CSRF token")
    void testReindexRequiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/documents/reindex-embeddings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "embedding.user@exegese.test", roles = "USER")
    @DisplayName("Reindex action is forbidden to standard users")
    void testReindexForbiddenToUsers() throws Exception {
        mockMvc.perform(post("/admin/documents/reindex-embeddings").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "embedding.operator@exegese.test", roles = "OPERATOR")
    @DisplayName("Operators can trigger the reindex; outside PostgreSQL it reports that pgvector is required")
    void testReindexOutsidePostgresReportsUnsupported() throws Exception {
        mockMvc.perform(post("/admin/documents/reindex-embeddings").with(csrf()).param("forceAll", "true"))
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attribute("errorMessage", containsString("pgvector")));

        mockMvc.perform(get("/admin/documents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/documents/reindex-embeddings")));
    }

    private static byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                for (String line : text.split("\n")) {
                    contentStream.showText(line.trim());
                    contentStream.newLineAtOffset(0, -15);
                }
                contentStream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
