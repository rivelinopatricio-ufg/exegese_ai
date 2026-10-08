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
 * Outcome of an LLM provider connectivity test. It carries only a status and, for HTTP answers, the status
 * code: never key material nor upstream response bodies.
 *
 * @param status Outcome category
 * @param httpStatus HTTP status code of the provider answer, or 0 when no answer was received
 * @author Rivelino Patrício
 */
public record LlmPingResult(Status status, int httpStatus) {

    /**
     * Outcome categories of a connectivity test.
     */
    public enum Status {
        /** The provider answered 2xx: endpoint reachable, credentials and model accepted. */
        OK,
        /** No API key configured for a provider that requires one. */
        NOT_CONFIGURED,
        /** The configured base URL violates the endpoint policy; nothing was sent. */
        ENDPOINT_REJECTED,
        /** HTTP 401/403: the key was refused. */
        AUTH_FAILED,
        /** HTTP 404: unknown model or wrong base path. */
        NOT_FOUND,
        /** Any other HTTP status. */
        HTTP_ERROR,
        /** No answer within the ping timeout. */
        TIMEOUT,
        /** Connection failure (DNS, TLS, refused connection...). */
        UNREACHABLE,
        /** Unexpected local failure. */
        ERROR
    }

    public static LlmPingResult of(Status status) {
        return new LlmPingResult(status, 0);
    }

    /**
     * Maps an HTTP status code to a result.
     *
     * @param httpStatus HTTP status code
     * @return The corresponding result
     */
    public static LlmPingResult fromHttpStatus(int httpStatus) {
        Status status;
        if (httpStatus >= 200 && httpStatus < 300) {
            status = Status.OK;
        } else if (httpStatus == 401 || httpStatus == 403) {
            status = Status.AUTH_FAILED;
        } else if (httpStatus == 404) {
            status = Status.NOT_FOUND;
        } else {
            status = Status.HTTP_ERROR;
        }
        return new LlmPingResult(status, httpStatus);
    }
}
