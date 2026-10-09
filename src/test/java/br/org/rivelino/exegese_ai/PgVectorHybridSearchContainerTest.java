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

import br.org.rivelino.exegese_ai.domain.dto.SearchResultChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.AntiHallucinationGuard;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.EmbeddingReindexService;
import br.org.rivelino.exegese_ai.service.EmbeddingService;
import br.org.rivelino.exegese_ai.service.HybridSearchService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PostgreSQL + pgvector integration tests (Testcontainers, skipped when Docker is unavailable) for the SQL
 * paths that H2 cannot run: embedding reindexing of NULL and legacy zero vectors, cosine vector retrieval
 * with similarity evidence, bounded strict/relaxed full-text retrieval, refusal of out-of-scope questions and
 * vector storage during ingestion. Embeddings come from the deterministic test model (no external API).
 * The schema is created by the real Flyway migrations and validated against the JPA entities.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class PgVectorHybridSearchContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private HybridSearchService searchService;

    @Autowired
    private EmbeddingReindexService reindexService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private AntiHallucinationGuard guard;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Reindex fills NULL and zero vectors; search combines vector similarity and bounded full-text retrieval")
    void testReindexAndHybridSearch() {
        ExegeseSubject subject = subjectRepository.save(new ExegeseSubject("pg-irpf", "IRPF", "IRPF no PostgreSQL"));
        ExegeseDocument doc = new ExegeseDocument("Manual IRPF", "irpf.pdf", "storage/irpf.pdf", "hash-pg-doc", 10L, "PDF");
        doc.addSubject(subject);
        doc = documentRepository.save(doc);

        ExegeseChunk medical = chunkRepository.save(new ExegeseChunk(doc, "hash-pg-medicas", 1,
                "Pergunta 045 — Despesas Médicas Dedutíveis",
                "São dedutíveis os pagamentos efetuados a médicos, dentistas e hospitais.", "{\"page\": 45}"));
        chunkRepository.save(new ExegeseChunk(doc, "hash-pg-cripto", 2,
                "Pergunta 120 — Criptoativos e Bitcoin",
                "Os criptoativos devem ser informados na ficha Bens e Direitos.", "{\"page\": 120}"));
        ExegeseChunk legacy = chunkRepository.save(new ExegeseChunk(doc, "hash-pg-prazo", 3,
                "Pergunta 021 — Prazo de Entrega",
                "A declaração deve ser apresentada até 30 de abril.", "{\"page\": 21}"));
        jdbcTemplate.update("UPDATE exegese_chunk SET embedding = cast(? as vector) WHERE id = ?",
                EmbeddingService.toPgVector(new float[768]), legacy.getId());

        // An embedding run started by an ingestion in another test must finish first
        await().atMost(Duration.ofSeconds(30)).until(() -> !reindexService.status().running());
        assertThat(countMissingVectors()).isEqualTo(3);

        assertThat(reindexService.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);
        await().atMost(Duration.ofSeconds(30)).until(() -> !reindexService.status().running());
        assertThat(reindexService.status().state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
        assertThat(reindexService.status().processed()).isEqualTo(3);
        assertThat(countMissingVectors()).isZero();

        // Semantic + strict full-text match
        List<SearchResultChunk> medicalResults = searchService.search("despesas médicas dedutíveis hospitais",
                List.of(subject.getId()), 4);
        assertThat(medicalResults).isNotEmpty();
        assertThat(medicalResults.get(0).chunkId()).isEqualTo(medical.getId());
        assertThat(medicalResults.get(0).vectorSimilarity()).isNotNull().isPositive();
        assertThat(guard.isGrounded(medicalResults)).isTrue();

        // Relaxed full-text (any term) still finds the chunk when one query term is absent
        List<SearchResultChunk> relaxed = searchService.search("bitcoin zzqxinexistente", List.of(subject.getId()), 4);
        assertThat(relaxed).extracting(SearchResultChunk::chunkTitle).anyMatch(t -> t.contains("Bitcoin"));

        // Out of scope: vector neighbours exist but are dissimilar and there is no lexical evidence
        List<SearchResultChunk> outOfScope = searchService.search("alíquota de ICMS sobre combustíveis",
                List.of(subject.getId()), 4);
        assertThat(guard.isGrounded(outOfScope)).isFalse();
    }

    @Test
    @DisplayName("Ingestion indexes at once and the background job stores a non-zero 768-dimension vector per chunk")
    void testIngestionStoresVectors() throws IOException {
        ExegeseDocument doc = ingestionService.ingestDocument("Manual PG", "manual-pg.pdf",
                createPdf("001 — O que é rendimento isento?\nRendimentos isentos não sofrem tributação.\n"
                        + "002 — Quem é dependente?\nFilhos até 21 anos podem ser dependentes."),
                List.of(), SegmentationStrategyType.STRUCTURED_QA);

        assertThat(doc.getStatus()).isEqualTo("INDEXED");
        UUID documentId = doc.getId();
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject("""
                SELECT count(*) FROM exegese_chunk
                WHERE document_id = ? AND embedding IS NOT NULL AND vector_norm(embedding) > 0
                """, Integer.class, documentId) == 2);
        Integer dimensions = jdbcTemplate.queryForObject(
                "SELECT max(vector_dims(embedding)) FROM exegese_chunk WHERE document_id = ?", Integer.class, doc.getId());

        assertThat(dimensions).isEqualTo(768);
    }

    private long countMissingVectors() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM exegese_chunk WHERE embedding IS NULL OR vector_norm(embedding) = 0", Long.class);
        return count != null ? count : 0L;
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
