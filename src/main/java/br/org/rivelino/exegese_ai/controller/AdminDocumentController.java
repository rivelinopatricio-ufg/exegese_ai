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
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.SubjectCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Administrative controller for viewing cataloged documents, statuses and uploading new normative files.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/documents")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminDocumentController {

    private final SubjectCatalogService catalogService;
    private final DocumentIngestionService ingestionService;

    public AdminDocumentController(SubjectCatalogService catalogService,
                                   DocumentIngestionService ingestionService) {
        this.catalogService = catalogService;
        this.ingestionService = ingestionService;
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

        return "admin/documents";
    }

    @PostMapping("/upload")
    public String uploadDocument(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title,
                                 @RequestParam(value = "subjectIds", required = false) List<UUID> subjectIds,
                                 @RequestParam(value = "strategy", defaultValue = "RECURSIVE") SegmentationStrategyType strategy,
                                 RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Selecione um arquivo PDF válido para upload.");
            return "redirect:/admin/documents";
        }

        try {
            String docTitle = (title != null && !title.isBlank()) ? title.trim() : file.getOriginalFilename();
            ingestionService.ingestDocument(docTitle, file.getOriginalFilename(), file.getInputStream(), subjectIds, strategy);
            redirectAttributes.addFlashAttribute("successMessage", "Documento '" + docTitle + "' enviado e indexado com sucesso!");
        } catch (IOException | RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Falha na ingestão do documento: " + e.getMessage());
        }

        return "redirect:/admin/documents";
    }
}
