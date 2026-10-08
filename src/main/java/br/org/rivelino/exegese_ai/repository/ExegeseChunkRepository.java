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
package br.org.rivelino.exegese_ai.repository;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for ExegeseChunk entities.
 *
 * @author Rivelino Patrício
 */
public interface ExegeseChunkRepository extends JpaRepository<ExegeseChunk, UUID> {

    /**
     * Chunk hashes are unique per document (the same text may be indexed in several documents).
     */
    boolean existsByDocumentIdAndChunkHashSha256(UUID documentId, String chunkHashSha256);

    List<ExegeseChunk> findByDocumentIdOrderBySequenceNumberAsc(UUID documentId);

    long countByDocumentId(UUID documentId);

    /**
     * Removes every chunk of a document (bulk delete, used before a document is ingested again).
     *
     * @return Number of removed chunks
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ExegeseChunk c WHERE c.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
