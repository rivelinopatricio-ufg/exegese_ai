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
package br.org.rivelino.exegese_ai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Filter validating the chat {@code question} parameter of every request (notably
 * {@code POST /api/chat/messages}): it enforces the maximum length and rejects control characters
 * (null bytes included).
 * <p>
 * The prompt-injection pattern check is only defense in depth: a blacklist is easy to bypass, so the real
 * protection is structural (delimited data sections in the prompt, see {@code RagOrchestrationService}).
 * Rejections are logged without the question text (LGPD).
 *
 * @author Rivelino Patrício
 */
@Component
public class InputSanitizationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InputSanitizationFilter.class);

    public static final int MAX_QUESTION_LENGTH = 2000;

    /** C0 control characters except TAB, LF and CR, plus DEL. */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions|" +
            "ignore\\s+(?:todas\\s+as\\s+)?instruções|" +
            "system\\s+prompt\\s+override|" +
            "mode\\s+developer|" +
            "você\\s+agora\\s+é|" +
            "desconsidere\\s+(?:tudo|as\\s+regras)|" +
            "bypass\\s+safety|" +
            "<script[\\s>]|</script>)",
            Pattern.CASE_INSENSITIVE
    );

    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    public InputSanitizationFilter(MessageSource messageSource, LocaleResolver localeResolver) {
        this.messageSource = messageSource;
        this.localeResolver = localeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String question = request.getParameter("question");

        if (question != null) {
            if (question.length() > MAX_QUESTION_LENGTH) {
                reject(request, response, "too_long", question.length(), "security.input.too_long",
                        new Object[]{String.valueOf(MAX_QUESTION_LENGTH)});
                return;
            }

            if (CONTROL_CHARACTERS.matcher(question).find()) {
                reject(request, response, "control_characters", question.length(), "security.input.invalid_characters", null);
                return;
            }

            if (INJECTION_PATTERN.matcher(question).find()) {
                reject(request, response, "injection_pattern", question.length(), "security.input.injection_detected", null);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        String reason,
                        int length,
                        String messageKey,
                        Object[] args) throws IOException {
        // Never log the question itself: it may contain personal or fiscal data
        log.warn("Rejected question parameter on {} {}: reason={}, length={}",
                request.getMethod(), request.getRequestURI(), reason, length);
        String message = messageSource.getMessage(messageKey, args, localeResolver.resolveLocale(request));
        JsonErrorResponse.write(response, HttpServletResponse.SC_BAD_REQUEST, message);
    }
}
