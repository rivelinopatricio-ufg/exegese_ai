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
import br.org.rivelino.exegese_ai.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Locale;

/**
 * Authentication success handler orchestrating user bootstrapping, system prompt locale configuration,
 * and post-login redirection.
 *
 * @author Rivelino Patrício
 */
@Component
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final LocaleResolver localeResolver;
    private final ObjectProvider<RagOrchestrationService> ragOrchestrationServiceProvider;

    public GoogleOAuth2SuccessHandler(UserService userService,
                                        LocaleResolver localeResolver,
                                        ObjectProvider<RagOrchestrationService> ragOrchestrationServiceProvider) {
        this.userService = userService;
        this.localeResolver = localeResolver;
        this.ragOrchestrationServiceProvider = ragOrchestrationServiceProvider;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        Object principal = authentication.getPrincipal();

        if (principal instanceof OidcUser oidcUser) {
            String email = oidcUser.getEmail();
            String name = oidcUser.getFullName() != null ? oidcUser.getFullName() : oidcUser.getGivenName();
            if (name == null || name.isBlank()) {
                name = email;
            }
            String avatar = oidcUser.getPicture();
            userService.syncGoogleUser(email, name, avatar);
        } else if (principal instanceof OAuth2User oauth2User) {
            String email = oauth2User.getAttribute("email");
            String name = oauth2User.getAttribute("name");
            String avatar = oauth2User.getAttribute("picture");
            if (email != null) {
                userService.syncGoogleUser(email, name != null ? name : email, avatar);
            }
        }

        Locale userLocale = localeResolver.resolveLocale(request);
        ragOrchestrationServiceProvider.ifAvailable(service -> service.configureSystemPromptForLocale(userLocale));

        response.sendRedirect("/");
    }
}
