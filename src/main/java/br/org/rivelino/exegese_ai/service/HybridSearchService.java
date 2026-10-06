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

import br.org.rivelino.exegese_ai.domain.dto.SearchResultChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Service orchestrating hybrid search combining semantic vector retrieval (HNSW cosine)
 * and lexical full-text search (tsvector in Portuguese) fused via Reciprocal Rank Fusion (RRF).
 *
 * @author Rivelino Patrício
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;
    private static final int DEFAULT_TOP_K = 4;
    private static final int CANDIDATE_LIMIT = 10;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final boolean isPostgres;

    public HybridSearchService(NamedParameterJdbcTemplate jdbcTemplate,
                               EmbeddingModel embeddingModel,
                               DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.isPostgres = checkPostgreSql(dataSource);
        log.info("HybridSearchService initialized. Database mode: {}", isPostgres ? "PostgreSQL (pgvector/tsvector)" : "Fallback (H2/Generic)");
    }

    private boolean checkPostgreSql(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Could not determine database product name, assuming non-postgres: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Executes hybrid search returning Top-4 canonical chunks fused by RRF.
     *
     * @param query The search query string
     * @param subjectIds List of subject IDs to filter by (or empty/null for all)
     * @return Top-K ranked SearchResultChunk list
     */
    public List<SearchResultChunk> search(String query, List<UUID> subjectIds) {
        return search(query, subjectIds, DEFAULT_TOP_K);
    }

    /**
     * Executes hybrid search returning Top-K canonical chunks fused by RRF.
     *
     * @param query The search query string
     * @param subjectIds List of subject IDs to filter by (or empty/null for all)
     * @param topK Number of final fused chunks to return
     * @return Top-K ranked SearchResultChunk list
     */
    public List<SearchResultChunk> search(String query, List<UUID> subjectIds, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        List<RetrievedChunk> vectorCandidates = retrieveVectorCandidates(query, subjectIds);
        List<RetrievedChunk> textCandidates = retrieveTextCandidates(query, subjectIds);

        return fuseRankings(vectorCandidates, textCandidates, topK);
    }

    private List<RetrievedChunk> retrieveVectorCandidates(String query, List<UUID> subjectIds) {
        if (!isPostgres) {
            return fallbackCandidateRetrieval(query, subjectIds, true);
        }

        try {
            float[] embedding = embeddingModel.embed(query);
            String vectorStr = formatVector(embedding);

            StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                       c.content, c.sequence_number, c.metadata,
                       (c.embedding <=> cast(:vector as vector)) as distance
                FROM exegese_chunk c
                JOIN exegese_document d ON c.document_id = d.id
            """);

            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("vector", vectorStr);

            if (subjectIds != null && !subjectIds.isEmpty()) {
                sql.append("""
                    JOIN document_subject ds ON d.id = ds.document_id
                    WHERE ds.subject_id IN (:subjectIds)
                """);
                params.addValue("subjectIds", subjectIds);
            }

            sql.append(" ORDER BY distance ASC LIMIT ").append(CANDIDATE_LIMIT);

            return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new RetrievedChunk(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("document_id")),
                    rs.getString("doc_title"),
                    rs.getString("chunk_title"),
                    rs.getString("content"),
                    rs.getInt("sequence_number"),
                    rs.getString("metadata"),
                    rs.getDouble("distance")
            ));
        } catch (Exception e) {
            log.warn("PostgreSQL vector retrieval failed, falling back: {}", e.getMessage());
            return fallbackCandidateRetrieval(query, subjectIds, true);
        }
    }

    private List<RetrievedChunk> retrieveTextCandidates(String query, List<UUID> subjectIds) {
        if (!isPostgres) {
            return fallbackCandidateRetrieval(query, subjectIds, false);
        }

        try {
            StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                       c.content, c.sequence_number, c.metadata,
                       ts_rank_cd(c.tsv, plainto_tsquery('portuguese', :query)) as rank
                FROM exegese_chunk c
                JOIN exegese_document d ON c.document_id = d.id
            """);

            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("query", query);

            if (subjectIds != null && !subjectIds.isEmpty()) {
                sql.append("""
                    JOIN document_subject ds ON d.id = ds.document_id
                    WHERE ds.subject_id IN (:subjectIds)
                      AND c.tsv @@ plainto_tsquery('portuguese', :query)
                """);
                params.addValue("subjectIds", subjectIds);
            } else {
                sql.append(" WHERE c.tsv @@ plainto_tsquery('portuguese', :query)");
            }

            sql.append(" ORDER BY rank DESC LIMIT ").append(CANDIDATE_LIMIT);

            return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new RetrievedChunk(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("document_id")),
                    rs.getString("doc_title"),
                    rs.getString("chunk_title"),
                    rs.getString("content"),
                    rs.getInt("sequence_number"),
                    rs.getString("metadata"),
                    rs.getDouble("rank")
            ));
        } catch (Exception e) {
            log.warn("PostgreSQL FTS retrieval failed, falling back: {}", e.getMessage());
            return fallbackCandidateRetrieval(query, subjectIds, false);
        }
    }

    private List<RetrievedChunk> fallbackCandidateRetrieval(String query, List<UUID> subjectIds, boolean isSemantic) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                   c.content, c.sequence_number, c.metadata
            FROM exegese_chunk c
            JOIN exegese_document d ON c.document_id = d.id
        """);

        MapSqlParameterSource params = new MapSqlParameterSource();

        if (subjectIds != null && !subjectIds.isEmpty()) {
            sql.append("""
                JOIN document_subject ds ON d.id = ds.document_id
                WHERE ds.subject_id IN (:subjectIds)
            """);
            params.addValue("subjectIds", subjectIds);
        }

        List<RetrievedChunk> candidates = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new RetrievedChunk(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("document_id")),
                rs.getString("doc_title"),
                rs.getString("chunk_title"),
                rs.getString("content"),
                rs.getInt("sequence_number"),
                rs.getString("metadata"),
                0.0
        ));

        // Score candidates based on term overlap and lexical relevance
        String[] terms = query.toLowerCase().split("\\s+");
        List<ScoredCandidate> scored = new ArrayList<>();

        for (RetrievedChunk candidate : candidates) {
            String fullCandidateText = ((candidate.chunkTitle() != null ? candidate.chunkTitle() : "") + " " + candidate.content()).toLowerCase();
            double score = 0.0;
            for (String term : terms) {
                if (term.length() > 2) {
                    if (fullCandidateText.contains(term)) {
                        score += isSemantic ? 1.0 : 2.0;
                    }
                    if (candidate.chunkTitle() != null && candidate.chunkTitle().toLowerCase().contains(term)) {
                        score += 3.0; // Higher weight for title matches
                    }
                }
            }
            if (score > 0.0 || !isSemantic) {
                scored.add(new ScoredCandidate(candidate, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<RetrievedChunk> result = new ArrayList<>();
        int limit = Math.min(scored.size(), CANDIDATE_LIMIT);
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).chunk());
        }
        return result;
    }

    /**
     * Combines vector and lexical candidate lists using Reciprocal Rank Fusion (RRF):
     * RRF_Score = 1/(60 + rank_vector) + 1/(60 + rank_text)
     */
    private List<SearchResultChunk> fuseRankings(List<RetrievedChunk> vectorCandidates,
                                                 List<RetrievedChunk> textCandidates,
                                                 int topK) {
        Map<UUID, RrfAccumulator> map = new LinkedHashMap<>();

        // Process vector candidates (1-based ranking)
        for (int rank = 1; rank <= vectorCandidates.size(); rank++) {
            RetrievedChunk c = vectorCandidates.get(rank - 1);
            RrfAccumulator acc = map.computeIfAbsent(c.id(), k -> new RrfAccumulator(c));
            acc.vectorRank = rank;
            acc.score += 1.0 / (RRF_K + rank);
        }

        // Process text candidates (1-based ranking)
        for (int rank = 1; rank <= textCandidates.size(); rank++) {
            RetrievedChunk c = textCandidates.get(rank - 1);
            RrfAccumulator acc = map.computeIfAbsent(c.id(), k -> new RrfAccumulator(c));
            acc.textRank = rank;
            acc.score += 1.0 / (RRF_K + rank);
        }

        List<RrfAccumulator> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Double.compare(b.score, a.score));

        List<SearchResultChunk> result = new ArrayList<>();
        int count = Math.min(list.size(), topK);
        for (int i = 0; i < count; i++) {
            RrfAccumulator acc = list.get(i);
            result.add(new SearchResultChunk(
                    acc.chunk.id(),
                    acc.chunk.documentId(),
                    acc.chunk.docTitle(),
                    acc.chunk.chunkTitle(),
                    acc.chunk.content(),
                    acc.chunk.sequenceNumber(),
                    acc.chunk.metadata(),
                    acc.score,
                    acc.vectorRank,
                    acc.textRank
            ));
        }

        return result;
    }

    private String formatVector(float[] vector) {
        if (vector == null || vector.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private record RetrievedChunk(
            UUID id,
            UUID documentId,
            String docTitle,
            String chunkTitle,
            String content,
            int sequenceNumber,
            String metadata,
            double originalScore
    ) {}

    private record ScoredCandidate(RetrievedChunk chunk, double score) {}

    private static class RrfAccumulator {
        private final RetrievedChunk chunk;
        private double score = 0.0;
        private int vectorRank = Integer.MAX_VALUE;
        private int textRank = Integer.MAX_VALUE;

        public RrfAccumulator(RetrievedChunk chunk) {
            this.chunk = chunk;
        }
    }
}
