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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * High-fidelity text extractor using Apache PDFBox 3.0.4 with page mapping.
 *
 * @author Rivelino Patrício
 */
@Service
public class PdfTextExtractor {

    public record ExtractedPdf(int totalPages, Map<Integer, String> pages, String fullText) {}

    public ExtractedPdf extract(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return extractFromDocument(document);
        }
    }

    public ExtractedPdf extract(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return extract(bytes);
    }

    private ExtractedPdf extractFromDocument(PDDocument document) throws IOException {
        int totalPages = document.getNumberOfPages();
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
