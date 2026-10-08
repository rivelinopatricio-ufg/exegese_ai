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

import br.org.rivelino.exegese_ai.service.RagOrchestrationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Locale;

/**
 * Authentication success handler orchestrating system prompt locale configuration and post-login redirection.
 * The local account is synchronized once, by {@link CustomOidcUserService}, before this handler runs.
 *
 * @author Rivelino Patrício
 */
@Component
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final LocaleResolver localeResolver;
    private final ObjectProvider<RagOrchestrationService> ragOrchestrationServiceProvider;

    public GoogleOAuth2SuccessHandler(LocaleResolver localeResolver,
                                        ObjectProvider<RagOrchestrationService> ragOrchestrationServiceProvider) {
        this.localeResolver = localeResolver;
        this.ragOrchestrationServiceProvider = ragOrchestrationServiceProvider;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        Locale userLocale = localeResolver.resolveLocale(request);
        ragOrchestrationServiceProvider.ifAvailable(service -> service.configureSystemPromptForLocale(userLocale));

        response.sendRedirect("/");
    }
}
