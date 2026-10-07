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
package br.org.rivelino.exegese_ai.domain.entity;

import br.org.rivelino.exegese_ai.domain.dto.CanonicalCitationDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Message exchange within a chat session containing content and grounding citations.
 *
 * @author Rivelino Patrício
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_subject_ids")
    private String appliedSubjectIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations")
    private String citations;

    @Transient
    private transient List<CanonicalCitationDTO> parsedCitationsCache;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "execution_duration_ms")
    private Integer executionDurationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ChatMessage() {
    }

    public ChatMessage(ChatSession session, String role, String content) {
        this.session = session;
        this.role = role;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ChatSession getSession() {
        return session;
    }

    public void setSession(ChatSession session) {
        this.session = session;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAppliedSubjectIds() {
        return appliedSubjectIds;
    }

    public void setAppliedSubjectIds(String appliedSubjectIds) {
        this.appliedSubjectIds = appliedSubjectIds;
    }

    public String getCitations() {
        return citations;
    }

    public void setCitations(String citations) {
        this.citations = citations;
        this.parsedCitationsCache = null;
    }

    @Transient
    public List<CanonicalCitationDTO> getParsedCitations() {
        if (parsedCitationsCache != null) {
            return parsedCitationsCache;
        }
        if (citations == null || citations.isBlank()) {
            this.parsedCitationsCache = List.of();
            return this.parsedCitationsCache;
        }
        try {
            this.parsedCitationsCache = OBJECT_MAPPER.readValue(citations, new TypeReference<List<CanonicalCitationDTO>>() {});
            return this.parsedCitationsCache;
        } catch (@SuppressWarnings("unused") JsonProcessingException e) {
            this.parsedCitationsCache = List.of();
            return this.parsedCitationsCache;
        }
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }

    public Integer getExecutionDurationMs() {
        return executionDurationMs;
    }

    public void setExecutionDurationMs(Integer executionDurationMs) {
        this.executionDurationMs = executionDurationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
