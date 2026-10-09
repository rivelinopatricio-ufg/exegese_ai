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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import br.org.rivelino.exegese_ai.domain.dto.DocumentSummaryDTO;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;

/**
 * Unit tests for {@link SubjectCatalogService} validating subject management and document filtering logic.
 *
 * @author Rivelino Patrício
 */
class SubjectCatalogServiceTest {

    @Mock
    private ExegeseSubjectRepository subjectRepository;

    @Mock
    private ExegeseDocumentRepository documentRepository;

    private SubjectCatalogService subjectCatalogService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        subjectCatalogService = new SubjectCatalogService(subjectRepository, documentRepository);
    }

    @Test
    @DisplayName("createSubject throws IllegalArgumentException when subject code already exists")
    void testCreateSubjectDuplicateCode() {
        String code = "TAX_LAW";
        when(subjectRepository.findByCode(code)).thenReturn(Optional.of(new ExegeseSubject(code, "Tax Law", "Desc")));

        assertThatThrownBy(() -> subjectCatalogService.createSubject(code, "New Tax Law", "Desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Subject code already exists: " + code);
    }

    @Test
    @DisplayName("createSubject saves and returns new subject when code is unique")
    void testCreateSubjectSuccess() {
        String code = "CIVIL_LAW";
        ExegeseSubject subject = new ExegeseSubject(code, "Civil Law", "Description");
        when(subjectRepository.findByCode(code)).thenReturn(Optional.empty());
        when(subjectRepository.save(any(ExegeseSubject.class))).thenReturn(subject);

        ExegeseSubject created = subjectCatalogService.createSubject(code, "Civil Law", "Description");

        assertThat(created).isNotNull();
        assertThat(created.getCode()).isEqualTo(code);
        verify(subjectRepository).save(any(ExegeseSubject.class));
    }

    @Test
    @DisplayName("findById and findByCode delegate to repository")
    void testFindByIdAndCode() {
        UUID id = UUID.randomUUID();
        ExegeseSubject subject = new ExegeseSubject("CONST", "Constitutional Law", "Desc");

        when(subjectRepository.findById(id)).thenReturn(Optional.of(subject));
        when(subjectRepository.findByCode("CONST")).thenReturn(Optional.of(subject));
        when(subjectRepository.findAll()).thenReturn(List.of(subject));
        when(subjectRepository.findByActiveTrue()).thenReturn(List.of(subject));

        assertThat(subjectCatalogService.findById(id)).contains(subject);
        assertThat(subjectCatalogService.findByCode("CONST")).contains(subject);
        assertThat(subjectCatalogService.findAll()).containsExactly(subject);
        assertThat(subjectCatalogService.findAllActive()).containsExactly(subject);
    }

    @Test
    @DisplayName("toggleActive flips active status of an existing subject")
    void testToggleActiveSuccess() {
        UUID id = UUID.randomUUID();
        ExegeseSubject subject = new ExegeseSubject("TAX", "Taxation", "Desc");
        subject.setActive(true);

        when(subjectRepository.findById(id)).thenReturn(Optional.of(subject));
        when(subjectRepository.save(any(ExegeseSubject.class))).thenAnswer(inv -> inv.getArgument(0));

        ExegeseSubject toggled = subjectCatalogService.toggleActive(id);

        assertThat(toggled.isActive()).isFalse();

        ExegeseSubject toggledBack = subjectCatalogService.toggleActive(id);
        assertThat(toggledBack.isActive()).isTrue();
    }

    @Test
    @DisplayName("toggleActive throws IllegalArgumentException when subject is not found")
    void testToggleActiveNotFound() {
        UUID id = UUID.randomUUID();
        when(subjectRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subjectCatalogService.toggleActive(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Subject not found: " + id);
    }

    @Test
    @DisplayName("listDocuments filters by subjectId, status, or lists all when filters are null")
    void testListDocumentsFilters() {
        UUID subjectId = UUID.randomUUID();
        ExegeseSubject subject = new ExegeseSubject("SUB", "Subject", "Desc");
        ExegeseDocument doc = new ExegeseDocument("Doc Title", "file.pdf", "target/file.pdf", "hash123", 1024L, "application/pdf");
        doc.getSubjects().add(subject);

        when(documentRepository.findBySubjectId(subjectId)).thenReturn(List.of(doc));
        when(documentRepository.findByStatus("INDEXED")).thenReturn(List.of(doc));
        when(documentRepository.findAll()).thenReturn(List.of(doc));

        List<DocumentSummaryDTO> bySubject = subjectCatalogService.listDocuments(subjectId, null);
        assertThat(bySubject).hasSize(1);
        assertThat(bySubject.get(0).title()).isEqualTo("Doc Title");

        List<DocumentSummaryDTO> byStatus = subjectCatalogService.listDocuments(null, "INDEXED");
        assertThat(byStatus).hasSize(1);

        List<DocumentSummaryDTO> all = subjectCatalogService.listDocuments(null, null);
        assertThat(all).hasSize(1);
    }
}
