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

import br.org.rivelino.exegese_ai.domain.dto.DocumentSummaryDTO;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service managing subject categorization, topic taxonomies, and cataloged documents.
 *
 * @author Rivelino Patrício
 */
@Service
public class SubjectCatalogService {

    private final ExegeseSubjectRepository subjectRepository;
    private final ExegeseDocumentRepository documentRepository;

    public SubjectCatalogService(ExegeseSubjectRepository subjectRepository,
                                 ExegeseDocumentRepository documentRepository) {
        this.subjectRepository = subjectRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public ExegeseSubject createSubject(String code, String name, String description) {
        if (subjectRepository.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Subject code already exists: " + code);
        }
        ExegeseSubject subject = new ExegeseSubject(code, name, description);
        return subjectRepository.save(subject);
    }

    @Transactional(readOnly = true)
    public Optional<ExegeseSubject> findById(UUID id) {
        return subjectRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<ExegeseSubject> findByCode(String code) {
        return subjectRepository.findByCode(code);
    }

    @Transactional(readOnly = true)
    public List<ExegeseSubject> findAll() {
        return subjectRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ExegeseSubject> findAllActive() {
        return subjectRepository.findByActiveTrue();
    }

    @Transactional
    public ExegeseSubject toggleActive(UUID id) {
        ExegeseSubject subject = subjectRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Subject not found: " + id));
        subject.setActive(!subject.isActive());
        return subjectRepository.save(subject);
    }

    @Transactional(readOnly = true)
    public List<DocumentSummaryDTO> listDocuments(UUID subjectIdFilter, String statusFilter) {
        List<ExegeseDocument> docs;

        if (subjectIdFilter != null) {
            docs = documentRepository.findBySubjectId(subjectIdFilter);
        } else if (statusFilter != null && !statusFilter.isBlank()) {
            docs = documentRepository.findByStatus(statusFilter);
        } else {
            docs = documentRepository.findAll();
        }

        return docs.stream().map(doc -> new DocumentSummaryDTO(
            doc.getId(),
            doc.getTitle(),
            doc.getOriginalFileName(),
            doc.getFileSize(),
            doc.getFileType(),
            doc.getTotalPages(),
            doc.getSegmentationStrategy(),
            doc.getStatus(),
            doc.getSubjects().stream().map(ExegeseSubject::getName).collect(Collectors.toList()),
            doc.getCreatedAt()
        )).collect(Collectors.toList());
    }
}
