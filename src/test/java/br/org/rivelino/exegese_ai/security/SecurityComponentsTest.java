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

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link JsonErrorResponse} formatting and {@link GoogleOAuth2SuccessHandler} redirections.
 *
 * @author Rivelino Patrício
 */
class SecurityComponentsTest {

    @Test
    @DisplayName("JsonErrorResponse escape handles quotes, newlines, tabs, and control characters")
    void testJsonErrorResponseEscape() {
        String input = "Mensagem com \"aspas\", barra \\, quebra \n e retorno \r, tab \t e controle \u0007.";
        String escaped = JsonErrorResponse.escape(input);

        assertThat(escaped).contains("\\\"aspas\\\"")
                .contains("\\\\")
                .contains("\\n")
                .contains("\\r")
                .contains("\\t")
                .contains("\\u0007");
    }

    @Test
    @DisplayName("JsonErrorResponse write sets status, UTF-8 content type, and writes formatted JSON")
    void testJsonErrorResponseWrite() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonErrorResponse.write(response, 403, "Acesso Negado: \"Sem Permissão\"");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Acesso Negado: \\\"Sem Permissão\\\"\"}");
    }

    @Test
    @DisplayName("GoogleOAuth2SuccessHandler redirects to context root on authentication success")
    void testGoogleOAuth2SuccessHandler() throws ServletException, IOException {
        GoogleOAuth2SuccessHandler handler = new GoogleOAuth2SuccessHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/exegese");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/exegese/");
    }
}
