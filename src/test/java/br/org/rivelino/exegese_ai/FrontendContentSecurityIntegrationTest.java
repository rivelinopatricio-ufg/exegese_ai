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
package br.org.rivelino.exegese_ai;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test guarding the CSP-friendly front-end (M1): self-hosted Tailwind build, external
 * scripts only, no inline event handlers or style attributes, and the strict Content-Security-Policy
 * plus the extra browser security headers on rendered pages and static assets.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FrontendContentSecurityIntegrationTest {

    /** {@code <script>} element without a {@code src} (or {@code th:src}) attribute, i.e. an inline script block. */
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\s(?:th:)?src=)[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Inline event handler attribute such as {@code onclick=} or {@code onsubmit=} inside a tag. */
    private static final Pattern INLINE_HANDLER = Pattern.compile("<[^>]*\\son[a-z]+\\s*=[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Inline {@code style=} attribute (would require style-src 'unsafe-inline'). */
    private static final Pattern INLINE_STYLE = Pattern.compile("<[^>]*\\sstyle\\s*=[^>]*>", Pattern.CASE_INSENSITIVE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExegeseUserRepository userRepository;

    @Test
    @DisplayName("Strict CSP without 'unsafe-inline' and the extra browser security headers are sent")
    void testStrictContentSecurityPolicyAndHeaders() throws Exception {
        mockMvc.perform(get("/login").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", allOf(
                        containsString("default-src 'self'"),
                        containsString("script-src 'self';"),
                        containsString("style-src 'self';"),
                        containsString("img-src 'self' data: https://*.googleusercontent.com"),
                        containsString("connect-src 'self'"),
                        containsString("object-src 'none'"),
                        containsString("base-uri 'self'"),
                        containsString("form-action 'self'"),
                        containsString("frame-ancestors 'none'"),
                        not(containsString("unsafe-inline")),
                        not(containsString("unsafe-eval")),
                        not(containsString("cdn.tailwindcss.com")))))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    @DisplayName("Every template is CSP-compliant: no CDN, inline scripts, inline handlers or style attributes")
    void testTemplatesHaveNoInlineScriptsHandlersOrStyles() throws Exception {
        Resource[] templates = new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html");
        assertThat(templates).isNotEmpty();

        for (Resource template : templates) {
            String html = read(template);
            String name = template.getFilename();
            assertThat(html).as("Tailwind Play CDN in %s", name).doesNotContain("cdn.tailwindcss.com");
            assertThat(html).as("self-hosted Tailwind stylesheet in %s", name).contains("@{/css/tailwind.css}");
            assertNoInlineCode(html, name);
        }
    }

    @Test
    @DisplayName("Login page renders with the compiled stylesheet and no inline code")
    void testLoginPageRendersCompliantMarkup() throws Exception {
        MvcResult result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/tailwind.css")))
                .andReturn();
        assertNoInlineCode(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "login");
    }

    @Test
    @WithMockUser(username = "csp.chat@exegese.test", roles = "USER")
    @DisplayName("Chat page loads external scripts and exposes server data through data attributes")
    void testChatPageRendersCompliantMarkup() throws Exception {
        userRepository.save(new ExegeseUser("csp.chat@exegese.test", "CSP Chat", UserRole.ROLE_USER));

        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<script src=\"/js/theme-init.js\"></script>")))
                .andExpect(content().string(containsString("<script src=\"/js/chat.js\" defer></script>")))
                .andExpect(content().string(containsString("data-messages-url=\"/api/chat/messages\"")))
                .andExpect(content().string(containsString("id=\"chat-i18n\"")))
                .andExpect(content().string(containsString("data-error-connection-lost=\"")))
                .andExpect(content().string(containsString("data-action=\"toggle-theme\"")))
                .andExpect(content().string(containsString("data-rename-url=\"/chat/")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\" content=\"")))
                .andReturn();
        assertNoInlineCode(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "index");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/users", "/admin/subjects", "/admin/documents", "/admin/models"})
    @WithMockUser(username = "csp.admin@exegese.test", roles = "ADMIN")
    @DisplayName("Admin pages render without inline scripts, handlers or style attributes")
    void testAdminPagesRenderCompliantMarkup(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/common.js")))
                .andExpect(content().string(containsString("/css/tailwind.css")))
                .andReturn();
        assertNoInlineCode(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/js/theme-init.js", "/js/common.js", "/js/chat.js", "/js/admin-documents.js", "/css/tailwind.css"})
    @DisplayName("Static scripts and the compiled stylesheet are public and carry the security headers")
    void testStaticAssetsArePublic(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")));
    }

    @Test
    @DisplayName("Compiled Tailwind stylesheet contains utilities used by templates and by JS-generated DOM")
    void testCompiledTailwindContainsTemplateAndScriptClasses() throws Exception {
        Resource css = new PathMatchingResourcePatternResolver().getResource("classpath:static/css/tailwind.css");
        assertThat(css.exists()).isTrue();
        String stylesheet = read(css);

        assertThat(stylesheet)
                // Template utilities and the class-based dark mode
                .contains(".bg-slate-100", ".dark\\:bg-slate-900", ".lg\\:grid-cols-3", ".hidden")
                // Classes that only appear in /js/chat.js (chat bubbles and stream errors)
                .contains(".whitespace-pre-wrap", ".dark\\:bg-sky-950\\/80", ".text-red-600", ".max-w-2xl")
                // Class added by /js/admin-documents.js while uploading
                .contains(".cursor-not-allowed", ".opacity-60");
    }

    private static void assertNoInlineCode(String html, String name) {
        assertThat(INLINE_SCRIPT.matcher(html).find()).as("inline <script> block in %s", name).isFalse();
        assertThat(INLINE_HANDLER.matcher(html).find()).as("inline event handler attribute in %s", name).isFalse();
        assertThat(INLINE_STYLE.matcher(html).find()).as("inline style attribute in %s", name).isFalse();
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
