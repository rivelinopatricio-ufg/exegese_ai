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
package br.org.rivelino.exegese_ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Automatically initializes and self-heals PostgreSQL schema extensions,
 * vector columns, and specialized full-text/HNSW indexes upon application startup.
 *
 * @author Rivelino Patrício
 */
@Component
public class DatabaseSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(@SuppressWarnings("unused") ApplicationArguments args) {
        if (!isPostgreSql(dataSource)) {
            log.info("Non-PostgreSQL database detected. Skipping pgvector schema initialization.");
            return;
        }

        log.info("PostgreSQL database detected. Verifying and self-healing pgvector schema, vector columns, and indexes...");
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS \"vector\"");

            jdbcTemplate.execute("ALTER TABLE IF EXISTS exegese_chunk ADD COLUMN IF NOT EXISTS embedding VECTOR(768)");
            jdbcTemplate.execute("""
                ALTER TABLE IF EXISTS exegese_chunk ADD COLUMN IF NOT EXISTS tsv TSVECTOR GENERATED ALWAYS AS (
                    to_tsvector('portuguese', coalesce(title, '') || ' ' || content)
                ) STORED
            """);

            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_exegese_chunk_hnsw 
                ON exegese_chunk USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
            """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_tsv ON exegese_chunk USING gin (tsv)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_metadata ON exegese_chunk USING gin (metadata jsonb_path_ops)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_doc ON exegese_chunk (document_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_doc_subject_subject ON document_subject (subject_id)");

            log.info("PostgreSQL pgvector schema self-healing completed successfully.");
        } catch (DataAccessException e) {
            log.error("Failed to initialize or migrate PostgreSQL pgvector schema: {}", e.getMessage(), e);
            throw e;
        }
    }

    private boolean isPostgreSql(DataSource ds) {
        try (Connection conn = ds.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Could not determine database product name from DataSource: {}", e.getMessage());
            return false;
        }
    }
}
