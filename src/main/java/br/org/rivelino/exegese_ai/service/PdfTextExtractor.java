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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * High-fidelity text extractor using Apache PDFBox 3 with page mapping.
 * <p>
 * Documents are parsed with a temporary-file-backed stream cache ({@link IOUtils#createTempFileOnlyStreamCache()}),
 * so decoded streams of large PDFs do not fill the heap, and files are read from disk instead of a byte array.
 * PDFs with more pages than {@code exegese.ingestion.max-pages} are refused before any text is extracted.
 *
 * @author Rivelino Patrício
 */
@Service
public class PdfTextExtractor {

    public record ExtractedPdf(int totalPages, Map<Integer, String> pages, String fullText) {}

    private final int maxPages;

    public PdfTextExtractor(@Value("${exegese.ingestion.max-pages:2000}") int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("exegese.ingestion.max-pages must be positive");
        }
        this.maxPages = maxPages;
    }

    /**
     * @return Maximum number of pages accepted per document
     */
    public int maxPages() {
        return maxPages;
    }

    /**
     * Extracts the text of a PDF file.
     *
     * @param pdfFile PDF on disk
     * @return Text per page and the full text
     * @throws IOException when the file is not a readable PDF
     * @throws DocumentRejectedException when the PDF has more than {@link #maxPages()} pages
     */
    public ExtractedPdf extract(Path pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            return extractFromDocument(document);
        }
    }

    /**
     * Extracts the text of an in-memory PDF (tests and small programmatic inputs).
     *
     * @see #extract(Path)
     */
    public ExtractedPdf extract(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            return extractFromDocument(document);
        }
    }

    private ExtractedPdf extractFromDocument(PDDocument document) throws IOException {
        int totalPages = document.getNumberOfPages();
        if (totalPages > maxPages) {
            throw DocumentRejectedException.tooManyPages(totalPages, maxPages);
        }
        Map<Integer, String> pageMap = new LinkedHashMap<>();
        StringBuilder fullTextBuilder = new StringBuilder();

        PDFTextStripper stripper = new PDFTextStripper();

        for (int p = 1; p <= totalPages; p++) {
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            String rawText = stripper.getText(document);
            String cleaned = cleanPageText(rawText);
            pageMap.put(p, cleaned);
            fullTextBuilder.append(cleaned).append("\n\n");
        }

        return new ExtractedPdf(totalPages, Collections.unmodifiableMap(pageMap), fullTextBuilder.toString());
    }

    private String cleanPageText(String rawText) {
        if (rawText == null) return "";
        // Normalize CRLF to LF and trim extraneous blank lines
        return rawText.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
