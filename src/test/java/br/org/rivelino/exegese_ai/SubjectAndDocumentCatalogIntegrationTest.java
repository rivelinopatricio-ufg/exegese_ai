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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
