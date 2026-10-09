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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HybridSearchService} targeting PostgreSQL pgvector queries, full-text tsquery execution,
 * and edge cases of data source inspection and error handling.
 *
 * @author Rivelino Patrício
 */
class HybridSearchServiceUnitTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(dataSource.getConnection()).thenReturn(conn);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("PostgreSQL vector retrieval performs query and handles vector results")
    void testPostgresVectorRetrievalSuccess() throws SQLException {
        HybridSearchService searchService = new HybridSearchService(jdbcTemplate, embeddingService, dataSource);
        when(embeddingService.embed("imposto de renda")).thenReturn(new float[]{0.1f, 0.2f});

        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(chunkId.toString());
        when(rs.getString("document_id")).thenReturn(docId.toString());
        when(rs.getString("doc_title")).thenReturn("Guia IRPF");
        when(rs.getString("chunk_title")).thenReturn("Secao 1");
        when(rs.getString("content")).thenReturn("Conteudo explicativo de imposto de renda");
        when(rs.getInt("sequence_number")).thenReturn(1);
        when(rs.getString("metadata")).thenReturn("{}");
        when(rs.getDouble("distance")).thenReturn(0.15);

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("vector")) {
                RowMapper<?> mapper = invocation.getArgument(2);
                Object mapped = mapper.mapRow(rs, 1);
                return List.of(mapped);
            }
            return List.of();
        }).when(jdbcTemplate).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));

        List<SearchResultChunk> results = searchService.search("imposto de renda", List.of(), 5);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkId()).isEqualTo(chunkId);
    }

    @Test
    @DisplayName("PostgreSQL vector retrieval gracefully handles unconfigured and failing embeddings")
    void testPostgresVectorRetrievalEmbeddingFailures() {
        HybridSearchService searchService = new HybridSearchService(jdbcTemplate, embeddingService, dataSource);

        // Unconfigured embedding model
        when(embeddingService.embed("imposto")).thenThrow(EmbeddingException.notConfigured());
        List<SearchResultChunk> resUnconfigured = searchService.search("imposto", List.of(), 5);
        assertThat(resUnconfigured).isEmpty();

        // Runtime embedding failure (network/timeout)
        when(embeddingService.embed("falha")).thenThrow(new EmbeddingException("Timeout"));
        List<SearchResultChunk> resFailed = searchService.search("falha", List.of(), 5);
        assertThat(resFailed).isEmpty();
    }

    @SuppressWarnings("unchecked")
	@Test
    @DisplayName("PostgreSQL vector retrieval handles DataAccessException from database")
    void testPostgresVectorRetrievalDataAccessException() {
        HybridSearchService searchService = new HybridSearchService(jdbcTemplate, embeddingService, dataSource);
        when(embeddingService.embed("imposto")).thenReturn(new float[]{0.1f, 0.2f});
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("vector")),
                any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new QueryTimeoutException("Vector query timeout"));

        List<SearchResultChunk> results = searchService.search("imposto", List.of(), 5);
        assertThat(results).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("PostgreSQL lexical search executes strict and relaxed tsqueries and fuses results")
    void testPostgresLexicalSearchStrictAndRelaxed() throws SQLException {
        HybridSearchService searchService = new HybridSearchService(jdbcTemplate, embeddingService, dataSource);
        when(embeddingService.embed(anyString())).thenThrow(EmbeddingException.notConfigured());

        UUID chunkId1 = UUID.randomUUID();
        UUID chunkId2 = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        ResultSet rs1 = mock(ResultSet.class);
        when(rs1.getString("id")).thenReturn(chunkId1.toString());
        when(rs1.getString("document_id")).thenReturn(docId.toString());
        when(rs1.getString("doc_title")).thenReturn("Doc 1");
        when(rs1.getString("chunk_title")).thenReturn("Titulo 1");
        when(rs1.getString("content")).thenReturn("Deducoes tributaveis");
        when(rs1.getInt("sequence_number")).thenReturn(1);
        when(rs1.getString("metadata")).thenReturn("{}");

        ResultSet rs2 = mock(ResultSet.class);
        when(rs2.getString("id")).thenReturn(chunkId2.toString());
        when(rs2.getString("document_id")).thenReturn(docId.toString());
        when(rs2.getString("doc_title")).thenReturn("Doc 2");
        when(rs2.getString("chunk_title")).thenReturn("Titulo 2");
        when(rs2.getString("content")).thenReturn("Outras despesas medicas");
        when(rs2.getInt("sequence_number")).thenReturn(2);
        when(rs2.getString("metadata")).thenReturn("{}");

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(2);
            if (sql.contains("plainto_tsquery")) {
                return List.of(mapper.mapRow(rs1, 1));
            } else if (sql.contains("to_tsquery")) {
                return List.of(mapper.mapRow(rs2, 1));
            }
            return List.of();
        }).when(jdbcTemplate).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));

        List<SearchResultChunk> results = searchService.search("deducoes despesas medicas", List.of(), 10);
        assertThat(results).hasSize(2);
    }

    @SuppressWarnings("unchecked")
	@Test
    @DisplayName("PostgreSQL lexical search handles DataAccessException during strict and relaxed queries")
    void testPostgresLexicalSearchDatabaseExceptions() {
        HybridSearchService searchService = new HybridSearchService(jdbcTemplate, embeddingService, dataSource);
        when(embeddingService.embed(anyString())).thenThrow(EmbeddingException.notConfigured());

        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new QueryTimeoutException("TSQuery error"));

        List<SearchResultChunk> results = searchService.search("termo busca", List.of());
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("checkPostgreSql handles null product name and SQLException")
    void testCheckPostgreSqlVariations() throws SQLException {
        DataSource errorDs = mock(DataSource.class);
        when(errorDs.getConnection()).thenThrow(new SQLException("DB unavailable"));
        HybridSearchService errorDsService = new HybridSearchService(jdbcTemplate, embeddingService, errorDs);
        assertThat(errorDsService.search("teste", List.of())).isEmpty();

        DataSource nullProductDs = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(nullProductDs.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(null);
        HybridSearchService nullProductService = new HybridSearchService(jdbcTemplate, embeddingService, nullProductDs);
        assertThat(nullProductService.search("teste", List.of())).isEmpty();
    }
}
