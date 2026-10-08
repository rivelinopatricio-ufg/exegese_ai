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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Outbound endpoint policy for LLM providers (protection against SSRF and API key exfiltration through an
 * edited base URL). A base URL is accepted only when:
 * <ul>
 *   <li>it uses {@code https} ({@code OLLAMA_LOCAL} may also use {@code http});</li>
 *   <li>its host is in the provider allowlist: the official API host of each cloud provider, extended by
 *       {@code exegese.llm.allowed-hosts}; for {@code OLLAMA_LOCAL}, the host of {@code exegese.ollama.base-url},
 *       {@code ollama}, {@code localhost} and {@code 127.0.0.1};</li>
 *   <li>it has no user info, query or fragment.</li>
 * </ul>
 * {@code exegese.llm.allowed-hosts} is a comma-separated list: {@code host} allows it for every cloud provider,
 * {@code PROVIDER=host} (e.g. {@code OPENAI=gateway.example.com}) for one provider only. A blank base URL means
 * "use the provider default", which is always allowed. The policy runs when an admin saves a configuration and
 * again before every outbound call.
 *
 * @author Rivelino Patrício
 */
@Component
public class LlmEndpointPolicy {

    private static final int MAX_BASE_URL_LENGTH = 255;

    private static final Map<ModelProvider, String> CLOUD_DEFAULT_BASE_URLS;

    static {
        Map<ModelProvider, String> defaults = new EnumMap<>(ModelProvider.class);
        defaults.put(ModelProvider.GEMINI, "https://generativelanguage.googleapis.com");
        defaults.put(ModelProvider.CLAUDE, "https://api.anthropic.com");
        defaults.put(ModelProvider.OPENAI, "https://api.openai.com/v1");
        defaults.put(ModelProvider.NEMOTRON, "https://integrate.api.nvidia.com/v1");
        defaults.put(ModelProvider.DEEPSEEK, "https://api.deepseek.com/v1");
        defaults.put(ModelProvider.CEREBRAS, "https://api.cerebras.ai/v1");
        CLOUD_DEFAULT_BASE_URLS = Collections.unmodifiableMap(defaults);
    }

    private final String ollamaBaseUrl;
    private final Map<ModelProvider, Set<String>> allowedHosts;

    public LlmEndpointPolicy(@Value("${exegese.llm.allowed-hosts:}") String extraAllowedHosts,
                             @Value("${exegese.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.ollamaBaseUrl = stripTrailingSlashes(ollamaBaseUrl == null || ollamaBaseUrl.isBlank()
                ? "http://localhost:11434" : ollamaBaseUrl.trim());

        Map<ModelProvider, Set<String>> hosts = new EnumMap<>(ModelProvider.class);
        for (Map.Entry<ModelProvider, String> entry : CLOUD_DEFAULT_BASE_URLS.entrySet()) {
            hosts.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(hostOf(entry.getValue()));
        }
        Set<String> ollamaHosts = hosts.computeIfAbsent(ModelProvider.OLLAMA_LOCAL, k -> new LinkedHashSet<>());
        String configuredOllamaHost = hostOf(this.ollamaBaseUrl);
        if (configuredOllamaHost != null) {
            ollamaHosts.add(configuredOllamaHost);
        }
        ollamaHosts.addAll(Set.of("ollama", "localhost", "127.0.0.1"));

        parseExtraHosts(extraAllowedHosts, hosts);

        Map<ModelProvider, Set<String>> frozen = new EnumMap<>(ModelProvider.class);
        hosts.forEach((provider, set) -> frozen.put(provider, Collections.unmodifiableSet(set)));
        this.allowedHosts = Collections.unmodifiableMap(frozen);
    }

    private static void parseExtraHosts(String extraAllowedHosts, Map<ModelProvider, Set<String>> hosts) {
        if (extraAllowedHosts == null || extraAllowedHosts.isBlank()) {
            return;
        }
        for (String rawEntry : extraAllowedHosts.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator > 0) {
                ModelProvider provider;
                try {
                    provider = ModelProvider.valueOf(entry.substring(0, separator).trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("exegese.llm.allowed-hosts: unknown provider in entry '" + entry + "'", e);
                }
                hosts.computeIfAbsent(provider, k -> new LinkedHashSet<>()).add(normalizeHost(entry.substring(separator + 1)));
            } else {
                String host = normalizeHost(entry);
                for (ModelProvider provider : CLOUD_DEFAULT_BASE_URLS.keySet()) {
                    hosts.computeIfAbsent(provider, k -> new LinkedHashSet<>()).add(host);
                }
            }
        }
    }

    /**
     * Default base URL of a provider (for {@code OLLAMA_LOCAL}, {@code exegese.ollama.base-url}).
     *
     * @param provider Provider
     * @return Default base URL without trailing slash
     */
    public String defaultBaseUrl(ModelProvider provider) {
        return provider == ModelProvider.OLLAMA_LOCAL ? ollamaBaseUrl : CLOUD_DEFAULT_BASE_URLS.get(provider);
    }

    /**
     * Hosts accepted for a provider.
     *
     * @param provider Provider
     * @return Allowed host names (lower case)
     */
    public Set<String> allowedHosts(ModelProvider provider) {
        return allowedHosts.getOrDefault(provider, Set.of());
    }

    /**
     * Validates a base URL as entered by an administrator. Blank means "provider default" and is accepted.
     *
     * @param provider Provider being configured
     * @param baseUrl Base URL, may be null or blank
     * @throws LlmEndpointRejectedException when the URL violates the policy
     */
    public void validate(ModelProvider provider, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        String candidate = baseUrl.trim();
        if (candidate.length() > MAX_BASE_URL_LENGTH) {
            throw new LlmEndpointRejectedException(provider, "URL longer than " + MAX_BASE_URL_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new LlmEndpointRejectedException(provider, "malformed URL");
        }

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        boolean schemeAllowed = "https".equals(scheme)
                || (provider == ModelProvider.OLLAMA_LOCAL && "http".equals(scheme));
        if (!schemeAllowed) {
            throw new LlmEndpointRejectedException(provider, "scheme '" + scheme + "' not allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new LlmEndpointRejectedException(provider, "user info not allowed");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new LlmEndpointRejectedException(provider, "query or fragment not allowed");
        }
        String host = uri.getHost() != null ? normalizeHost(uri.getHost()) : null;
        if (host == null || host.isEmpty()) {
            throw new LlmEndpointRejectedException(provider, "missing host");
        }
        if (!allowedHosts(provider).contains(host)) {
            throw new LlmEndpointRejectedException(provider, "host '" + host + "' not in the allowlist");
        }
    }

    /**
     * Resolves the base URL to call: the configured one when valid, the provider default when blank.
     *
     * @param provider Provider
     * @param configuredBaseUrl Base URL stored in the configuration, may be null or blank
     * @return Validated base URL without trailing slash
     * @throws LlmEndpointRejectedException when the configured URL violates the policy
     */
    public String resolveBaseUrl(ModelProvider provider, String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return defaultBaseUrl(provider);
        }
        validate(provider, configuredBaseUrl);
        return stripTrailingSlashes(configuredBaseUrl.trim());
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? normalizeHost(host) : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String normalizeHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    private static String stripTrailingSlashes(String url) {
        return url.replaceAll("/+$", "");
    }
}
