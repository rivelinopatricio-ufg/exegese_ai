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
package br.org.rivelino.exegese_ai.domain.dto;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Single-use authorization to open the SSE answer stream of a question accepted by
 * {@code POST /api/chat/messages}. It is bound to the user that submitted the question and carries the
 * request locale captured in the web thread, so the asynchronous answer never depends on global state.
 *
 * @author Rivelino Patrício
 */
public record ChatStreamTicket(
        UUID userId,
        UUID sessionId,
        String question,
        List<UUID> subjectIds,
        Locale locale
) {

    public ChatStreamTicket {
        subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
    }

    /**
     * Omits the question text so that an accidental log statement never records it (LGPD).
     */
    @Override
    public String toString() {
        return "ChatStreamTicket[userId=" + userId + ", sessionId=" + sessionId
                + ", subjects=" + subjectIds.size() + ", locale=" + locale + "]";
    }
}
