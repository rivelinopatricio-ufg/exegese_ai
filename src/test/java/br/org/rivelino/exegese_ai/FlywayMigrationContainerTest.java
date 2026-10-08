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
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.service.AntiHallucinationGuard;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.EmbeddingService;
import br.org.rivelino.exegese_ai.service.HybridSearchService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL + pgvector integration tests (Testcontainers, skipped when Docker is unavailable) for the Flyway
 * migrations (T5): a fresh database is created by V1+ and validated against the JPA entities
 * ({@code ddl-auto=validate}), then used for a real ingestion and a hybrid search with the deterministic test
 * embedding model (no external API). Databases created before Flyway, by the former
 * {@code docker/postgres/init-schema.sql} or by Hibernate {@code ddl-auto=update}, are baselined at version 1
 * and migrated by the idempotent V2+ (per-document chunk uniqueness, nullable embedding, no enum CHECKs).
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
class FlywayMigrationContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private HybridSearchService searchService;

    @Autowired
    private AntiHallucinationGuard guard;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Test
    @DisplayName("A fresh database is created by V1 to V4 and matches the JPA entities")
    void testFreshDatabaseIsMigrated() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4");
        assertMigratedSchema(jdbcTemplate);
    }

    @Test
    @DisplayName("Ingestion and hybrid search work on the migrated schema; a shared chunk is indexed in both documents")
    void testIngestionAndHybridSearchOnMigratedSchema() throws IOException {
        String sharedQuestion = "001 — O que é rendimento isento?\nRendimentos isentos não sofrem tributação do imposto.";
        ExegeseDocument first = ingestionService.ingestDocument("Manual Flyway A", "flyway-a.pdf",
                createPdf(sharedQuestion + "\n002 — Quem é dependente?\nFilhos até 21 anos podem ser dependentes."),
                List.of(), SegmentationStrategyType.STRUCTURED_QA);
        ExegeseDocument second = ingestionService.ingestDocument("Manual Flyway B", "flyway-b.pdf",
                createPdf(sharedQuestion + "\n003 — Qual o prazo?\nA declaração deve ser entregue até maio."),
                List.of(), SegmentationStrategyType.STRUCTURED_QA);

        assertThat(first.getStatus()).isEqualTo("INDEXED");
        assertThat(second.getStatus()).isEqualTo("INDEXED");
        assertThat(chunkRepository.countByDocumentId(first.getId())).isEqualTo(2);
        assertThat(chunkRepository.countByDocumentId(second.getId())).isEqualTo(2);
        Integer withVector = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM exegese_chunk
                WHERE document_id IN (?, ?) AND embedding IS NOT NULL AND vector_norm(embedding) > 0
                """, Integer.class, first.getId(), second.getId());
        assertThat(withVector).isEqualTo(4);

        List<SearchResultChunk> results = searchService.search("rendimentos isentos tributação imposto", List.of(), 4);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkTitle()).contains("Pergunta 001");
        assertThat(guard.isGrounded(results)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"db/legacy/init-schema-before-flyway.sql", "db/legacy/hibernate-ddl-auto-schema.sql"})
    @DisplayName("A database created before Flyway is baselined at version 1 and migrated by V2+ keeping its data")
    void testLegacyDatabaseIsBaselinedAndMigrated(String legacySchema) {
        String database = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE DATABASE " + database);
        DataSource legacyDataSource = new DriverManagerDataSource(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource(legacySchema)).execute(legacyDataSource);
        JdbcTemplate legacy = new JdbcTemplate(legacyDataSource);

        UUID firstDoc = insertDocument(legacy, "a".repeat(64));
        UUID secondDoc = insertDocument(legacy, "b".repeat(64));
        String chunkHash = "c".repeat(64);
        insertChunk(legacy, firstDoc, chunkHash, true);
        // Before the migration the chunk hash is globally unique
        assertThatThrownBy(() -> insertChunk(legacy, secondDoc, chunkHash, true))
                .isInstanceOf(DataIntegrityViolationException.class);

        Flyway flyway = Flyway.configure()
                .dataSource(legacyDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();

        List<String> versions = legacy.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4");
        assertThat(legacy.queryForObject(
                "SELECT type FROM flyway_schema_history WHERE version = '1'", String.class)).isEqualTo("BASELINE");
        assertMigratedSchema(legacy);
        assertThat(legacy.queryForObject("SELECT count(*) FROM exegese_chunk", Integer.class)).isEqualTo(1);

        // Same text in another document is accepted; twice in the same document is not; the vector may be NULL
        insertChunk(legacy, secondDoc, chunkHash, false);
        assertThatThrownBy(() -> insertChunk(legacy, secondDoc, chunkHash, false))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Running the migrations again changes nothing
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private static void assertMigratedSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'exegese_chunk'::regclass AND conname = 'uk_exegese_chunk_document_hash'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                WHERE i.indrelid = 'exegese_chunk'::regclass AND i.indisunique AND i.indnkeyatts = 1
                  AND a.attname = 'chunk_hash_sha256'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'exegese_chunk' AND column_name = 'embedding'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                WHERE table_name = 'exegese_chunk' AND column_name = 'tsv'
                """, String.class)).isEqualTo("ALWAYS");
        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'exegese_chunk'", String.class))
                .contains("idx_exegese_chunk_hnsw", "idx_exegese_chunk_tsv", "idx_exegese_chunk_metadata", "idx_exegese_chunk_doc");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE contype = 'c' AND conname IN ('exegese_user_role_check', 'ai_model_config_provider_check',
                                                    'exegese_document_segmentation_strategy_check')
                """, Integer.class)).isZero();
        // V4: accounts are bound to the Google subject, unique when set (unbound legacy rows stay NULL)
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'exegese_user' AND column_name = 'google_sub'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'exegese_user'", String.class))
                .contains("uk_exegese_user_google_sub");
    }

    private static UUID insertDocument(JdbcTemplate jdbc, String fileHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exegese_document (id, title, original_file_name, storage_path, file_hash_sha256, file_size,
                                              file_type, total_pages, segmentation_strategy, status, created_at, updated_at)
                VALUES (?, 'Legado', 'legado.pdf', 'local://legado.pdf', ?, 10, 'application/pdf', 1, 'STRUCTURED_QA',
                        'INDEXED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, fileHash);
        return id;
    }

    private static void insertChunk(JdbcTemplate jdbc, UUID documentId, String chunkHash, boolean withVector) {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        jdbc.update("""
                INSERT INTO exegese_chunk (id, document_id, chunk_hash_sha256, sequence_number, title, content, metadata,
                                           embedding, created_at)
                VALUES (?, ?, ?, 1, 'Pergunta 001', 'Conteúdo legado', '{}'::jsonb, cast(? as vector), CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), documentId, chunkHash, withVector ? EmbeddingService.toPgVector(vector) : null);
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database;
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
