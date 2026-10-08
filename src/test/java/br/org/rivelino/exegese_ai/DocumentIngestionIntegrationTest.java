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

import br.org.rivelino.exegese_ai.domain.dto.RawChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.CryptoService;
import br.org.rivelino.exegese_ai.service.DocumentIngestionService;
import br.org.rivelino.exegese_ai.service.PdfTextExtractor;
import br.org.rivelino.exegese_ai.service.segmentation.LegalSectionSegmentationStrategy;
import br.org.rivelino.exegese_ai.service.segmentation.StructuredQuestionSegmentationStrategy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests validating PDF extraction, polymorphic chunking strategies, and idempotent document ingestion.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentIngestionIntegrationTest {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private PdfTextExtractor pdfTextExtractor;

    @Autowired
    private CryptoService cryptoService;

    @Test
    @DisplayName("Structured question segmentation strategy partitions canonical RFB Q&A format")
    void testStructuredQuestionSegmentation() {
        StructuredQuestionSegmentationStrategy strategy = new StructuredQuestionSegmentationStrategy(cryptoService);

        String sampleText = """
                001 — O que é o IRPF?
                O imposto sobre a renda das pessoas físicas incide sobre o rendimento auferido.
                Dispositivos Legais: Lei nº 7.713, de 1988, art. 2º.
                
                002 — Quem está obrigado a apresentar a Declaração de Ajuste Anual?
                Está obrigada a pessoa física residente no Brasil que recebeu rendimentos tributáveis acima do limite.
                Dispositivos Legais: IN RFB nº 2.178, de 2024.
                """;

        Map<Integer, String> pageMap = Map.of(1, sampleText);

        List<RawChunk> chunks = strategy.segment(sampleText, pageMap);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sequenceNumber()).isEqualTo(1);
        assertThat(chunks.get(0).title()).contains("Pergunta 001");
        assertThat(chunks.get(0).content()).contains("O imposto sobre a renda");
        assertThat(chunks.get(0).metadata()).containsEntry("questionNumber", 1);

        assertThat(chunks.get(1).sequenceNumber()).isEqualTo(2);
        assertThat(chunks.get(1).title()).contains("Pergunta 002");
        assertThat(chunks.get(1).content()).contains("Está obrigada a pessoa física");
        assertThat(chunks.get(1).metadata()).containsEntry("questionNumber", 2);
    }

    @Test
    @DisplayName("Legal section segmentation strategy partitions legal statutes by Article")
    void testLegalSectionSegmentation() {
        LegalSectionSegmentationStrategy strategy = new LegalSectionSegmentationStrategy(cryptoService);

        String sampleLaw = """
                Art. 1º Fica instituído o regime tributário simplificado.
                § 1º O regime aplica-se a microempresas.
                § 2º As vedações constam no regulamento.
                
                Art. 2º A adesão dar-se-á no mês de janeiro.
                Parágrafo único. A opção é irretratável para todo o ano-calendário.
                """;

        Map<Integer, String> pageMap = Map.of(1, sampleLaw);

        List<RawChunk> chunks = strategy.segment(sampleLaw, pageMap);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).title()).isEqualTo("Artigo 1");
        assertThat(chunks.get(0).content()).contains("Fica instituído o regime");
        assertThat(chunks.get(1).title()).isEqualTo("Artigo 2");
        assertThat(chunks.get(1).content()).contains("A adesão dar-se-á");
    }

    @Test
    @DisplayName("PdfTextExtractor extracts textual content and maintains page mapping")
    void testPdfTextExtractor() throws IOException {
        byte[] pdfBytes = createSamplePdf("001 — Teste de Extração PDFBox\nConteúdo explicativo da página 1.");

        PdfTextExtractor.ExtractedPdf extracted = pdfTextExtractor.extract(pdfBytes);

        assertThat(extracted.totalPages()).isEqualTo(1);
        assertThat(extracted.pages()).containsKey(1);
        assertThat(extracted.fullText()).contains("001 — Teste de Extração PDFBox");
    }

    @Test
    @DisplayName("DocumentIngestionService ingests PDF, indexes chunks and enforces idempotency")
    void testDocumentIngestionAndIdempotency() throws IOException {
        ExegeseSubject subject = subjectRepository.save(
                new ExegeseSubject("irpf-test", "IRPF Test", "Assunto para teste de ingestão")
        );

        String textContent = """
                001 — O que é rendimento isento?
                Rendimentos isentos são aqueles não sujeitos ao imposto de renda.
                
                002 — Como declarar dependentes?
                Podem ser considerados dependentes cônjuge, companheiro e filhos até 21 anos.
                """;

        byte[] pdfBytes = createSamplePdf(textContent);

        // First ingestion
        ExegeseDocument doc = ingestionService.ingestDocument(
                "Manual IRPF Teste",
                "manual_irpf_teste.pdf",
                pdfBytes,
                List.of(subject.getId()),
                SegmentationStrategyType.STRUCTURED_QA
        );

        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo("INDEXED");
        assertThat(doc.getTotalPages()).isEqualTo(1);
        assertThat(doc.getSubjects()).hasSize(1);

        List<ExegeseChunk> chunks = chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId());
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getTitle()).contains("Pergunta 001");
        assertThat(chunks.get(1).getTitle()).contains("Pergunta 002");

        // Second ingestion (Idempotency test with exact same bytes)
        ExegeseDocument duplicateDoc = ingestionService.ingestDocument(
                "Manual IRPF Teste",
                "manual_irpf_teste.pdf",
                pdfBytes,
                List.of(subject.getId()),
                SegmentationStrategyType.STRUCTURED_QA
        );

        assertThat(duplicateDoc.getId()).isEqualTo(doc.getId());

        // Verify chunks were not duplicated
        List<ExegeseChunk> chunksAfterDuplicate = chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId());
        assertThat(chunksAfterDuplicate).hasSize(2);
    }

    @Test
    @DisplayName("The same chunk text in two documents is indexed in both (chunk hash unique per document)")
    void testSameChunkIndexedInTwoDocuments() throws IOException {
        String sharedQuestion = """
                001 — O que é rendimento tributável?
                Rendimento tributável é aquele sujeito à incidência do imposto.
                """;
        byte[] firstPdf = createSamplePdf(sharedQuestion + """
                002 — Quem deve declarar?
                Quem recebeu rendimentos acima do limite anual.
                """);
        byte[] secondPdf = createSamplePdf(sharedQuestion + """
                003 — Qual o prazo de entrega?
                A declaração deve ser entregue até o último dia útil de maio.
                """);

        ExegeseDocument first = ingestionService.ingestDocument("Manual A", "manual_a.pdf", firstPdf,
                List.of(), SegmentationStrategyType.STRUCTURED_QA);
        ExegeseDocument second = ingestionService.ingestDocument("Manual B", "manual_b.pdf", secondPdf,
                List.of(), SegmentationStrategyType.STRUCTURED_QA);

        assertThat(first.getStatus()).isEqualTo("INDEXED");
        assertThat(second.getStatus()).isEqualTo("INDEXED");
        List<ExegeseChunk> firstChunks = chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(first.getId());
        List<ExegeseChunk> secondChunks = chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(second.getId());
        assertThat(firstChunks).hasSize(2);
        assertThat(secondChunks).hasSize(2);

        String sharedHash = firstChunks.get(0).getChunkHashSha256();
        assertThat(secondChunks.get(0).getChunkHashSha256()).isEqualTo(sharedHash);
        assertThat(chunkRepository.existsByDocumentIdAndChunkHashSha256(first.getId(), sharedHash)).isTrue();
        assertThat(chunkRepository.existsByDocumentIdAndChunkHashSha256(second.getId(), sharedHash)).isTrue();
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
