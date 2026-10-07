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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test validating subject management and document catalog views.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubjectAndDocumentCatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin creates official IRPF 2026 subject")
    void testCreateSubject() throws Exception {
        mockMvc.perform(post("/admin/subjects")
                .param("code", "tributario-irpf")
                .param("name", "Tributário - IRPF 2026")
                .param("description", "Manual de Perguntas e Respostas IRPF 2026 da RFB")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/subjects"));

        Optional<ExegeseSubject> found = subjectRepository.findByCode("tributario-irpf");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Tributário - IRPF 2026");
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    @DisplayName("Operator views document catalog and filters by subject")
    void testListDocumentsWithFilter() throws Exception {
        ExegeseSubject subject = subjectRepository.save(
            new ExegeseSubject("legislacao-irpf", "Legislação IRPF", "Leis e Instruções Normativas")
        );

        ExegeseDocument doc = new ExegeseDocument(
            "Perguntas & Respostas IRPF 2026",
            "P&R IRPF 2026.pdf",
            "/storage/docs/irpf2026.pdf",
            "hash-catalogo-001",
            4751936L,
            "PDF"
        );
        doc.setStatus("INDEXED");
        doc.setTotalPages(340);
        doc.addSubject(subject);
        documentRepository.save(doc);

        mockMvc.perform(get("/admin/documents")
                .param("subjectId", subject.getId().toString()))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Standard user without ADMIN or OPERATOR role is forbidden (403)")
    void testUserForbiddenOnCatalog() throws Exception {
        mockMvc.perform(get("/admin/subjects"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/documents"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    @DisplayName("Operator uploads and indexes PDF document via multipart POST")
    void testUploadDocument() throws Exception {
        ExegeseSubject subject = subjectRepository.save(
            new ExegeseSubject("upload-test", "Assunto Teste Upload", "Descrição do assunto")
        );

        byte[] pdfBytes = createSamplePdf("001 — O que é o teste de upload no RAG?\nConteúdo explicativo da resposta.");
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "manual_upload_teste.pdf",
            "application/pdf",
            pdfBytes
        );

        mockMvc.perform(multipart("/admin/documents/upload")
                .file(file)
                .param("title", "Manual de Teste Upload")
                .param("subjectIds", subject.getId().toString())
                .param("strategy", "STRUCTURED_QA")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/documents"));

        Optional<ExegeseDocument> found = documentRepository.findAll().stream()
            .filter(d -> "Manual de Teste Upload".equals(d.getTitle()))
            .findFirst();

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("INDEXED");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    @DisplayName("Upload messages respect the requested locale via EXEGESE_LOCALE cookie (pt-BR, en, es)")
    void testUploadMessagesRespectSelectedLocale() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "file",
            "empty.pdf",
            "application/pdf",
            new byte[0]
        );

        // English (cookie set before login)
        mockMvc.perform(multipart("/admin/documents/upload")
                .file(emptyFile)
                .cookie(new Cookie("EXEGESE_LOCALE", "en"))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("errorMessage", "Please select a valid PDF file for upload."));

        // Spanish (cookie set before login)
        mockMvc.perform(multipart("/admin/documents/upload")
                .file(emptyFile)
                .cookie(new Cookie("EXEGESE_LOCALE", "es"))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("errorMessage", "Seleccione un archivo PDF válido para cargar."));

        // Portuguese (pt_BR)
        mockMvc.perform(multipart("/admin/documents/upload")
                .file(emptyFile)
                .cookie(new Cookie("EXEGESE_LOCALE", "pt_BR"))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("errorMessage", "Selecione um arquivo PDF válido para upload."));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    @DisplayName("Language cannot be changed after login via ?lang= parameter")
    void testPostLoginLanguageChangeBlocked() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "file",
            "empty.pdf",
            "application/pdf",
            new byte[0]
        );

        // Authenticated user with English cookie tries to alter language to Spanish via ?lang=es
        // The ?lang= parameter must be ignored post-login, retaining English
        mockMvc.perform(multipart("/admin/documents/upload")
                .file(emptyFile)
                .param("lang", "es")
                .cookie(new Cookie("EXEGESE_LOCALE", "en"))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("errorMessage", "Please select a valid PDF file for upload."));
    }

    private byte[] createSamplePdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);

                String[] lines = text.split("\n");
                for (String line : lines) {
                    contentStream.showText(line.trim());
                    contentStream.newLineAtOffset(0, -15);
                }
                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }
}
