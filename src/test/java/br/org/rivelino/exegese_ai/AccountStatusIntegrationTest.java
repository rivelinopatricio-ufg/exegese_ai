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
package br.org.rivelino.exegese_ai;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.service.RagOrchestrationService;
import br.org.rivelino.exegese_ai.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests of the account status checks applied to open sessions (deactivation and role
 * changes take effect without a new login) and of the login page alerts for refused Google logins.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountStatusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExegeseUserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private RagOrchestrationService ragOrchestrationService;

    @AfterEach
    void resetLocalePrompt() {
        // ?lang= on the login page still reconfigures the global prompt (A6, handled separately)
        ragOrchestrationService.configureSystemPromptForLocale(Locale.of("pt", "BR"));
    }

    @Test
    @WithMockUser(username = "desativado@exegese.test", roles = "USER")
    @DisplayName("Session of a deactivated account is invalidated: /login?disabled for pages, 401 for the API")
    void testDeactivatedAccountSessionRejected() throws Exception {
        ExegeseUser user = new ExegeseUser("desativado@exegese.test", "Desativado", UserRole.ROLE_USER);
        user.setActive(false);
        userRepository.save(user);

        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?disabled"));

        mockMvc.perform(get("/api/chat/stream")
                        .param("sessionId", UUID.randomUUID().toString())
                        .param("question", "Pergunta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "suspenso.depois@exegese.test", roles = "USER")
    @DisplayName("Deactivation by an administrator applies to an already authenticated session")
    void testDeactivationAppliesToOpenSession() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("suspenso.depois@exegese.test", "Suspenso", UserRole.ROLE_USER));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk());

        userService.toggleActive("admin@exegese.test", user.getId());

        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?disabled"));
    }

    @Test
    @WithMockUser(username = "promovido@exegese.test", roles = "USER")
    @DisplayName("A role granted after login is applied to the open session (authorities refreshed)")
    void testPromotionRefreshesAuthorities() throws Exception {
        userRepository.save(new ExegeseUser("promovido@exegese.test", "Promovido", UserRole.ROLE_ADMIN));

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "rebaixado@exegese.test", roles = "ADMIN")
    @DisplayName("A role revoked after login stops granting access in the open session")
    void testDemotionRefreshesAuthorities() throws Exception {
        userRepository.save(new ExegeseUser("rebaixado@exegese.test", "Rebaixado", UserRole.ROLE_USER));

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OIDC session: deactivated or missing local account is rejected, role change is refreshed")
    void testOidcSessionsFollowLocalAccount() throws Exception {
        ExegeseUser inactive = new ExegeseUser("oidc.inativo@exegese.test", "OIDC Inativo", UserRole.ROLE_USER);
        inactive.setActive(false);
        userRepository.save(inactive);
        userRepository.save(new ExegeseUser("oidc.admin@exegese.test", "OIDC Admin", UserRole.ROLE_ADMIN));

        mockMvc.perform(get("/").with(googleLogin("oidc.inativo@exegese.test", "ROLE_USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?disabled"));

        mockMvc.perform(get("/").with(googleLogin("oidc.removido@exegese.test", "ROLE_USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?disabled"));

        mockMvc.perform(get("/admin/users").with(googleLogin("oidc.admin@exegese.test", "ROLE_USER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Login page shows a specific, translated alert for each refused-login reason")
    void testLoginPageAlerts() throws Exception {
        mockMvc.perform(get("/login").param("error", "account_disabled"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sua conta está desativada")));

        mockMvc.perform(get("/login").param("disabled", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sua conta está desativada")));

        mockMvc.perform(get("/login").param("error", "email_not_verified").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The Google account e-mail is not verified")));

        mockMvc.perform(get("/login").param("error", "email_domain_not_allowed").param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("no está autorizado")));

        mockMvc.perform(get("/login").param("error", "<script>").param("lang", "pt_BR"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Falha na autenticação corporativa")))
                .andExpect(content().string(not(containsString("<script>"))));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Falha na autenticação corporativa"))));
    }

    private static OidcLoginRequestPostProcessor googleLogin(String email, String role) {
        return oidcLogin()
                .idToken(token -> token.claim("email", email).claim("email_verified", true))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
