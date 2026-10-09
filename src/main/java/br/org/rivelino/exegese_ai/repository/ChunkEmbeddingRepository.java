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
package br.org.rivelino.exegese_ai.repository;

import br.org.rivelino.exegese_ai.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC access to the pgvector {@code exegese_chunk.embedding} column, used by the embedding reindexing job.
 * The column only exists on PostgreSQL: on other databases {@link #supportsVectors()} is false and the job
 * does not run. Batches are read with keyset pagination on the chunk id, so a chunk whose embedding failed
 * is not selected again within the same run.
 *
 * @author Rivelino Patrício
 */
@Repository
public class ChunkEmbeddingRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingRepository.class);

    /** Chunks without a usable vector: never embedded (NULL) or holding a legacy zero vector. */
    private static final String MISSING_VECTOR = "(c.embedding IS NULL OR vector_norm(c.embedding) = 0)";

    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;

    public ChunkEmbeddingRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgres = isPostgreSql(dataSource);
    }

    /**
     * Chunk text read for (re)embedding.
     *
     * @param id Chunk id
     * @param title Chunk title, may be null
     * @param content Chunk content
     */
    public record PendingChunk(UUID id, String title, String content) {}

    /**
     * @return true when the database has the pgvector embedding column (PostgreSQL)
     */
    public boolean supportsVectors() {
        return postgres;
    }

    /**
     * Counts the chunks a reindex run will process.
     *
     * @param forceAll true to count every chunk, false for chunks with a missing or zero vector only
     * @return Number of chunks
     */
    public long countChunks(boolean forceAll) {
        String sql = "SELECT count(*) FROM exegese_chunk c" + (forceAll ? "" : " WHERE " + MISSING_VECTOR);
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Reads the next batch of chunks after the given id (keyset pagination, ordered by id).
     *
     * @param forceAll true for every chunk, false for chunks with a missing or zero vector only
     * @param afterId Exclusive lower bound of the chunk id
     * @param limit Maximum number of chunks
     * @return The next chunks, empty when done
     */
    public List<PendingChunk> findBatch(boolean forceAll, UUID afterId, int limit) {
        String sql = "SELECT c.id, c.title, c.content FROM exegese_chunk c WHERE c.id > ?"
                + (forceAll ? "" : " AND " + MISSING_VECTOR)
                + " ORDER BY c.id LIMIT ?";
        return jdbcTemplate.query(sql, (var rs, @SuppressWarnings("unused") var rowNum) -> new PendingChunk(
                UUID.fromString(rs.getString("id")),
                rs.getString("title"),
                rs.getString("content")), afterId, limit);
    }

    /**
     * Embedding progress of one document.
     *
     * @param embedded Chunks with a usable vector
     * @param total All chunks of the document
     */
    public record EmbeddingProgress(long embedded, long total) {

        public boolean complete() {
            return embedded >= total;
        }
    }

    /**
     * Embedding progress of every document that has chunks (one grouped query).
     *
     * @return Document id to progress; empty outside PostgreSQL (no vector column)
     */
    public Map<UUID, EmbeddingProgress> embeddingProgressByDocument() {
        if (!postgres) {
            return Map.of();
        }
        Map<UUID, EmbeddingProgress> progress = new HashMap<>();
        String sql = "SELECT c.document_id, count(*) AS total, count(*) FILTER (WHERE NOT " + MISSING_VECTOR
                + ") AS embedded FROM exegese_chunk c GROUP BY c.document_id";
        jdbcTemplate.query(sql, (ResultSet rs) -> {
            progress.put(UUID.fromString(rs.getString("document_id")),
                    new EmbeddingProgress(rs.getLong("embedded"), rs.getLong("total")));
        });
        return progress;
    }

    /**
     * Stores the given vectors (each one already validated by {@link EmbeddingService}).
     *
     * @param embeddings Chunk id to vector
     */
    public void updateEmbeddings(Map<UUID, float[]> embeddings) {
        if (embeddings.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(embeddings.size());
        embeddings.forEach((id, vector) -> args.add(new Object[]{EmbeddingService.toPgVector(vector), id}));
        jdbcTemplate.batchUpdate("UPDATE exegese_chunk SET embedding = cast(? as vector) WHERE id = ?", args);
    }

    private static boolean isPostgreSql(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Could not determine database product name, assuming non-postgres: {}", e.getMessage());
            return false;
        }
    }
}
