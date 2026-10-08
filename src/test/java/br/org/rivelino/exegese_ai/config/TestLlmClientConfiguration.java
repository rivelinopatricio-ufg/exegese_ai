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
package br.org.rivelino.exegese_ai.config;

import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockReset;

import br.org.rivelino.exegese_ai.service.LlmClientService;

/**
 * Test configuration keeping the test suite hermetic: replaces the outbound {@link LlmClientService}
 * with a Mockito mock so no test ever performs a real HTTP call to an LLM provider.
 * <p>
 * By default {@code streamInference(..)} returns {@code false} (no tokens), which drives the RAG
 * pipeline through its grounded fallback answer. Tests that need a streamed LLM answer can autowire
 * {@link LlmClientService} and stub it; the mock is reset automatically after each test method.
 *
 * @author Rivelino Patrício
 */
@Configuration
public class TestLlmClientConfiguration {

    @Bean
    @Primary
    public LlmClientService stubLlmClientService() {
        return Mockito.mock(LlmClientService.class, MockReset.withSettings(MockReset.AFTER));
    }
}
