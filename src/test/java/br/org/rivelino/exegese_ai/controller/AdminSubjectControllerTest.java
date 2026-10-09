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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.service.SubjectCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSubjectController} managing subject taxonomy administrative operations.
 *
 * @author Rivelino Patrício
 */
class AdminSubjectControllerTest {

    @Mock
    private SubjectCatalogService catalogService;

    private AdminSubjectController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AdminSubjectController(catalogService);
    }

    @Test
    @DisplayName("listSubjects adds subjects list to model and returns view name")
    void testListSubjects() {
        ExegeseSubject subject = new ExegeseSubject("TAX", "Tributário", "Direito Tributário");
        when(catalogService.findAll()).thenReturn(List.of(subject));

        Model model = new ConcurrentModel();
        String view = controller.listSubjects(model);

        assertThat(view).isEqualTo("admin/subjects");
        assertThat(model.getAttribute("subjects")).isEqualTo(List.of(subject));
    }

    @Test
    @DisplayName("createSubject delegates to catalog service and redirects")
    void testCreateSubject() {
        String view = controller.createSubject("IRPF", "Imposto de Renda", "Declaração e regras");

        assertThat(view).isEqualTo("redirect:/admin/subjects");
        verify(catalogService).createSubject("IRPF", "Imposto de Renda", "Declaração e regras");
    }

    @Test
    @DisplayName("toggleActive delegates to catalog service and redirects")
    void testToggleActive() {
        UUID id = UUID.randomUUID();
        String view = controller.toggleActive(id);

        assertThat(view).isEqualTo("redirect:/admin/subjects");
        verify(catalogService).toggleActive(id);
    }
}
