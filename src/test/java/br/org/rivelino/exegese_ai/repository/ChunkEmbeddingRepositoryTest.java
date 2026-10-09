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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link ChunkEmbeddingRepository} validating vector support detection and progress calculations.
 *
 * @author Rivelino Patrício
 */
class ChunkEmbeddingRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @Test
    @DisplayName("supportsVectors returns false for non-PostgreSQL database")
    void testSupportsVectorsNonPostgres() {
        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        assertThat(repository.supportsVectors()).isFalse();
        assertThat(repository.embeddingProgressByDocument()).isEmpty();
    }

    @Test
    @DisplayName("supportsVectors returns true for PostgreSQL database")
    void testSupportsVectorsPostgres() throws SQLException {
        DatabaseMetaData pgMeta = mock(DatabaseMetaData.class);
        Connection pgConn = mock(Connection.class);
        when(pgConn.getMetaData()).thenReturn(pgMeta);
        when(pgMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(dataSource.getConnection()).thenReturn(pgConn);

        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        assertThat(repository.supportsVectors()).isTrue();
    }

    @Test
    @DisplayName("supportsVectors handles SQLException gracefully and defaults to false")
    void testSupportsVectorsSqlException() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("DB connection failed"));
        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        assertThat(repository.supportsVectors()).isFalse();
    }

    @Test
    @DisplayName("supportsVectors handles null database product name")
    void testSupportsVectorsNullProduct() throws SQLException {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(null);
        when(dataSource.getConnection()).thenReturn(conn);

        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        assertThat(repository.supportsVectors()).isFalse();
    }

    @Test
    @DisplayName("EmbeddingProgress complete method correctly compares embedded vs total")
    void testEmbeddingProgressComplete() {
        ChunkEmbeddingRepository.EmbeddingProgress inProgress = new ChunkEmbeddingRepository.EmbeddingProgress(5, 10);
        assertThat(inProgress.complete()).isFalse();

        ChunkEmbeddingRepository.EmbeddingProgress done = new ChunkEmbeddingRepository.EmbeddingProgress(10, 10);
        assertThat(done.complete()).isTrue();

        ChunkEmbeddingRepository.EmbeddingProgress overflow = new ChunkEmbeddingRepository.EmbeddingProgress(12, 10);
        assertThat(overflow.complete()).isTrue();
    }

    @SuppressWarnings("unchecked")
	@Test
    @DisplayName("updateEmbeddings returns immediately when embeddings map is empty")
    void testUpdateEmbeddingsEmptyMap() {
        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        repository.updateEmbeddings(Map.of());

        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    @DisplayName("updateEmbeddings executes batch update when embeddings map contains entries")
    void testUpdateEmbeddingsNonEmptyMap() {
        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        UUID chunkId = UUID.randomUUID();
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};

        repository.updateEmbeddings(Map.of(chunkId, vector));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(anyString(), argsCaptor.capture());

        List<Object[]> batchArgs = argsCaptor.getValue();
        assertThat(batchArgs).hasSize(1);
        assertThat(batchArgs.get(0)[1]).isEqualTo(chunkId);
    }

    @Test
    @DisplayName("countChunks queries jdbcTemplate for chunk count and handles null")
    void testCountChunks() {
        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);

        long count = repository.countChunks(true);
        assertThat(count).isEqualTo(42L);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
        long countNull = repository.countChunks(false);
        assertThat(countNull).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("findBatch executes keyset query and maps pending chunks")
    void testFindBatch() throws SQLException {
        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        UUID chunkId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(chunkId.toString());
        when(rs.getString("title")).thenReturn("Chunk Title");
        when(rs.getString("content")).thenReturn("Chunk Content");

        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            Object mapped = mapper.mapRow(rs, 1);
            return List.of(mapped);
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(afterId), eq(10));

        List<ChunkEmbeddingRepository.PendingChunk> chunksForceAll = repository.findBatch(true, afterId, 10);
        assertThat(chunksForceAll).hasSize(1);
        assertThat(chunksForceAll.get(0).id()).isEqualTo(chunkId);
        assertThat(chunksForceAll.get(0).title()).isEqualTo("Chunk Title");
        assertThat(chunksForceAll.get(0).content()).isEqualTo("Chunk Content");

        List<ChunkEmbeddingRepository.PendingChunk> chunksMissingOnly = repository.findBatch(false, afterId, 10);
        assertThat(chunksMissingOnly).hasSize(1);
    }

    @Test
    @DisplayName("embeddingProgressByDocument queries progress when running on PostgreSQL")
    void testEmbeddingProgressByDocumentPostgres() throws SQLException {
        DatabaseMetaData pgMeta = mock(DatabaseMetaData.class);
        Connection pgConn = mock(Connection.class);
        when(pgConn.getMetaData()).thenReturn(pgMeta);
        when(pgMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(dataSource.getConnection()).thenReturn(pgConn);

        ChunkEmbeddingRepository repository = new ChunkEmbeddingRepository(jdbcTemplate, dataSource);
        UUID docId = UUID.randomUUID();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("document_id")).thenReturn(docId.toString());
        when(rs.getLong("embedded")).thenReturn(8L);
        when(rs.getLong("total")).thenReturn(10L);

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));

        Map<UUID, ChunkEmbeddingRepository.EmbeddingProgress> progress = repository.embeddingProgressByDocument();
        assertThat(progress).containsEntry(docId, new ChunkEmbeddingRepository.EmbeddingProgress(8L, 10L));
    }
}
