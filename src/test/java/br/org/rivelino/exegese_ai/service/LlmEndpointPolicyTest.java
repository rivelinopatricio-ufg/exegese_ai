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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LlmEndpointPolicy} (M3): https only, per-provider host allowlist extendable by
 * {@code exegese.llm.allowed-hosts}, and http allowed only for the local Ollama host.
 *
 * @author Rivelino Patrício
 */
class LlmEndpointPolicyTest {

    private final LlmEndpointPolicy policy = new LlmEndpointPolicy("", "http://ollama:11434");

    @Test
    @DisplayName("Official https endpoints and blank (provider default) are accepted")
    void testOfficialEndpointsAccepted() {
        assertThatCode(() -> {
            policy.validate(ModelProvider.GEMINI, "https://generativelanguage.googleapis.com");
            policy.validate(ModelProvider.CLAUDE, "https://api.anthropic.com/");
            policy.validate(ModelProvider.OPENAI, "https://API.OPENAI.COM/v1");
            policy.validate(ModelProvider.NEMOTRON, "https://integrate.api.nvidia.com/v1");
            policy.validate(ModelProvider.DEEPSEEK, "https://api.deepseek.com/v1");
            policy.validate(ModelProvider.CEREBRAS, "https://api.cerebras.ai:443/v1");
            policy.validate(ModelProvider.OPENAI, "");
            policy.validate(ModelProvider.OPENAI, null);
        }).doesNotThrowAnyException();
        assertThat(policy.resolveBaseUrl(ModelProvider.CLAUDE, " ")).isEqualTo("https://api.anthropic.com");
        assertThat(policy.resolveBaseUrl(ModelProvider.OPENAI, "https://api.openai.com/v1//")).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("Foreign hosts, another provider's host and look-alike hosts are rejected")
    void testForeignHostsRejected() {
        assertThatThrownBy(() -> policy.validate(ModelProvider.OPENAI, "https://attacker.example.com/v1"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.OPENAI, "https://api.anthropic.com"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.CLAUDE, "https://api.anthropic.com.attacker.io"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.GEMINI, "https://169.254.169.254/latest"))
                .isInstanceOf(LlmEndpointRejectedException.class);
    }

    @Test
    @DisplayName("http, user info, query strings and malformed URLs are rejected for cloud providers")
    void testUnsafeUrlsRejected() {
        assertThatThrownBy(() -> policy.validate(ModelProvider.OPENAI, "http://api.openai.com/v1"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.OPENAI, "https://user:pass@api.openai.com/v1"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.GEMINI, "https://generativelanguage.googleapis.com?x=1"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.GEMINI, "ftp://generativelanguage.googleapis.com"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.GEMINI, "https://exa mple.com"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> policy.validate(ModelProvider.CLAUDE, "https://api.anthropic.com/" + "a".repeat(300)))
                .isInstanceOf(LlmEndpointRejectedException.class);
    }

    @Test
    @DisplayName("OLLAMA_LOCAL may use http, but only to the configured Ollama host or the local defaults")
    void testOllamaLocal() {
        assertThatCode(() -> {
            policy.validate(ModelProvider.OLLAMA_LOCAL, "http://ollama:11434");
            policy.validate(ModelProvider.OLLAMA_LOCAL, "http://localhost:11434");
            policy.validate(ModelProvider.OLLAMA_LOCAL, "http://127.0.0.1:11434/v1");
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(ModelProvider.OLLAMA_LOCAL, "http://attacker.example.com:11434"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThat(policy.defaultBaseUrl(ModelProvider.OLLAMA_LOCAL)).isEqualTo("http://ollama:11434");

        LlmEndpointPolicy custom = new LlmEndpointPolicy("", "http://gpu-box.internal:11434/");
        assertThatCode(() -> custom.validate(ModelProvider.OLLAMA_LOCAL, "http://gpu-box.internal:11434"))
                .doesNotThrowAnyException();
        assertThat(custom.defaultBaseUrl(ModelProvider.OLLAMA_LOCAL)).isEqualTo("http://gpu-box.internal:11434");
    }

    @Test
    @DisplayName("exegese.llm.allowed-hosts extends the allowlist globally or per provider")
    void testExtraAllowedHosts() {
        LlmEndpointPolicy extended = new LlmEndpointPolicy(" gateway.example.com , openai=proxy.example.org ", "http://ollama:11434");

        assertThatCode(() -> {
            extended.validate(ModelProvider.CLAUDE, "https://gateway.example.com/anthropic");
            extended.validate(ModelProvider.GEMINI, "https://gateway.example.com");
            extended.validate(ModelProvider.OPENAI, "https://proxy.example.org/v1");
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> extended.validate(ModelProvider.CLAUDE, "https://proxy.example.org"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> extended.validate(ModelProvider.OPENAI, "http://proxy.example.org/v1"))
                .isInstanceOf(LlmEndpointRejectedException.class);
        assertThatThrownBy(() -> new LlmEndpointPolicy("NOPE=host.example.com", "http://ollama:11434"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
