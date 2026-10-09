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
package br.org.rivelino.exegese_ai.domain;

import br.org.rivelino.exegese_ai.domain.dto.SubjectDTO;
import br.org.rivelino.exegese_ai.domain.dto.UserDTO;
import br.org.rivelino.exegese_ai.domain.dto.UserPermissionUpdateDTO;
import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermission;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermissionId;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.domain.enums.SubjectPermissionLevel;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for domain models, entities, DTOs, and composite keys.
 *
 * @author Rivelino Patrício
 */
class DomainAndDtoTest {

    @Test
    @DisplayName("DTOs record and class getters and constructors behave correctly")
    void testDtos() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        UserDTO userDTO = new UserDTO(userId, "user@test.com", "User Name", "pic.png", UserRole.ROLE_ADMIN, true, now, now);
        assertThat(userDTO.id()).isEqualTo(userId);
        assertThat(userDTO.email()).isEqualTo("user@test.com");
        assertThat(userDTO.name()).isEqualTo("User Name");
        assertThat(userDTO.avatarUrl()).isEqualTo("pic.png");
        assertThat(userDTO.role()).isEqualTo(UserRole.ROLE_ADMIN);
        assertThat(userDTO.active()).isTrue();
        assertThat(userDTO.createdAt()).isEqualTo(now);
        assertThat(userDTO.lastLoginAt()).isEqualTo(now);

        UUID subjectId = UUID.randomUUID();
        SubjectDTO subjectDTO = new SubjectDTO(subjectId, "IRPF", "Imposto de Renda", "Desc", true, now);
        assertThat(subjectDTO.id()).isEqualTo(subjectId);
        assertThat(subjectDTO.code()).isEqualTo("IRPF");
        assertThat(subjectDTO.name()).isEqualTo("Imposto de Renda");
        assertThat(subjectDTO.description()).isEqualTo("Desc");
        assertThat(subjectDTO.active()).isTrue();
        assertThat(subjectDTO.createdAt()).isEqualTo(now);

        UserPermissionUpdateDTO updateDTO = new UserPermissionUpdateDTO(List.of(subjectId), SubjectPermissionLevel.READ.name());
        assertThat(updateDTO.subjectIds()).containsExactly(subjectId);
        assertThat(updateDTO.permissionLevel()).isEqualTo("READ");
    }

    @Test
    @DisplayName("UserSubjectPermissionId equals, hashCode, and accessors")
    void testUserSubjectPermissionId() {
        UUID userId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();

        UserSubjectPermissionId id1 = new UserSubjectPermissionId(userId, subjectId);
        UserSubjectPermissionId id2 = new UserSubjectPermissionId(userId, subjectId);
        UserSubjectPermissionId id3 = new UserSubjectPermissionId(UUID.randomUUID(), subjectId);

        assertThat(id1.getUserId()).isEqualTo(userId);
        assertThat(id1.getSubjectId()).isEqualTo(subjectId);
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());

        UserSubjectPermissionId noArg = new UserSubjectPermissionId();
        noArg.setUserId(userId);
        noArg.setSubjectId(subjectId);
        assertThat(noArg).isEqualTo(id1);
    }

    @Test
    @DisplayName("Entities equals, hashCode, getters, and setters")
    void testEntities() {
        UUID docId = UUID.randomUUID();
        ExegeseDocument doc = new ExegeseDocument("Titulo", "arquivo.pdf", "path/doc.pdf", "sha256", 1024L, "application/pdf");
        doc.setId(docId);
        assertThat(doc.getTitle()).isEqualTo("Titulo");
        assertThat(doc.getFileSize()).isEqualTo(1024L);

        ExegeseChunk chunk = new ExegeseChunk(doc, "chunk-hash", 1, "Secao 1", "Conteudo", "{}");
        chunk.setId(UUID.randomUUID());
        assertThat(chunk.getDocument()).isEqualTo(doc);
        assertThat(chunk.getTitle()).isEqualTo("Secao 1");
        assertThat(chunk.getChunkHashSha256()).isEqualTo("chunk-hash");

        ExegeseUser user = new ExegeseUser("user@test.com", "Nome", UserRole.ROLE_USER);
        user.setId(UUID.randomUUID());
        assertThat(user.getEmail()).isEqualTo("user@test.com");
        assertThat(user.getRole()).isEqualTo(UserRole.ROLE_USER);

        ExegeseSubject subject = new ExegeseSubject("DIR", "Direito", "Descricao");
        subject.setId(UUID.randomUUID());
        doc.addSubject(subject);
        assertThat(doc.getSubjects()).contains(subject);
        doc.getSubjects().remove(subject);
        assertThat(doc.getSubjects()).doesNotContain(subject);

        ChatSession session = new ChatSession(user, "Sessao");
        session.setId(UUID.randomUUID());
        ChatMessage message = new ChatMessage(session, "user", "Pergunta?");
        assertThat(message.getSession()).isEqualTo(session);
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(message.getContent()).isEqualTo("Pergunta?");

        UserSubjectPermission perm = new UserSubjectPermission(user, subject, "READ");
        assertThat(perm.getUser()).isEqualTo(user);
        assertThat(perm.getSubject()).isEqualTo(subject);
        assertThat(perm.getPermissionLevel()).isEqualTo("READ");

        AiModelConfig modelConfig = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "gemini-flash");
        assertThat(modelConfig.getProvider()).isEqualTo(ModelProvider.GEMINI);
    }
}
