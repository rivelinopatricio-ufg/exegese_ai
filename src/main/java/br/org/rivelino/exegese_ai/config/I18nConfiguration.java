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

import br.org.rivelino.exegese_ai.service.RagOrchestrationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Spring Web MVC internationalization configuration setting up cookie-based locale resolution
 * with default Brazilian Portuguese (pt-BR) and query parameter locale switching.
 * Language modification is permitted only prior to login.
 *
 * @author Rivelino Patrício
 */
@Configuration
public class I18nConfiguration implements WebMvcConfigurer {

    public static final String LOCALE_COOKIE_NAME = "EXEGESE_LOCALE";
    public static final String LOCALE_PARAM_NAME = "lang";

    private final ObjectProvider<RagOrchestrationService> ragOrchestrationServiceProvider;

    public I18nConfiguration(ObjectProvider<RagOrchestrationService> ragOrchestrationServiceProvider) {
        this.ragOrchestrationServiceProvider = ragOrchestrationServiceProvider;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LOCALE_COOKIE_NAME);
        resolver.setDefaultLocale(Locale.of("pt", "BR"));
        resolver.setCookieMaxAge(Duration.ofDays(30));
        resolver.setCookiePath("/");
        resolver.setCookieHttpOnly(true);
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws ServletException {
                String newLocale = request.getParameter(getParamName());
                if (newLocale != null) {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    boolean isAuthenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
                    if (isAuthenticated) {
                        // Language alteration is blocked after login to simplify application state
                        return true;
                    }
                    boolean result = super.preHandle(request, response, handler);
                    try {
                        Locale locale = parseLocaleValue(newLocale);
                        ragOrchestrationServiceProvider.ifAvailable(service -> service.configureSystemPromptForLocale(locale));
                    } catch (IllegalArgumentException e) {
                        // ignore malformed locale string
                    }
                    return result;
                }
                return true;
            }
        };
        interceptor.setParamName(LOCALE_PARAM_NAME);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
