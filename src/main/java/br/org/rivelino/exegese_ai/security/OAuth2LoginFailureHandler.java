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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Authentication failure handler for the Google OAuth2 login. Known account policy rejections raised by
 * {@link CustomOidcUserService} are forwarded to the login page as {@code /login?error=<code>} so that a
 * specific, translated message is shown; anything else falls back to the generic {@code /login?error}.
 *
 * @author Rivelino Patrício
 */
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private static final Set<String> FORWARDED_ERROR_CODES = Set.of(
            CustomOidcUserService.ERROR_ACCOUNT_DISABLED,
            CustomOidcUserService.ERROR_EMAIL_NOT_VERIFIED,
            CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED,
            CustomOidcUserService.ERROR_ACCOUNT_IDENTITY_MISMATCH
    );

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String target = "/login?error";
        if (exception instanceof OAuth2AuthenticationException oauth2Exception
                && oauth2Exception.getError() != null
                && FORWARDED_ERROR_CODES.contains(oauth2Exception.getError().getErrorCode())) {
            target = "/login?error=" + oauth2Exception.getError().getErrorCode();
        }
        log.info("Google login rejected: {}", exception instanceof OAuth2AuthenticationException e && e.getError() != null
                ? e.getError().getErrorCode() : exception.getClass().getSimpleName());
        response.sendRedirect(request.getContextPath() + target);
    }
}
