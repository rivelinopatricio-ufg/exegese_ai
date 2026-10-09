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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.service.UserService;

/**
 * Unit tests for {@link SecurityContextFacade} verifying email extraction, user account lookup, and role authorization.
 *
 * @author Rivelino Patrício
 */
class SecurityContextFacadeTest {

    @Mock
    private UserService userService;

    private SecurityContextFacade facade;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        facade = new SecurityContextFacade(userService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("extractEmail returns empty when authentication is null or unauthenticated")
    void testExtractEmailNullOrUnauthenticated() {
        assertThat(SecurityContextFacade.extractEmail(null)).isEmpty();

        Authentication unauth = mock(Authentication.class);
        when(unauth.isAuthenticated()).thenReturn(false);
        assertThat(SecurityContextFacade.extractEmail(unauth)).isEmpty();
    }

    @Test
    @DisplayName("extractEmail extracts email from OidcUser, OAuth2User, UserDetails, and String principal")
    void testExtractEmailVariousPrincipals() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getEmail()).thenReturn("oidc@example.com");
        Authentication oidcAuth = new UsernamePasswordAuthenticationToken(oidcUser, null, List.of());
        assertThat(SecurityContextFacade.extractEmail(oidcAuth)).contains("oidc@example.com");

        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttribute("email")).thenReturn("oauth2@example.com");
        Authentication oauth2Auth = new UsernamePasswordAuthenticationToken(oauth2User, null, List.of());
        assertThat(SecurityContextFacade.extractEmail(oauth2Auth)).contains("oauth2@example.com");

        UserDetails userDetails = User.withUsername("user@example.com").password("pass").roles("USER").build();
        Authentication userDetailsAuth = new UsernamePasswordAuthenticationToken(userDetails, null, List.of());
        assertThat(SecurityContextFacade.extractEmail(userDetailsAuth)).contains("user@example.com");

        Authentication stringAuth = new UsernamePasswordAuthenticationToken("string@example.com", null, List.of());
        assertThat(SecurityContextFacade.extractEmail(stringAuth)).contains("string@example.com");

        Authentication anonAuth = new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of());
        assertThat(SecurityContextFacade.extractEmail(anonAuth)).isEmpty();
    }

    @Test
    @DisplayName("getCurrentUser and requireCurrentUser resolve user account correctly")
    void testCurrentUserResolution() {
        String email = "admin@exegese.org";
        ExegeseUser user = new ExegeseUser("Admin", email, UserRole.ROLE_ADMIN);
        when(userService.findByEmail(email)).thenReturn(Optional.of(user));

        Authentication auth = new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(facade.getCurrentUserEmail()).contains(email);
        assertThat(facade.getCurrentUser()).contains(user);
        assertThat(facade.requireCurrentUser()).isEqualTo(user);
        assertThat(facade.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("requireCurrentUser throws InsufficientAuthenticationException when local user is absent")
    void testRequireCurrentUserThrowsWhenAbsent() {
        String email = "unknown@exegese.org";
        when(userService.findByEmail(email)).thenReturn(Optional.empty());

        Authentication auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> facade.requireCurrentUser())
                .isInstanceOf(InsufficientAuthenticationException.class)
                .hasMessageContaining("No local account for the authenticated principal");
    }
}
