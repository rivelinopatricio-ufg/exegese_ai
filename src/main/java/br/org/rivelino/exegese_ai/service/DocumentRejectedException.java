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

/**
 * Raised when an uploaded file is refused before or during ingestion for a reason the operator can act on:
 * it is not a PDF (missing {@code %PDF-} signature), it has more pages than {@code exegese.ingestion.max-pages}
 * or its stored original is not available. The message never contains user content or infrastructure detail.
 *
 * @author Rivelino Patrício
 */
public class DocumentRejectedException extends RuntimeException {

    /**
     * Why the document was refused.
     */
    public enum Reason {
        NOT_PDF,
        TOO_MANY_PAGES,
        FILE_UNAVAILABLE
    }

    private final Reason reason;

    public DocumentRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static DocumentRejectedException notPdf() {
        return new DocumentRejectedException(Reason.NOT_PDF, "The uploaded file is not a PDF (missing %PDF- signature)");
    }

    public static DocumentRejectedException tooManyPages(int pages, int maxPages) {
        return new DocumentRejectedException(Reason.TOO_MANY_PAGES,
                "The PDF has " + pages + " pages; the maximum allowed is " + maxPages);
    }

    public static DocumentRejectedException fileUnavailable() {
        return new DocumentRejectedException(Reason.FILE_UNAVAILABLE,
                "The original PDF of this document is not available in the upload directory; upload it again");
    }

    public Reason getReason() {
        return reason;
    }
}
