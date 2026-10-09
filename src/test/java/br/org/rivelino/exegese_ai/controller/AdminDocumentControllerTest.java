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
package br.org.rivelino.exegese_ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ChunkEmbeddingRepository;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.DocumentRejectedException;
import br.org.rivelino.exegese_ai.service.EmbeddingReindexService;
import br.org.rivelino.exegese_ai.service.EmbeddingService;
import br.org.rivelino.exegese_ai.service.SubjectCatalogService;

/**
 * Unit tests for {@link AdminDocumentController} validating document catalog listing, upload, reprocessing,
 * reindexing endpoints, and error handling.
 *
 * @author Rivelino Patrício
 */
class AdminDocumentControllerTest {

    @Mock
    private SubjectCatalogService catalogService;

    @Mock
    private DocumentIngestionService ingestionService;

    @Mock
    private EmbeddingReindexService reindexService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChunkEmbeddingRepository chunkEmbeddingRepository;

    @Mock
    private MessageSource messageSource;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        AdminDocumentController controller = new AdminDocumentController(
                catalogService, ingestionService, reindexService, embeddingService,
                chunkEmbeddingRepository, messageSource);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reindexService.status()).thenReturn(new EmbeddingReindexService.ReindexStatus(
                EmbeddingReindexService.State.IDLE, false, 0, 0, 0, null, null, null, null));
        when(chunkEmbeddingRepository.embeddingProgressByDocument()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("listDocuments populates view model with catalog data")
    void testListDocuments() throws Exception {
        UUID subjectId = UUID.randomUUID();
        when(catalogService.listDocuments(subjectId, "INDEXED")).thenReturn(List.of());
        when(catalogService.findAllActive()).thenReturn(List.of(new ExegeseSubject("TAX", "Taxation", "Desc")));
        when(embeddingService.isConfigured()).thenReturn(true);

        mockMvc.perform(get("/admin/documents")
                        .param("subjectId", subjectId.toString())
                        .param("status", "INDEXED"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/documents"))
                .andExpect(model().attributeExists("documents", "subjects", "reindexStatus", "embeddingConfigured"));
    }

    @Test
    @DisplayName("uploadDocument returns error flash attribute when file is empty")
    void testUploadEmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/admin/documents/upload").file(emptyFile))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("uploadDocument returns error flash attribute when document is rejected (not a PDF)")
    void testUploadNonPdfFile() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());

        when(ingestionService.submitDocument(anyString(), anyString(), any(InputStream.class), any(), any(SegmentationStrategyType.class)))
                .thenThrow(new DocumentRejectedException(DocumentRejectedException.Reason.NOT_PDF, "Not a PDF"));

        mockMvc.perform(multipart("/admin/documents/upload").file(textFile))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("uploadDocument sets success flash attribute when upload outcome is QUEUED")
    void testUploadSuccessQueued() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 test".getBytes();
        MockMultipartFile pdfFile = new MockMultipartFile("file", "norma.pdf", "application/pdf", pdfBytes);
        ExegeseDocument doc = new ExegeseDocument("Norma Teste", "norma.pdf", "target/norma.pdf", "hash123", (long) pdfBytes.length, "application/pdf");

        when(ingestionService.submitDocument(anyString(), anyString(), any(InputStream.class), any(), any(SegmentationStrategyType.class)))
                .thenReturn(new DocumentIngestionService.UploadOutcome(doc, DocumentIngestionService.UploadState.QUEUED));
        when(embeddingService.isConfigured()).thenReturn(true);

        mockMvc.perform(multipart("/admin/documents/upload")
                        .file(pdfFile)
                        .param("title", "Norma Teste"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @DisplayName("uploadDocument handles unexpected ingestion exception")
    void testUploadUnexpectedException() throws Exception {
        MockMultipartFile pdfFile = new MockMultipartFile("file", "norma.pdf", "application/pdf", "%PDF-1.4".getBytes());
        when(ingestionService.submitDocument(anyString(), anyString(), any(InputStream.class), any(), any(SegmentationStrategyType.class)))
                .thenThrow(new RuntimeException("DB Connection failed"));

        mockMvc.perform(multipart("/admin/documents/upload").file(pdfFile))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("reprocessDocument handles all ReprocessOutcome enum values")
    void testReprocessDocumentOutcomes() throws Exception {
        UUID docId = UUID.randomUUID();

        when(ingestionService.reprocessDocument(docId))
                .thenReturn(DocumentIngestionService.ReprocessOutcome.QUEUED)
                .thenReturn(DocumentIngestionService.ReprocessOutcome.NOT_FOUND)
                .thenReturn(DocumentIngestionService.ReprocessOutcome.NOT_FAILED)
                .thenReturn(DocumentIngestionService.ReprocessOutcome.FILE_MISSING);

        mockMvc.perform(post("/admin/documents/" + docId + "/reprocess"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        mockMvc.perform(post("/admin/documents/" + docId + "/reprocess"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        mockMvc.perform(post("/admin/documents/" + docId + "/reprocess"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        mockMvc.perform(post("/admin/documents/" + docId + "/reprocess"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("reprocessDocument handles unexpected runtime exception")
    void testReprocessDocumentException() throws Exception {
        UUID docId = UUID.randomUUID();
        when(ingestionService.reprocessDocument(docId)).thenThrow(new RuntimeException("Storage error"));

        mockMvc.perform(post("/admin/documents/" + docId + "/reprocess"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("reindexEmbeddings handles all StartOutcome enum values")
    void testReindexEmbeddingsOutcomes() throws Exception {
        when(reindexService.start(anyBoolean()))
                .thenReturn(EmbeddingReindexService.StartOutcome.STARTED)
                .thenReturn(EmbeddingReindexService.StartOutcome.ALREADY_RUNNING)
                .thenReturn(EmbeddingReindexService.StartOutcome.NOT_CONFIGURED)
                .thenReturn(EmbeddingReindexService.StartOutcome.UNSUPPORTED_DATABASE);

        mockMvc.perform(post("/admin/documents/reindex-embeddings").param("forceAll", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        mockMvc.perform(post("/admin/documents/reindex-embeddings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        mockMvc.perform(post("/admin/documents/reindex-embeddings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        mockMvc.perform(post("/admin/documents/reindex-embeddings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("reindexEmbeddings handles unexpected runtime exception")
    void testReindexEmbeddingsException() throws Exception {
        when(reindexService.start(anyBoolean())).thenThrow(new RuntimeException("Reindex failed"));

        mockMvc.perform(post("/admin/documents/reindex-embeddings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/documents"))
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
