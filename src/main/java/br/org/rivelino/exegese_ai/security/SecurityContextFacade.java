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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.service.UserService;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Facade providing clean decoupled access to current authenticated security context.
 *
 * @author Rivelino Patrício
 */
@Component
public class SecurityContextFacade {

    private final UserService userService;

    public SecurityContextFacade(UserService userService) {
        this.userService = userService;
    }

    public Optional<String> getCurrentUserEmail() {
        return extractEmail(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Extracts the account e-mail from an authentication (OIDC/OAuth2 e-mail claim or username).
     *
     * @param auth Authentication to inspect (may be null)
     * @return The e-mail, or empty for anonymous or unidentifiable principals
     */
    public static Optional<String> extractEmail(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = auth.getPrincipal();
        String email = null;
        if (principal instanceof OidcUser oidcUser) {
            email = oidcUser.getEmail();
        } else if (principal instanceof OAuth2User oauth2User) {
            email = oauth2User.getAttribute("email");
        } else if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername();
        } else if (principal instanceof String principalString && !"anonymousUser".equals(principalString)) {
            email = principalString;
        }
        return email == null || email.isBlank() ? Optional.empty() : Optional.of(email);
    }

    public Optional<ExegeseUser> getCurrentUser() {
        return getCurrentUserEmail().flatMap(userService::findByEmail);
    }

    /**
     * Resolves the local account of the authenticated principal. Never provisions a fallback account:
     * a principal without e-mail or without a local account is treated as unauthenticated.
     *
     * @return The local account of the current user
     * @throws InsufficientAuthenticationException when there is no matching local account
     */
    public ExegeseUser requireCurrentUser() {
        return getCurrentUser().orElseThrow(() ->
                new InsufficientAuthenticationException("No local account for the authenticated principal"));
    }

    /**
     * Whether the current authentication holds ROLE_ADMIN. Authorities are kept in sync with the
     * persisted role by {@link AccountStatusFilter}.
     */
    public boolean isAdmin() {
        return hasAnyAuthority(UserRole.ROLE_ADMIN.name());
    }

    /**
     * Whether the current authentication holds ROLE_ADMIN or ROLE_OPERATOR.
     */
    public boolean isOperatorOrAdmin() {
        return hasAnyAuthority(UserRole.ROLE_ADMIN.name(), UserRole.ROLE_OPERATOR.name());
    }

    private static boolean hasAnyAuthority(String... authorities) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Set<String> wanted = Set.of(authorities);
        return auth.getAuthorities().stream().anyMatch(a -> wanted.contains(a.getAuthority()));
    }
}
