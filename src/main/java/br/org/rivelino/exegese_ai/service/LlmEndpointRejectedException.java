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

import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;

/**
 * Raised when the base URL configured for an LLM provider violates the endpoint policy (scheme other than
 * https, host outside the provider allowlist, embedded credentials...). The rejected URL itself is not part
 * of the message, which is an i18n key.
 *
 * @author Rivelino Patrício
 */
public class LlmEndpointRejectedException extends RuntimeException {

    public static final String MESSAGE_KEY = "admin.model.error.base_url_rejected";

    private final ModelProvider provider;
    private final String reason;

    public LlmEndpointRejectedException(ModelProvider provider, String reason) {
        super("Base URL rejected for provider " + provider + ": " + reason);
        this.provider = provider;
        this.reason = reason;
    }

    public ModelProvider getProvider() {
        return provider;
    }

    /**
     * @return Short technical reason (for logs), never containing the rejected URL
     */
    public String getReason() {
        return reason;
    }

    public String getMessageKey() {
        return MESSAGE_KEY;
    }
}
