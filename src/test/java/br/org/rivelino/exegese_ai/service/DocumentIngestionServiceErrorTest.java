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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.segmentation.SegmentationStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentIngestionService} covering failure paths, PostgreSQL writes, and edge cases.
 *
 * @author Rivelino Patrício
 */
class DocumentIngestionServiceErrorTest {

    @Mock
    private ExegeseDocumentRepository documentRepository;

    @Mock
    private ExegeseChunkRepository chunkRepository;

    @Mock
    private ExegeseSubjectRepository subjectRepository;

    @Mock
    private PdfTextExtractor pdfTextExtractor;

    @Mock
    private SegmentationStrategyFactory strategyFactory;

    @Mock
    private EmbeddingReindexService embeddingReindexService;

    @Mock
    private DocumentStorageService storageService;

    @Mock
    private DocumentIngestionExecutor ingestionExecutor;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private DataSource dataSource;

    private JsonMapper jsonMapper;
    private DocumentIngestionService ingestionService;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        jsonMapper = JsonMapper.builder().build();

        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);

        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(dataSource.getConnection()).thenReturn(conn);

        ingestionService = new DocumentIngestionService(
                documentRepository,
                chunkRepository,
                subjectRepository,
                pdfTextExtractor,
                strategyFactory,
                embeddingReindexService,
                storageService,
                ingestionExecutor,
                jsonMapper,
                jdbcTemplate,
                transactionManager,
                dataSource
        );
    }

    @Test
    @DisplayName("submitDocument marks document failed when execution is rejected by the executor")
    void testSubmitDocumentRejectedExecution() throws IOException {
        UUID docId = UUID.randomUUID();
        ExegeseDocument doc = new ExegeseDocument("Title", "file.pdf", "path/to/file.pdf", "sha256", 100L, "application/pdf");
        doc.setId(docId);

        when(storageService.store(any(ByteArrayInputStream.class)))
                .thenReturn(new DocumentStorageService.StoredPdf("path/to/file.pdf", "sha256", 100L));
        when(documentRepository.findByFileHashSha256("sha256")).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any())).thenReturn(doc);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        doAnswer(invocation -> {
            throw new RejectedExecutionException("Executor queue full");
        }).when(ingestionExecutor).execute(any());

        ingestionService.submitDocument("Title", "file.pdf", new ByteArrayInputStream("PDF".getBytes()), List.of(), SegmentationStrategyType.STRUCTURED_QA);

        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).containsIgnoringCase("interrupted");
    }

    @Test
    @DisplayName("ingestDocument handles PdfTextExtractor IOException and marks document FAILED")
    void testIngestDocumentExtractionIoException() throws IOException {
        UUID docId = UUID.randomUUID();
        ExegeseDocument doc = new ExegeseDocument("Doc Title", "doc.pdf", "path/doc.pdf", "sha256", 100L, "application/pdf");
        doc.setId(docId);

        when(storageService.store(any(byte[].class)))
                .thenReturn(new DocumentStorageService.StoredPdf("path/doc.pdf", "sha256", 100L));
        when(documentRepository.findByFileHashSha256("sha256")).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any())).thenReturn(doc);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(storageService.resolve("path/doc.pdf")).thenReturn(Path.of("path/doc.pdf"));
        when(pdfTextExtractor.extract((Path) any())).thenThrow(new IOException("Corrupted PDF"));

        assertThatThrownBy(() -> ingestionService.ingestDocument("Doc Title", "doc.pdf", "bad-pdf".getBytes(), List.of(), SegmentationStrategyType.STRUCTURED_QA))
                .isInstanceOf(IOException.class);

        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).contains("could not be read as a PDF");
    }

    @Test
    @DisplayName("ingestDocument handles DocumentRejectedException and marks document FAILED")
    void testIngestDocumentRejection() throws IOException {
        UUID docId = UUID.randomUUID();
        ExegeseDocument doc = new ExegeseDocument("Doc Title", "doc.pdf", "path/doc.pdf", "sha256", 100L, "application/pdf");
        doc.setId(docId);

        when(storageService.store(any(byte[].class)))
                .thenReturn(new DocumentStorageService.StoredPdf("path/doc.pdf", "sha256", 100L));
        when(documentRepository.findByFileHashSha256("sha256")).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any())).thenReturn(doc);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(storageService.resolve("path/doc.pdf")).thenReturn(Path.of("path/doc.pdf"));
        when(pdfTextExtractor.extract((Path) any())).thenThrow(new DocumentRejectedException(DocumentRejectedException.Reason.NOT_PDF, "File is not PDF"));

        assertThatThrownBy(() -> ingestionService.ingestDocument("Doc Title", "doc.pdf", "not-pdf".getBytes(), List.of(), SegmentationStrategyType.STRUCTURED_QA))
                .isInstanceOf(DocumentRejectedException.class);

        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).contains("File is not PDF");
    }

    @Test
    @DisplayName("failInterruptedIngestions updates processing documents to failed")
    void testFailInterruptedIngestions() {
        when(documentRepository.updateStatus(eq("PROCESSING"), eq("FAILED"), anyString(), any()))
                .thenReturn(3);

        int updated = ingestionService.failInterruptedIngestions();
        assertThat(updated).isEqualTo(3);
    }

    @Test
    @DisplayName("truncate trims long strings with ellipsis and handles short or null strings")
    void testTruncate() {
        assertThat(DocumentIngestionService.truncate(null, 10)).isNull();
        assertThat(DocumentIngestionService.truncate("curto", 10)).isEqualTo("curto");
        assertThat(DocumentIngestionService.truncate("texto muito longo para caber", 10)).isEqualTo("texto mui…");
    }
}
