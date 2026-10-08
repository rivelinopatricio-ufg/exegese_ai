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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service orchestrating hybrid search combining semantic vector retrieval (HNSW cosine)
 * and lexical full-text search (tsvector in Portuguese) fused via Reciprocal Rank Fusion (RRF).
 * <p>
 * Subjects are public, but every retrieval path only returns documents visible through an
 * <em>active</em> subject: requested subject ids are intersected with the active subjects
 * (unknown or inactive ids are ignored), and without a usable filter the search covers documents
 * having at least one active subject or no subject at all. Documents linked only to inactive
 * subjects are never returned.
 * <p>
 * Every retrieval query is bounded by a {@code LIMIT}: on PostgreSQL the lexical side runs a strict
 * full-text query (all terms) and, when it finds too few chunks, a relaxed full-text query (any significant
 * term) whose candidates are re-scored by a term-overlap heuristic. When no embedding provider is configured
 * the vector side is skipped (logged once) and the answer relies on full-text retrieval only. Outside
 * PostgreSQL (H2 in tests) there is no vector column: a bounded scan scored by the same heuristic is used.
 *
 * @author Rivelino Patrício
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;
    private static final int DEFAULT_TOP_K = 4;
    private static final int CANDIDATE_LIMIT = 10;
    /** Candidates fetched by the relaxed (any term) full-text query before in-memory re-scoring. */
    private static final int RELAXED_CANDIDATE_LIMIT = 50;
    /** Rows scanned by the non-PostgreSQL lexical path (test databases only). */
    private static final int SCAN_LIMIT = 500;
    private static final int MAX_QUERY_TERMS = 16;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final boolean isPostgres;
    private final AtomicBoolean embeddingsUnavailableLogged = new AtomicBoolean(false);

    public HybridSearchService(NamedParameterJdbcTemplate jdbcTemplate,
                               EmbeddingService embeddingService,
                               DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
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

        SubjectScope scope = resolveSubjectScope(subjectIds);
        List<RetrievedChunk> vectorCandidates = retrieveVectorCandidates(query, scope);
        List<RetrievedChunk> textCandidates = retrieveLexicalCandidates(query, scope);

        return fuseRankings(vectorCandidates, textCandidates, topK);
    }

    /**
     * Intersects the requested subject ids with the active subjects. When nothing usable remains
     * (no request, or only unknown/inactive ids) the default visibility scope applies.
     */
    private SubjectScope resolveSubjectScope(List<UUID> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return SubjectScope.DEFAULT_VISIBILITY;
        }
        List<UUID> requested = subjectIds.stream().filter(Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) {
            return SubjectScope.DEFAULT_VISIBILITY;
        }

        List<UUID> active = jdbcTemplate.query(
                "SELECT s.id FROM exegese_subject s WHERE s.active = TRUE AND s.id IN (:subjectIds)",
                new MapSqlParameterSource("subjectIds", requested),
                (var rs, @SuppressWarnings("unused") var rowNum) -> UUID.fromString(rs.getString("id")));

        if (active.isEmpty()) {
            log.debug("Requested subject filter has no active subject; applying default visibility scope");
            return SubjectScope.DEFAULT_VISIBILITY;
        }
        return new SubjectScope(active);
    }

    /**
     * Builds the parameterized visibility predicate over the document alias {@code d}.
     */
    private static String visibilityPredicate(SubjectScope scope, MapSqlParameterSource params) {
        if (scope.isFiltered()) {
            params.addValue("subjectIds", scope.activeSubjectIds());
            return """
                EXISTS (SELECT 1 FROM document_subject ds
                        JOIN exegese_subject s ON s.id = ds.subject_id
                        WHERE ds.document_id = d.id AND s.active = TRUE AND ds.subject_id IN (:subjectIds))
            """;
        }
        return """
            (EXISTS (SELECT 1 FROM document_subject ds
                     JOIN exegese_subject s ON s.id = ds.subject_id
                     WHERE ds.document_id = d.id AND s.active = TRUE)
             OR NOT EXISTS (SELECT 1 FROM document_subject ds_any WHERE ds_any.document_id = d.id))
        """;
    }

    /**
     * Semantic candidates ordered by cosine distance. Chunks without a vector (NULL) are skipped; legacy
     * zero vectors yield a NaN distance in pgvector and are discarded as well.
     */
    private List<RetrievedChunk> retrieveVectorCandidates(String query, SubjectScope scope) {
        if (!isPostgres) {
            // Vector columns only exist on PostgreSQL (pgvector)
            return List.of();
        }

        float[] embedding;
        try {
            embedding = embeddingService.embed(query);
        } catch (EmbeddingException e) {
            if (e.isNotConfigured()) {
                if (embeddingsUnavailableLogged.compareAndSet(false, true)) {
                    log.warn("Semantic search disabled: no embedding provider configured (GEMINI_API_KEY). "
                            + "Answers rely on full-text retrieval only; this warning is logged once.");
                }
            } else {
                log.warn("Query embedding failed, using full-text retrieval only: {}", e.getMessage());
            }
            return List.of();
        }

        try {
            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("vector", EmbeddingService.toPgVector(embedding));
            String sql = """
                SELECT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                       c.content, c.sequence_number, c.metadata,
                       (c.embedding <=> cast(:vector as vector)) as distance
                FROM exegese_chunk c
                JOIN exegese_document d ON c.document_id = d.id
                WHERE c.embedding IS NOT NULL AND
            """ + visibilityPredicate(scope, params) + " ORDER BY distance ASC LIMIT " + CANDIDATE_LIMIT;

            List<RetrievedChunk> rows = jdbcTemplate.query(sql, params, (var rs, @SuppressWarnings("unused") var rowNum) ->
                    mapChunk(rs, 1.0 - rs.getDouble("distance"), 0.0, false));
            return rows.stream().filter(c -> c.vectorSimilarity() != null && !c.vectorSimilarity().isNaN()).toList();
        } catch (DataAccessException e) {
            log.warn("PostgreSQL vector retrieval failed ({}), using full-text retrieval only",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<RetrievedChunk> retrieveLexicalCandidates(String query, SubjectScope scope) {
        LexicalQuery lexicalQuery = LexicalQuery.of(query);
        return isPostgres
                ? postgresLexicalCandidates(query, lexicalQuery, scope)
                : scannedLexicalCandidates(lexicalQuery, scope);
    }

    /**
     * PostgreSQL lexical retrieval: strict full-text matches (every query term, ranked by {@code ts_rank_cd})
     * first, completed by relaxed matches (any significant term) re-scored by the term-overlap heuristic.
     */
    private List<RetrievedChunk> postgresLexicalCandidates(String query, LexicalQuery lexicalQuery, SubjectScope scope) {
        List<RetrievedChunk> result = new ArrayList<>();
        try {
            MapSqlParameterSource params = new MapSqlParameterSource("query", query);
            String strictSql = """
                SELECT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                       c.content, c.sequence_number, c.metadata,
                       ts_rank_cd(c.tsv, plainto_tsquery('portuguese', :query)) as rank
                FROM exegese_chunk c
                JOIN exegese_document d ON c.document_id = d.id
                WHERE c.tsv @@ plainto_tsquery('portuguese', :query) AND
            """ + visibilityPredicate(scope, params) + " ORDER BY rank DESC LIMIT " + CANDIDATE_LIMIT;

            result.addAll(jdbcTemplate.query(strictSql, params, (var rs, @SuppressWarnings("unused") var rowNum) ->
                    mapChunk(rs, null, lexicalQuery.score(rs.getString("chunk_title"), rs.getString("content")), true)));
        } catch (DataAccessException e) {
            log.warn("PostgreSQL strict full-text retrieval failed ({})", e.getClass().getSimpleName());
        }

        if (result.size() >= CANDIDATE_LIMIT || lexicalQuery.tsQueryTerms().isEmpty()) {
            return result;
        }

        try {
            MapSqlParameterSource params = new MapSqlParameterSource("anyTerm", String.join(" | ", lexicalQuery.tsQueryTerms()));
            String relaxedSql = """
                SELECT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                       c.content, c.sequence_number, c.metadata
                FROM exegese_chunk c
                JOIN exegese_document d ON c.document_id = d.id
                WHERE c.tsv @@ to_tsquery('portuguese', :anyTerm) AND
            """ + visibilityPredicate(scope, params)
                    + " ORDER BY ts_rank_cd(c.tsv, to_tsquery('portuguese', :anyTerm)) DESC LIMIT " + RELAXED_CANDIDATE_LIMIT;

            List<RetrievedChunk> relaxed = jdbcTemplate.query(relaxedSql, params, (var rs, @SuppressWarnings("unused") var rowNum) ->
                    mapChunk(rs, null, lexicalQuery.score(rs.getString("chunk_title"), rs.getString("content")), false));

            Set<UUID> seen = new LinkedHashSet<>();
            result.forEach(c -> seen.add(c.id()));
            for (RetrievedChunk candidate : bestByLexicalScore(relaxed)) {
                if (result.size() >= CANDIDATE_LIMIT) {
                    break;
                }
                if (seen.add(candidate.id())) {
                    result.add(candidate);
                }
            }
        } catch (DataAccessException e) {
            log.warn("PostgreSQL relaxed full-text retrieval failed ({})", e.getClass().getSimpleName());
        }
        return result;
    }

    /**
     * Non-PostgreSQL lexical retrieval (H2 test databases): a bounded scan of visible chunks scored by the
     * term-overlap heuristic.
     */
    private List<RetrievedChunk> scannedLexicalCandidates(LexicalQuery lexicalQuery, SubjectScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
            SELECT c.id, c.document_id, d.title as doc_title, c.title as chunk_title,
                   c.content, c.sequence_number, c.metadata
            FROM exegese_chunk c
            JOIN exegese_document d ON c.document_id = d.id
            WHERE
        """ + visibilityPredicate(scope, params) + " LIMIT " + SCAN_LIMIT;

        List<RetrievedChunk> scanned = jdbcTemplate.query(sql, params, (var rs, @SuppressWarnings("unused") var rowNum) ->
                mapChunk(rs, null, lexicalQuery.score(rs.getString("chunk_title"), rs.getString("content")), false));
        return bestByLexicalScore(scanned);
    }

    /** Candidates with a positive heuristic score, best first, at most {@link #CANDIDATE_LIMIT}. */
    private static List<RetrievedChunk> bestByLexicalScore(List<RetrievedChunk> candidates) {
        return candidates.stream()
                .filter(c -> c.lexicalScore() > 0.0)
                .sorted((a, b) -> Double.compare(b.lexicalScore(), a.lexicalScore()))
                .limit(CANDIDATE_LIMIT)
                .toList();
    }

    private static RetrievedChunk mapChunk(ResultSet rs, Double vectorSimilarity, double lexicalScore,
                                           boolean fullTextMatch) throws SQLException {
        return new RetrievedChunk(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("document_id")),
                rs.getString("doc_title"),
                rs.getString("chunk_title"),
                rs.getString("content"),
                rs.getInt("sequence_number"),
                rs.getString("metadata"),
                vectorSimilarity,
                lexicalScore,
                fullTextMatch
        );
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
            acc.vectorSimilarity = c.vectorSimilarity();
            acc.score += 1.0 / (RRF_K + rank);
        }

        // Process text candidates (1-based ranking)
        for (int rank = 1; rank <= textCandidates.size(); rank++) {
            RetrievedChunk c = textCandidates.get(rank - 1);
            RrfAccumulator acc = map.computeIfAbsent(c.id(), (@SuppressWarnings("unused") var k) -> new RrfAccumulator(c));
            acc.textRank = rank;
            acc.lexicalScore = c.lexicalScore();
            acc.fullTextMatch = c.fullTextMatch();
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
                    acc.textRank,
                    acc.vectorSimilarity,
                    acc.lexicalScore,
                    acc.fullTextMatch
            ));
        }

        return result;
    }

    /**
     * Pre-processed user query for lexical matching: the accent-free normalized text and terms used by the
     * term-overlap heuristic, plus the significant raw terms (letters and digits only, hence safe inside
     * {@code to_tsquery}) used by the relaxed PostgreSQL full-text query.
     */
    private record LexicalQuery(String normalizedQuery, String[] terms, List<String> tsQueryTerms) {

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

        static LexicalQuery of(String query) {
            String normalized = normalizeText(query);
            String[] terms = normalized.split("\\s+");

            Set<String> tsTerms = new LinkedHashSet<>();
            for (String raw : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (raw.length() < 2 || STOP_WORDS.contains(normalizeText(raw).trim())) {
                    continue;
                }
                tsTerms.add(raw);
                if (tsTerms.size() >= MAX_QUERY_TERMS) {
                    break;
                }
            }
            return new LexicalQuery(normalized, terms, List.copyOf(tsTerms));
        }

        /**
         * Term-overlap relevance of a chunk: title matches weigh more than content matches, numbers/codes
         * more than words, ubiquitous domain words (imposto, declaração...) almost nothing. Spurious matches
         * (no title term and fewer than two content terms for a longer question) score zero.
         */
        double score(String chunkTitle, String content) {
            String normTitle = normalizeText(chunkTitle);
            String normContent = normalizeText(content);
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

                if (matchesTermOrStem(normTitle, term)) {
                    matchedTitleTerms++;
                    score += termWeight * (isUbiquitous ? 1.5 : 5.0);
                }
                if (matchesTermOrStem(normContent, term)) {
                    matchedContentTerms++;
                    score += termWeight;
                }
            }

            // Reject spurious matches with zero title matches and insufficient content matches
            if (matchedTitleTerms == 0 && (matchedContentTerms < 2 && terms.length >= 3)) {
                score = 0.0;
            }
            return score;
        }

        private static String normalizeText(String text) {
            if (text == null) {
                return "";
            }
            String nfd = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
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
    }

    private record RetrievedChunk(
            UUID id,
            UUID documentId,
            String docTitle,
            String chunkTitle,
            String content,
            int sequenceNumber,
            String metadata,
            Double vectorSimilarity,
            double lexicalScore,
            boolean fullTextMatch
    ) {}

    /**
     * Subject restriction of one search: the active subject ids requested by the user, or
     * an empty list for the default visibility scope.
     */
    private record SubjectScope(List<UUID> activeSubjectIds) {
        static final SubjectScope DEFAULT_VISIBILITY = new SubjectScope(List.of());

        boolean isFiltered() {
            return !activeSubjectIds.isEmpty();
        }
    }

    private static class RrfAccumulator {
        private final RetrievedChunk chunk;
        private double score = 0.0;
        private int vectorRank = Integer.MAX_VALUE;
        private int textRank = Integer.MAX_VALUE;
        private Double vectorSimilarity;
        private double lexicalScore;
        private boolean fullTextMatch;

        public RrfAccumulator(RetrievedChunk chunk) {
            this.chunk = chunk;
        }
    }
}
