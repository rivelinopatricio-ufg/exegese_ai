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
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the account status cache and of the login failure handler.
 *
 * @author Rivelino Patrício
 */
class AccountStatusSupportTest {

    @Test
    @DisplayName("Account status is cached within the TTL and reloaded after eviction")
    void testCacheAndEvict() {
        ExegeseUserRepository repository = mock(ExegeseUserRepository.class);
        ExegeseUser user = new ExegeseUser("cache@exegese.test", "Cache", UserRole.ROLE_USER);
        when(repository.findByEmail("cache@exegese.test")).thenReturn(Optional.of(user));
        UserAccountStatusCache cache = new UserAccountStatusCache(repository, Duration.ofMinutes(5));

        assertThat(cache.lookup("cache@exegese.test")).get()
                .extracting(UserAccountStatusCache.AccountStatus::role).isEqualTo(UserRole.ROLE_USER);
        user.setRole(UserRole.ROLE_ADMIN);
        user.setActive(false);
        // Still served from the cache
        assertThat(cache.lookup("cache@exegese.test")).get()
                .extracting(UserAccountStatusCache.AccountStatus::role).isEqualTo(UserRole.ROLE_USER);
        verify(repository, times(1)).findByEmail("cache@exegese.test");

        cache.evict("cache@exegese.test");
        UserAccountStatusCache.AccountStatus refreshed = cache.lookup("cache@exegese.test").orElseThrow();
        assertThat(refreshed.role()).isEqualTo(UserRole.ROLE_ADMIN);
        assertThat(refreshed.active()).isFalse();
        verify(repository, times(2)).findByEmail("cache@exegese.test");

        assertThat(cache.lookup(null)).isEmpty();
        assertThat(cache.lookup("unknown@exegese.test")).isEmpty();
    }

    @Test
    @DisplayName("Login failure handler forwards known policy error codes and hides any other detail")
    void testFailureHandlerRedirects() throws Exception {
        OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler();

        assertThat(redirectFor(handler, new OAuth2AuthenticationException(
                new OAuth2Error(CustomOidcUserService.ERROR_ACCOUNT_DISABLED)))).isEqualTo("/login?error=account_disabled");
        assertThat(redirectFor(handler, new OAuth2AuthenticationException(
                new OAuth2Error(CustomOidcUserService.ERROR_EMAIL_NOT_VERIFIED)))).isEqualTo("/login?error=email_not_verified");
        assertThat(redirectFor(handler, new OAuth2AuthenticationException(
                new OAuth2Error(CustomOidcUserService.ERROR_EMAIL_DOMAIN_NOT_ALLOWED)))).isEqualTo("/login?error=email_domain_not_allowed");
        assertThat(redirectFor(handler, new OAuth2AuthenticationException(
                new OAuth2Error(CustomOidcUserService.ERROR_ACCOUNT_IDENTITY_MISMATCH)))).isEqualTo("/login?error=account_identity_mismatch");
        assertThat(redirectFor(handler, new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token_response")))).isEqualTo("/login?error");
        assertThat(redirectFor(handler, new BadCredentialsException("bad"))).isEqualTo("/login?error");
    }

    private static String redirectFor(OAuth2LoginFailureHandler handler,
                                      org.springframework.security.core.AuthenticationException exception) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, exception);
        return response.getRedirectedUrl();
    }
}
