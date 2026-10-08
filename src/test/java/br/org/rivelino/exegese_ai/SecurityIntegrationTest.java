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
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.repository.UserSubjectPermissionRepository;
import br.org.rivelino.exegese_ai.security.UserAccountStatusCache;
import br.org.rivelino.exegese_ai.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test validating Spring Security filters, OAuth2 routes and RBAC permissions.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Value of exegese.initial-admin-email in application-test.properties. */
    private static final String INITIAL_ADMIN = "bootstrap.admin@exegese.test";

    @Autowired
    private UserService userService;

    @Autowired
    private ExegeseUserRepository userRepository;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private UserSubjectPermissionRepository permissionRepository;

    @Autowired
    private UserAccountStatusCache accountStatusCache;

    @Test
    @DisplayName("Public routes are accessible anonymously without redirection")
    void testPublicRoutesAccessible() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/css/app.css"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated request to protected admin route redirects to login")
    void testProtectedRoutesRedirect() throws Exception {
        mockMvc.perform(get("/admin/users"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(username = "standard@receita.gov.br", roles = {"USER"})
    @DisplayName("User with ROLE_USER is forbidden (403) from accessing admin routes")
    void testRoleUserForbiddenOnAdmin() throws Exception {
        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@exegese.ai", roles = {"ADMIN"})
    @DisplayName("User with ROLE_ADMIN is authorized (200) on admin routes")
    void testRoleAdminAuthorizedOnAdmin() throws Exception {
        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bootstrap of first administrator via INITIAL_ADMIN_EMAIL while no administrator exists")
    void testBootstrapInitialAdmin() {
        demoteEveryAdmin();

        ExegeseUser adminUser = userService.syncGoogleUser(INITIAL_ADMIN, "Primeiro Administrador", "https://avatar.url");
        assertThat(adminUser.getRole()).isEqualTo(UserRole.ROLE_ADMIN);

        ExegeseUser standardUser = userService.syncGoogleUser("outro@exegese.ai", "Outro Usuário", null);
        assertThat(standardUser.getRole()).isEqualTo(UserRole.ROLE_USER);
    }

    @Test
    @DisplayName("INITIAL_ADMIN_EMAIL is not re-elevated on login once an administrator exists")
    void testInitialAdminNotReElevatedWhenAdminExists() {
        demoteEveryAdmin();
        userRepository.save(new ExegeseUser("current.admin@exegese.test", "Admin Atual", UserRole.ROLE_ADMIN));

        ExegeseUser newcomer = userService.syncGoogleUser(INITIAL_ADMIN, "Admin Inicial", null);
        assertThat(newcomer.getRole()).isEqualTo(UserRole.ROLE_USER);

        // An initial admin demoted later is not promoted back on the next login
        newcomer.setRole(UserRole.ROLE_OPERATOR);
        userRepository.save(newcomer);
        ExegeseUser again = userService.syncGoogleUser(INITIAL_ADMIN, "Admin Inicial", null);
        assertThat(again.getRole()).isEqualTo(UserRole.ROLE_OPERATOR);
    }

    @Test
    @DisplayName("Blank INITIAL_ADMIN_EMAIL promotes nobody (no built-in admin@exegese.ai default)")
    void testBlankInitialAdminGrantsNothing() {
        demoteEveryAdmin();
        UserService withoutInitialAdmin = new UserService(userRepository, subjectRepository, permissionRepository,
                accountStatusCache, "");

        assertThat(withoutInitialAdmin.isInitialAdmin("admin@exegese.ai")).isFalse();
        ExegeseUser legacyDefault = withoutInitialAdmin.syncGoogleUser("admin@exegese.ai", "Antigo Padrão", null);
        assertThat(legacyDefault.getRole()).isEqualTo(UserRole.ROLE_USER);
        assertThat(userService.isInitialAdmin("admin@exegese.ai")).isFalse();
    }

    private void demoteEveryAdmin() {
        for (ExegeseUser admin : userRepository.findAll()) {
            if (admin.getRole() == UserRole.ROLE_ADMIN) {
                admin.setRole(UserRole.ROLE_USER);
                userRepository.save(admin);
            }
        }
        userRepository.flush();
    }

    @Test
    @WithMockUser(username = "user@exegese.ai", roles = {"USER"})
    @DisplayName("GET /logout terminates session and redirects to /login?logout")
    void testLogoutViaGetRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/logout"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?logout"));
    }
}
