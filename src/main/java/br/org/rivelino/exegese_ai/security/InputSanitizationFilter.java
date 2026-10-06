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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Filter sanitizing input parameters and protecting against prompt injection attacks.
 *
 * @author Rivelino Patrício
 */
@Component
public class InputSanitizationFilter extends OncePerRequestFilter {

    private static final int MAX_QUESTION_LENGTH = 2000;

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions|" +
            "ignore\\s+(?:todas\\s+as\\s+)?instruções|" +
            "system\\s+prompt\\s+override|" +
            "mode\\s+developer|" +
            "você\\s+agora\\s+é|" +
            "desconsidere\\s+(?:tudo|as\\s+regras)|" +
            "bypass\\s+safety|" +
            "<script[\\s>]|</script>|\\u0000)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String question = request.getParameter("question");

        if (question != null) {
            if (question.length() > MAX_QUESTION_LENGTH) {
                rejectRequest(response, "O tamanho da pergunta excede o limite máximo de 2000 caracteres.");
                return;
            }

            if (INJECTION_PATTERN.matcher(question).find()) {
                rejectRequest(response, "Entrada rejeitada por violação de segurança (padrão de injeção de prompt detectado).");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void rejectRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"error\": \"%s\"}", message));
    }
}
