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

import br.org.rivelino.exegese_ai.config.DatabaseSchemaInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit and integration tests for the DatabaseSchemaInitializer component.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
class DatabaseSchemaInitializerTest {

    @Autowired
    private DatabaseSchemaInitializer schemaInitializer;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Initializer runs gracefully on in-memory H2 without errors")
    void testInitializerRunsGracefullyOnH2() {
        assertThatCode(() -> schemaInitializer.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Initializer executes pgvector DDL statements when PostgreSQL is detected")
    void testInitializerExecutesDdlOnPostgreSql() throws SQLException {
        DataSource mockDataSource = mock(DataSource.class);
        Connection mockConnection = mock(Connection.class);
        DatabaseMetaData mockMetaData = mock(DatabaseMetaData.class);
        JdbcTemplate mockJdbcTemplate = mock(JdbcTemplate.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        DatabaseSchemaInitializer initializer = new DatabaseSchemaInitializer(mockJdbcTemplate, mockDataSource);
        initializer.run(new DefaultApplicationArguments());

        verify(mockJdbcTemplate).execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
        verify(mockJdbcTemplate).execute("CREATE EXTENSION IF NOT EXISTS \"vector\"");
        verify(mockJdbcTemplate).execute("ALTER TABLE IF EXISTS exegese_chunk ADD COLUMN IF NOT EXISTS embedding VECTOR(768)");
        verify(mockJdbcTemplate).execute(contains("ALTER TABLE IF EXISTS exegese_chunk ADD COLUMN IF NOT EXISTS tsv TSVECTOR"));
        verify(mockJdbcTemplate).execute(contains("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_hnsw"));
        verify(mockJdbcTemplate).execute("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_tsv ON exegese_chunk USING gin (tsv)");
        verify(mockJdbcTemplate).execute("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_metadata ON exegese_chunk USING gin (metadata jsonb_path_ops)");
        verify(mockJdbcTemplate).execute("CREATE INDEX IF NOT EXISTS idx_exegese_chunk_doc ON exegese_chunk (document_id)");
        verify(mockJdbcTemplate).execute("CREATE INDEX IF NOT EXISTS idx_doc_subject_subject ON document_subject (subject_id)");
    }
}
