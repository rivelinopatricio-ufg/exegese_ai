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

import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.security.UserAccountStatusCache.AccountStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter running after authentication that re-validates every authenticated request against the
 * account's current state (cached for a few seconds by {@link UserAccountStatusCache}):
 * <ul>
 *   <li>a deactivated account (or an OAuth2 login whose local account no longer exists) has its HTTP session
 *       invalidated and is sent to {@code /login?disabled} (HTTP 401 for {@code /api/**});</li>
 *   <li>an account whose role changed has the authorities of its {@link Authentication} refreshed in place,
 *       so that privileges granted or revoked by an administrator apply without a new login.</li>
 * </ul>
 * It is registered only inside the Spring Security filter chain (not as a servlet filter bean).
 *
 * @author Rivelino Patrício
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusFilter.class);

    private static final Set<String> APPLICATION_ROLES = Arrays.stream(UserRole.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final UserAccountStatusCache accountStatusCache;
    private final SecurityContextRepository securityContextRepository;

    public AccountStatusFilter(UserAccountStatusCache accountStatusCache) {
        this.accountStatusCache = accountStatusCache;
        this.securityContextRepository = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AccountStatus> status = SecurityContextFacade.extractEmail(authentication)
                .flatMap(accountStatusCache::lookup);

        if (status.isEmpty()) {
            if (authentication instanceof OAuth2AuthenticationToken) {
                // Every OAuth2 login is synchronized with a local account: a missing one was removed
                rejectSession(request, response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (!status.get().active()) {
            log.info("Rejected request of deactivated account {}", status.get().userId());
            rejectSession(request, response);
            return;
        }

        UserRole currentRole = status.get().role();
        if (!grantedApplicationRoles(authentication).equals(Set.of(currentRole.name()))) {
            Authentication refreshed = withRole(authentication, currentRole);
            if (refreshed == null) {
                rejectSession(request, response);
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(refreshed);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            log.info("Refreshed authorities of account {} to {}", status.get().userId(), currentRole);
        }

        filterChain.doFilter(request, response);
    }

    private static Set<String> grantedApplicationRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(APPLICATION_ROLES::contains)
                .collect(Collectors.toSet());
    }

    private static Authentication withRole(Authentication authentication, UserRole role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (!APPLICATION_ROLES.contains(authority.getAuthority())) {
                authorities.add(authority);
            }
        }
        authorities.add(new SimpleGrantedAuthority(role.name()));

        if (authentication instanceof OAuth2AuthenticationToken oauth2Token) {
            OAuth2User principal = oauth2Token.getPrincipal();
            if (principal instanceof OidcUser oidcUser) {
                principal = new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
            }
            OAuth2AuthenticationToken refreshed = new OAuth2AuthenticationToken(
                    principal, authorities, oauth2Token.getAuthorizedClientRegistrationId());
            refreshed.setDetails(oauth2Token.getDetails());
            return refreshed;
        }
        if (authentication instanceof UsernamePasswordAuthenticationToken) {
            UsernamePasswordAuthenticationToken refreshed = UsernamePasswordAuthenticationToken.authenticated(
                    authentication.getPrincipal(), authentication.getCredentials(), authorities);
            refreshed.setDetails(authentication.getDetails());
            return refreshed;
        }
        return null;
    }

    private static void rejectSession(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?disabled");
        }
    }
}
