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

import br.org.rivelino.exegese_ai.domain.dto.DocumentSummaryDTO;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ChunkEmbeddingRepository;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.DocumentRejectedException;
import br.org.rivelino.exegese_ai.service.DocumentStorageService;
import br.org.rivelino.exegese_ai.service.EmbeddingReindexService;
import br.org.rivelino.exegese_ai.service.EmbeddingService;
import br.org.rivelino.exegese_ai.service.ErrorReference;
import br.org.rivelino.exegese_ai.service.SubjectCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Administrative controller for viewing cataloged documents, statuses and uploading new normative files
 * (uploads are indexed asynchronously; the catalog shows PROCESSING / INDEXED / FAILED).
 * It also starts the background embedding reindexing job (CSRF-protected POST) and shows its progress.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/documents")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminDocumentController {

    private static final Logger log = LoggerFactory.getLogger(AdminDocumentController.class);

    private final SubjectCatalogService catalogService;
    private final DocumentIngestionService ingestionService;
    private final EmbeddingReindexService reindexService;
    private final EmbeddingService embeddingService;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final MessageSource messageSource;

    public AdminDocumentController(SubjectCatalogService catalogService,
                                   DocumentIngestionService ingestionService,
                                   EmbeddingReindexService reindexService,
                                   EmbeddingService embeddingService,
                                   ChunkEmbeddingRepository chunkEmbeddingRepository,
                                   MessageSource messageSource) {
        this.catalogService = catalogService;
        this.ingestionService = ingestionService;
        this.reindexService = reindexService;
        this.embeddingService = embeddingService;
        this.chunkEmbeddingRepository = chunkEmbeddingRepository;
        this.messageSource = messageSource;
    }

    @GetMapping
    public String listDocuments(@RequestParam(required = false) UUID subjectId,
                                @RequestParam(required = false) String status,
                                Model model) {
        List<DocumentSummaryDTO> documents = catalogService.listDocuments(subjectId, status);
        List<ExegeseSubject> subjects = catalogService.findAllActive();

        model.addAttribute("documents", documents);
        model.addAttribute("subjects", subjects);
        model.addAttribute("selectedSubjectId", subjectId);
        model.addAttribute("selectedStatus", status);
        EmbeddingReindexService.ReindexStatus reindexStatus = reindexService.status();
        model.addAttribute("reindexStatus", reindexStatus);
        model.addAttribute("reindexWaitSeconds", reindexStatus.waitingForQuota()
                ? Math.max(1, Duration.between(Instant.now(), reindexStatus.waitingUntil()).toSeconds())
                : null);
        model.addAttribute("embeddingConfigured", embeddingService.isConfigured());
        model.addAttribute("embeddingProgress", chunkEmbeddingRepository.embeddingProgressByDocument());
        model.addAttribute("processingDocuments", documents.stream()
                .anyMatch(doc -> DocumentIngestionService.STATUS_PROCESSING.equals(doc.status())));

        return "admin/documents";
    }

    /**
     * Accepts a PDF upload: the file is validated ({@code %PDF-} signature) and stored, the document is
     * registered with status {@code PROCESSING} and the request returns immediately; extraction, segmentation
     * and embeddings run in the background and the catalog shows the resulting status.
     */
    @PostMapping("/upload")
    public String uploadDocument(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title,
                                 @RequestParam(value = "subjectIds", required = false) List<UUID> subjectIds,
                                 @RequestParam(value = "strategy", defaultValue = "RECURSIVE") SegmentationStrategyType strategy,
                                 Locale locale,
                                 RedirectAttributes redirectAttributes) {
        Locale userLocale = (locale != null) ? locale : LocaleContextHolder.getLocale();

        if (file.isEmpty()) {
            String errorMsg = messageSource.getMessage("admin.document.error.file_empty", null, userLocale);
            redirectAttributes.addFlashAttribute("errorMessage", errorMsg);
            return "redirect:/admin/documents";
        }

        String fileName = DocumentStorageService.sanitizeFileName(file.getOriginalFilename());
        String docTitle = DocumentStorageService.sanitizeTitle(title, fileName);
        try (InputStream input = file.getInputStream()) {
            DocumentIngestionService.UploadOutcome outcome =
                    ingestionService.submitDocument(docTitle, fileName, input, subjectIds, strategy);
            String key = switch (outcome.state()) {
                // Without an embedding provider the document is still indexed for full-text search
                case QUEUED -> embeddingService.isConfigured()
                        ? "admin.document.success.queued"
                        : "admin.document.warning.embeddings_pending";
                case ALREADY_INDEXED -> "admin.document.success.already_indexed";
                case ALREADY_PROCESSING -> "admin.document.success.already_processing";
            };
            redirectAttributes.addFlashAttribute("successMessage",
                    messageSource.getMessage(key, new Object[]{outcome.document().getTitle()}, userLocale));
        } catch (DocumentRejectedException e) {
            log.warn("Document upload rejected: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.error.not_pdf", null, userLocale));
        } catch (IOException | RuntimeException e) {
            // The exception detail (SQL, paths, infrastructure) stays in the server log, linked by the reference
            String reference = ErrorReference.newReference();
            log.error("Document upload failed [ref={}]", reference, e);
            String errorMsg = messageSource.getMessage("admin.document.error.ingestion_failed", new Object[]{reference}, userLocale);
            redirectAttributes.addFlashAttribute("errorMessage", errorMsg);
        }

        return "redirect:/admin/documents";
    }

    /**
     * Processes a {@code FAILED} document again from its stored original PDF (no new upload needed).
     */
    @PostMapping("/{id}/reprocess")
    public String reprocessDocument(@PathVariable UUID id,
                                    Locale locale,
                                    RedirectAttributes redirectAttributes) {
        Locale userLocale = (locale != null) ? locale : LocaleContextHolder.getLocale();
        DocumentIngestionService.ReprocessOutcome outcome;
        try {
            outcome = ingestionService.reprocessDocument(id);
        } catch (RuntimeException e) {
            String reference = ErrorReference.newReference();
            log.error("Document {} could not be queued for reprocessing [ref={}]", id, reference, e);
            redirectAttributes.addFlashAttribute("errorMessage", messageSource.getMessage(
                    "admin.document.error.ingestion_failed", new Object[]{reference}, userLocale));
            return "redirect:/admin/documents";
        }
        switch (outcome) {
            case QUEUED -> redirectAttributes.addFlashAttribute("successMessage",
                    messageSource.getMessage("admin.document.reprocess.queued", null, userLocale));
            case NOT_FOUND -> redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reprocess.not_found", null, userLocale));
            case NOT_FAILED -> redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reprocess.not_failed", null, userLocale));
            case FILE_MISSING -> redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reprocess.file_missing", null, userLocale));
        }
        return "redirect:/admin/documents";
    }

    /**
     * Starts the background recomputation of chunk embeddings from the stored chunk text: chunks without a
     * vector (or with a legacy zero vector), or every chunk when {@code forceAll} is set.
     */
    @PostMapping("/reindex-embeddings")
    public String reindexEmbeddings(@RequestParam(value = "forceAll", defaultValue = "false") boolean forceAll,
                                    Locale locale,
                                    RedirectAttributes redirectAttributes) {
        Locale userLocale = (locale != null) ? locale : LocaleContextHolder.getLocale();
        EmbeddingReindexService.StartOutcome outcome;
        try {
            outcome = reindexService.start(forceAll);
        } catch (RuntimeException e) {
            String reference = ErrorReference.newReference();
            log.error("Embedding reindex could not start [ref={}]", reference, e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reindex.error", new Object[]{reference}, userLocale));
            return "redirect:/admin/documents";
        }

        switch (outcome) {
            case STARTED -> redirectAttributes.addFlashAttribute("successMessage", messageSource.getMessage(
                    "admin.document.reindex.started",
                    new Object[]{String.valueOf(reindexService.status().total())}, userLocale));
            case ALREADY_RUNNING -> redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reindex.already_running", null, userLocale));
            case NOT_CONFIGURED -> redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reindex.not_configured", null, userLocale));
            case UNSUPPORTED_DATABASE -> redirectAttributes.addFlashAttribute("errorMessage",
                    messageSource.getMessage("admin.document.reindex.unsupported", null, userLocale));
        }
        return "redirect:/admin/documents";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exc,
                                              Locale locale,
                                              RedirectAttributes redirectAttributes) {
        Locale userLocale = (locale != null) ? locale : LocaleContextHolder.getLocale();
        String errorMsg = messageSource.getMessage("admin.document.error.file_size_exceeded", null, userLocale);
        redirectAttributes.addFlashAttribute("errorMessage", errorMsg);
        return "redirect:/admin/documents";
    }
}
