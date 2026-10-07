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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            return fallbackCandidateRetrieval(query, subjectIds);
        }

        try {
            float[] embedding = embeddingModel.embed(query);
            if (isZeroOrInvalidVector(embedding)) {
                log.info("Vector embedding unavailable or zero-magnitude, proceeding with lexical full-text retrieval");
                return fallbackCandidateRetrieval(query, subjectIds);
            }
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

            return jdbcTemplate.query(sql.toString(), params, (var rs, @SuppressWarnings("unused") var rowNum) -> new RetrievedChunk(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("document_id")),
                    rs.getString("doc_title"),
                    rs.getString("chunk_title"),
                    rs.getString("content"),
                    rs.getInt("sequence_number"),
                    rs.getString("metadata"),
                    rs.getDouble("distance")
            ));
        } catch (DataAccessException e) {
            log.warn("PostgreSQL vector retrieval failed ({}), falling back to lexical search: {}",
                    e.getClass().getSimpleName(), e.getMessage() != null ? e.getMessage() : "no error message");
            return fallbackCandidateRetrieval(query, subjectIds);
        } catch (RuntimeException e) {
            log.warn("Vector embedding generation failed ({}), falling back to lexical search: {}",
                    e.getClass().getSimpleName(), e.getMessage() != null ? e.getMessage() : "no error message");
            return fallbackCandidateRetrieval(query, subjectIds);
        }
    }

    private List<RetrievedChunk> retrieveTextCandidates(String query, List<UUID> subjectIds) {
        if (!isPostgres) {
            return fallbackCandidateRetrieval(query, subjectIds);
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

            return jdbcTemplate.query(sql.toString(), params, (var rs, @SuppressWarnings("unused") var rowNum) -> new RetrievedChunk(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("document_id")),
                    rs.getString("doc_title"),
                    rs.getString("chunk_title"),
                    rs.getString("content"),
                    rs.getInt("sequence_number"),
                    rs.getString("metadata"),
                    rs.getDouble("rank")
            ));
        } catch (DataAccessException e) {
            log.warn("PostgreSQL FTS retrieval failed, falling back: {}", e.getMessage());
            return fallbackCandidateRetrieval(query, subjectIds);
        }
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas", "para", "por",
            "com", "sem", "sob", "sobre", "que", "quem", "qual", "quais", "como", "onde",
            "quando", "quanto", "quantos", "esta", "estao", "foi", "ser", "sao", "tem",
            "ter", "uma", "uns", "umas", "ate", "pode", "podem"
    );

    private static final Set<String> UBIQUITOUS_TERMS = Set.of(
            "declarar", "declaracao", "declaracoes", "declarada", "declarado",
            "imposto", "renda", "irpf", "tributavel", "tributaveis", "exercicio"
    );

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String nfd = java.text.Normalizer.normalize(text.toLowerCase(), java.text.Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9\\s]", " ");
    }

    private static boolean matchesTermOrStem(String text, String term) {
        if (text.contains(term)) {
            return true;
        }
        if (term.length() >= 5) {
            String stem = term.length() >= 7 ? term.substring(0, 6) : term.substring(0, term.length() - 1);
            return text.contains(stem);
        }
        return false;
    }

    private List<RetrievedChunk> fallbackCandidateRetrieval(String query, List<UUID> subjectIds) {
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

        List<RetrievedChunk> candidates = jdbcTemplate.query(sql.toString(), params, (var rs, @SuppressWarnings("unused") var rowNum) -> new RetrievedChunk(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("document_id")),
                rs.getString("doc_title"),
                rs.getString("chunk_title"),
                rs.getString("content"),
                rs.getInt("sequence_number"),
                rs.getString("metadata"),
                0.0
        ));

        // Score candidates based on term overlap, title importance, and lexical relevance
        String normalizedQuery = normalizeText(query);
        String[] terms = normalizedQuery.split("\\s+");
        List<ScoredCandidate> scored = new ArrayList<>();

        for (RetrievedChunk candidate : candidates) {
            String normTitle = normalizeText(candidate.chunkTitle());
            String normContent = normalizeText(candidate.content());
            String normFull = normTitle + " " + normContent;

            double score = 0.0;
            int matchedTitleTerms = 0;
            int matchedContentTerms = 0;

            if (normFull.contains(normalizedQuery)) {
                score += 30.0;
            }

            for (String term : terms) {
                if (term.length() < 2 || STOP_WORDS.contains(term)) {
                    continue;
                }

                boolean isUbiquitous = UBIQUITOUS_TERMS.contains(term);
                boolean isNumberOrCode = term.matches(".*\\d+.*") || (term.length() <= 4 && term.matches("[a-z0-9]+"));
                double termWeight = isUbiquitous ? 1.0 : (isNumberOrCode ? 8.0 : 4.0);

                boolean matchedInTitle = matchesTermOrStem(normTitle, term);
                boolean matchedInContent = matchesTermOrStem(normContent, term);

                if (matchedInTitle) {
                    matchedTitleTerms++;
                    score += termWeight * (isUbiquitous ? 1.5 : 5.0);
                }
                if (matchedInContent) {
                    matchedContentTerms++;
                    score += termWeight;
                }
            }

            // Reject spurious matches with zero title matches and insufficient content matches
            if (matchedTitleTerms == 0 && (matchedContentTerms < 2 && terms.length >= 3)) {
                score = 0.0;
            }

            if (score > 0.0) {
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
            RrfAccumulator acc = map.computeIfAbsent(c.id(), (@SuppressWarnings("unused") var k) -> new RrfAccumulator(c));
            acc.vectorRank = rank;
            acc.score += 1.0 / (RRF_K + rank);
        }

        // Process text candidates (1-based ranking)
        for (int rank = 1; rank <= textCandidates.size(); rank++) {
            RetrievedChunk c = textCandidates.get(rank - 1);
            RrfAccumulator acc = map.computeIfAbsent(c.id(), (@SuppressWarnings("unused") var k) -> new RrfAccumulator(c));
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

    private boolean isZeroOrInvalidVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return true;
        }
        for (float val : vector) {
            if (val != 0.0f) {
                return false;
            }
        }
        return true;
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
