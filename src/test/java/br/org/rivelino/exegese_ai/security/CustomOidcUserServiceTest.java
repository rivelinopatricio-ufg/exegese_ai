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
import br.org.rivelino.exegese_ai.service.GoogleIdentityMismatchException;
import br.org.rivelino.exegese_ai.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the Google login policy applied by {@link CustomOidcUserService}: verified e-mail,
 * allowed domains, Workspace hosted domain, Google subject binding and deactivated accounts.
 *
 * @author Rivelino Patrício
 */
class CustomOidcUserServiceTest {

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        when(userService.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userService.findByGoogleSub(anyString())).thenReturn(Optional.empty());
        when(userService.syncGoogleUser(anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> new ExegeseUser(invocation.getArgument(0), invocation.getArgument(1), UserRole.ROLE_USER));
    }

    @Test
    @DisplayName("Verified Google account is synchronized once and receives its local role as authority")
    void testVerifiedAccountAccepted() {
        CustomOidcUserService service = new CustomOidcUserService(userService, "", true);

        OidcUser result = service.authorizeAndSynchronize(oidcUser("ana@gmail.com", true));

        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_USER", "OIDC_USER");
        verify(userService).syncGoogleUser(eq("ana@gmail.com"), eq("Ana Teste"), any(), eq("google-sub-ana@gmail.com"));
    }

    @Test
    @DisplayName("Login is refused with email_not_verified when email_verified is false or missing")
    void testUnverifiedEmailRejected() {
        CustomOidcUserService service = new CustomOidcUserService(userService, "", true);

        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("ana@gmail.com", false)),
                CustomOidcUserService.ERROR_EMAIL_NOT_VERIFIED);
        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("ana@gmail.com", null)),
                CustomOidcUserService.ERROR_EMAIL_NOT_VERIFIED);
        verify(userService, never()).syncGoogleUser(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Login is refused with account_disabled for a deactivated local account, without updating it")
    void testDeactivatedAccountRejected() {
        ExegeseUser disabled = new ExegeseUser("bia@empresa.gov.br", "Bia", UserRole.ROLE_ADMIN);
        disabled.setActive(false);
        when(userService.findByEmail("bia@empresa.gov.br")).thenReturn(Optional.of(disabled));
        CustomOidcUserService service = new CustomOidcUserService(userService, "", true);

        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("bia@empresa.gov.br", true)),
                CustomOidcUserService.ERROR_ACCOUNT_DISABLED);
        verify(userService, never()).syncGoogleUser(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("With allowed-email-domains set, other domains are refused and listed ones accepted (case-insensitive)")
    void testAllowedEmailDomains() {
        CustomOidcUserService service = new CustomOidcUserService(userService, " Empresa.gov.br , @parceiro.org ", true);

        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("intruso@gmail.com", true, null)),
                CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED);
        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("sub@sub.empresa.gov.br", true, "sub.empresa.gov.br")),
                CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED);

        assertThat(service.authorizeAndSynchronize(oidcUser("servidor@EMPRESA.gov.br", true, "empresa.gov.br"))).isNotNull();
        assertThat(service.authorizeAndSynchronize(oidcUser("consultor@parceiro.org", true, "Parceiro.org"))).isNotNull();
    }

    @Test
    @DisplayName("With allowed-email-domains set, the account must be managed by that Workspace domain (hd claim)")
    void testHostedDomainRequired() {
        CustomOidcUserService service = new CustomOidcUserService(userService, "empresa.gov.br,parceiro.org", true);

        // Consumer Google account holding a verified address of the domain (e.g. a former employee): no hd
        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("ex.servidor@empresa.gov.br", true, null)),
                CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED);
        // Managed by another allowed domain than the e-mail's own
        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("servidor@empresa.gov.br", true, "parceiro.org")),
                CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED);
        // Managed by a domain outside the allowlist
        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("servidor@empresa.gov.br", true, "outra.com")),
                CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED);
        verify(userService, never()).syncGoogleUser(anyString(), anyString(), any(), anyString());

        assertThat(service.authorizeAndSynchronize(oidcUser("servidor@empresa.gov.br", true, "empresa.gov.br"))).isNotNull();

        // Opt-out for domains whose mail is not hosted on Google Workspace
        CustomOidcUserService withoutHd = new CustomOidcUserService(userService, "empresa.gov.br", false);
        assertThat(withoutHd.authorizeAndSynchronize(oidcUser("servidor@empresa.gov.br", true, null))).isNotNull();
        // Without an allowlist the hd claim is irrelevant
        CustomOidcUserService open = new CustomOidcUserService(userService, "", true);
        assertThat(open.authorizeAndSynchronize(oidcUser("ana@gmail.com", true, null))).isNotNull();
    }

    @Test
    @DisplayName("An e-mail whose local account is bound to another Google subject is refused (reassigned address)")
    void testSubjectMismatchRejected() {
        ExegeseUser admin = new ExegeseUser("alice@empresa.gov.br", "Alice", UserRole.ROLE_ADMIN);
        admin.setGoogleSub("google-sub-original-alice");
        when(userService.findByEmail("alice@empresa.gov.br")).thenReturn(Optional.of(admin));
        CustomOidcUserService service = new CustomOidcUserService(userService, "", true);

        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("alice@empresa.gov.br", true)),
                CustomOidcUserService.ERROR_ACCOUNT_IDENTITY_MISMATCH);
        verify(userService, never()).syncGoogleUser(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("A subject mismatch detected while synchronizing (concurrent binding) is refused as well")
    void testSubjectMismatchDuringSyncRejected() {
        when(userService.syncGoogleUser(anyString(), anyString(), any(), anyString()))
                .thenThrow(new GoogleIdentityMismatchException("Local account is bound to another Google subject"));
        CustomOidcUserService service = new CustomOidcUserService(userService, "", true);

        assertRejected(() -> service.authorizeAndSynchronize(oidcUser("bruno@gmail.com", true)),
                CustomOidcUserService.ERROR_ACCOUNT_IDENTITY_MISMATCH);
    }

    private static void assertRejected(Runnable call, String expectedErrorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo(expectedErrorCode));
    }

    private static OidcUser oidcUser(String email, Boolean emailVerified) {
        return oidcUser(email, emailVerified, null);
    }

    private static OidcUser oidcUser(String email, Boolean emailVerified, String hostedDomain) {
        OidcIdToken.Builder builder = OidcIdToken.withTokenValue("id-token")
                .subject("google-sub-" + email)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("email", email)
                .claim("name", "Ana Teste");
        if (emailVerified != null) {
            builder.claim("email_verified", emailVerified);
        }
        if (hostedDomain != null) {
            builder.claim("hd", hostedDomain);
        }
        OidcIdToken idToken = builder.build();
        return new DefaultOidcUser(Set.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
    }
}
