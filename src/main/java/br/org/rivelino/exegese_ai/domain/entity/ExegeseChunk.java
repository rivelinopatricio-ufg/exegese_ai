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

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic text chunk with embedding vector and full-text search indexing.
 *
 * @author Rivelino Patrício
 */
@Entity
@Table(name = "exegese_chunk")
public class ExegeseChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private ExegeseDocument document;

    @Column(name = "chunk_hash_sha256", nullable = false, unique = true, length = 64)
    private String chunkHashSha256;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false)
    private String metadata = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ExegeseChunk() {
    }

    public ExegeseChunk(ExegeseDocument document, String chunkHashSha256, Integer sequenceNumber,
                        String title, String content, String metadata) {
        this.document = document;
        this.chunkHashSha256 = chunkHashSha256;
        this.sequenceNumber = sequenceNumber;
        this.title = title;
        this.content = content;
        this.metadata = (metadata != null && !metadata.isBlank()) ? metadata : "{}";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ExegeseDocument getDocument() {
        return document;
    }

    public void setDocument(ExegeseDocument document) {
        this.document = document;
    }

    public String getChunkHashSha256() {
        return chunkHashSha256;
    }

    public void setChunkHashSha256(String chunkHashSha256) {
        this.chunkHashSha256 = chunkHashSha256;
    }

    public Integer getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Integer sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = (metadata != null && !metadata.isBlank()) ? metadata : "{}";
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
        ExegeseChunk that = (ExegeseChunk) o;
        return Objects.equals(chunkHashSha256, that.chunkHashSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkHashSha256);
    }
}
