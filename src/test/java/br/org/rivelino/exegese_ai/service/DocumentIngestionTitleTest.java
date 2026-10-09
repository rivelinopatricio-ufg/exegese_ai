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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the chunk title truncation of {@link DocumentIngestionService}: a heading longer than the
 * {@code VARCHAR(500)} column must not fail the whole document.
 *
 * @author Rivelino Patrício
 */
class DocumentIngestionTitleTest {

    @Test
    @DisplayName("Long chunk titles are truncated to the column size; short and null titles are kept")
    void testTruncate() {
        String longTitle = "Pergunta 1 — " + "a".repeat(700);
        String truncated = DocumentIngestionService.truncate(longTitle, 500);
        assertThat(truncated).hasSizeLessThanOrEqualTo(500).startsWith("Pergunta 1 — ").endsWith("…");
        assertThat(DocumentIngestionService.truncate("Pergunta 2", 500)).isEqualTo("Pergunta 2");
        assertThat(DocumentIngestionService.truncate(null, 500)).isNull();
    }
}
