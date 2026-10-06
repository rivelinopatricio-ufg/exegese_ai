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

import br.org.rivelino.exegese_ai.domain.entity.*;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying persistence, relationships and constraints of JPA entities.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntityPersistenceIntegrationTest {

    @Autowired
    private ExegeseUserRepository userRepository;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private UserSubjectPermissionRepository permissionRepository;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private AiModelConfigRepository modelConfigRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Test
    @DisplayName("Persist and query ExegeseUser with roles")
    void testUserPersistence() {
        ExegeseUser user = new ExegeseUser("auditor@receita.gov.br", "Auditor Fiscal", UserRole.ROLE_ADMIN);
        ExegeseUser saved = userRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRole()).isEqualTo(UserRole.ROLE_ADMIN);

        Optional<ExegeseUser> found = userRepository.findByEmail("auditor@receita.gov.br");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Auditor Fiscal");
    }

    @Test
    @DisplayName("Persist ExegeseDocument associated with multiple subjects in N:N relationship")
    void testDocumentSubjectAssociation() {
        ExegeseSubject taxSubject = subjectRepository.save(
            new ExegeseSubject("tributario-irpf", "Tributário - IRPF 2026", "Manual IRPF 2026")
        );
        ExegeseSubject cryptoSubject = subjectRepository.save(
            new ExegeseSubject("criptoativos", "Criptoativos e Renda Variável", "Instrução Normativa RFB")
        );

        ExegeseDocument doc = new ExegeseDocument(
            "Manual IRPF 2026",
            "perguntas_respostas_irpf_2026.pdf",
            "/storage/docs/irpf2026.pdf",
            "sha256-hash-fake-irpf-2026",
            4751936L,
            "PDF"
        );
        doc.addSubject(taxSubject);
        doc.addSubject(cryptoSubject);

        ExegeseDocument savedDoc = documentRepository.save(doc);
        assertThat(savedDoc.getId()).isNotNull();
        assertThat(savedDoc.getSubjects()).hasSize(2);

        List<ExegeseDocument> taxDocs = documentRepository.findBySubjectId(taxSubject.getId());
        assertThat(taxDocs).hasSize(1);
        assertThat(taxDocs.get(0).getTitle()).isEqualTo("Manual IRPF 2026");
    }

    @Test
    @DisplayName("Persist and retrieve ExegeseChunk linked to Document")
    void testChunkPersistence() {
        ExegeseDocument doc = documentRepository.save(
            new ExegeseDocument("Lei 14.754/2023", "lei_14754.pdf", "/storage/lei.pdf", "hash-lei-14754", 1024L, "PDF")
        );

        ExegeseChunk chunk = new ExegeseChunk(
            doc,
            "hash-chunk-p001",
            1,
            "Pergunta 001 - Obrigatoriedade",
            "Quem está obrigado a declarar o IRPF em 2026?",
            "{\"page\": 15, \"questionNumber\": 1}"
        );

        ExegeseChunk savedChunk = chunkRepository.save(chunk);
        assertThat(savedChunk.getId()).isNotNull();

        List<ExegeseChunk> chunks = chunkRepository.findByDocumentIdOrderBySequenceNumberAsc(doc.getId());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getTitle()).isEqualTo("Pergunta 001 - Obrigatoriedade");
    }

    @Test
    @DisplayName("Persist AiModelConfig and ChatSession with Messages")
    void testAiModelAndChatPersistence() {
        AiModelConfig config = new AiModelConfig(ModelProvider.GEMINI, "Google Gemini 2.5 Flash", "gemini-2.5-flash");
        config.setActive(true);
        config.setDefault(true);
        AiModelConfig savedConfig = modelConfigRepository.save(config);
        assertThat(savedConfig.getId()).isNotNull();

        ExegeseUser user = userRepository.save(new ExegeseUser("user@exegese.ai", "Cidadão", UserRole.ROLE_USER));

        ChatSession session = chatSessionRepository.save(new ChatSession(user, "Consulta Criptoativos"));
        ChatMessage msg = new ChatMessage(session, "USER", "Como declarar rendimentos no exterior?");
        msg.setModelUsed("gemini-2.5-flash");
        chatMessageRepository.save(msg);

        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).contains("rendimentos no exterior");
    }
}
