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
import br.org.rivelino.exegese_ai.service.GoogleIdentityMismatchException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        ExegeseUser adminUser = userService.syncGoogleUser(INITIAL_ADMIN, "Primeiro Administrador", "https://avatar.url", "sub-" + INITIAL_ADMIN);
        assertThat(adminUser.getRole()).isEqualTo(UserRole.ROLE_ADMIN);

        ExegeseUser standardUser = userService.syncGoogleUser("outro@exegese.ai", "Outro Usuário", null, "sub-outro@exegese.ai");
        assertThat(standardUser.getRole()).isEqualTo(UserRole.ROLE_USER);
    }

    @Test
    @DisplayName("INITIAL_ADMIN_EMAIL is not re-elevated on login once an administrator exists")
    void testInitialAdminNotReElevatedWhenAdminExists() {
        demoteEveryAdmin();
        userRepository.save(new ExegeseUser("current.admin@exegese.test", "Admin Atual", UserRole.ROLE_ADMIN));

        ExegeseUser newcomer = userService.syncGoogleUser(INITIAL_ADMIN, "Admin Inicial", null, "sub-" + INITIAL_ADMIN);
        assertThat(newcomer.getRole()).isEqualTo(UserRole.ROLE_USER);

        // An initial admin demoted later is not promoted back on the next login
        newcomer.setRole(UserRole.ROLE_OPERATOR);
        userRepository.save(newcomer);
        ExegeseUser again = userService.syncGoogleUser(INITIAL_ADMIN, "Admin Inicial", null, "sub-" + INITIAL_ADMIN);
        assertThat(again.getRole()).isEqualTo(UserRole.ROLE_OPERATOR);
    }

    @Test
    @DisplayName("Blank INITIAL_ADMIN_EMAIL promotes nobody (no built-in admin@exegese.ai default)")
    void testBlankInitialAdminGrantsNothing() {
        demoteEveryAdmin();
        UserService withoutInitialAdmin = new UserService(userRepository, subjectRepository, permissionRepository,
                accountStatusCache, "");

        assertThat(withoutInitialAdmin.isInitialAdmin("admin@exegese.ai")).isFalse();
        ExegeseUser legacyDefault = withoutInitialAdmin.syncGoogleUser("admin@exegese.ai", "Antigo Padrão", null, "sub-admin@exegese.ai");
        assertThat(legacyDefault.getRole()).isEqualTo(UserRole.ROLE_USER);
        assertThat(userService.isInitialAdmin("admin@exegese.ai")).isFalse();
    }

    @Test
    @DisplayName("Google subject is bound on first login; a reassigned e-mail asserted by another subject never takes over the account")
    void testGoogleSubjectBinding() {
        demoteEveryAdmin();
        // Account created before subjects were stored (unbound): the next login binds it
        ExegeseUser legacy = userRepository.save(new ExegeseUser("alice.legacy@exegese.test", "Alice", UserRole.ROLE_USER));
        ExegeseUser bound = userService.syncGoogleUser("alice.legacy@exegese.test", "Alice", null, "sub-alice-original");
        assertThat(bound.getId()).isEqualTo(legacy.getId());
        assertThat(bound.getGoogleSub()).isEqualTo("sub-alice-original");

        // Same e-mail, another Google account (address kept by a consumer account or reassigned): refused
        assertThatThrownBy(() -> userService.syncGoogleUser("alice.legacy@exegese.test", "Intruso", null, "sub-intruder"))
                .isInstanceOf(GoogleIdentityMismatchException.class);
        assertThat(userRepository.findById(legacy.getId()).orElseThrow().getName()).isEqualTo("Alice");

        // The bootstrap administrator address claimed by a foreign subject is refused before any promotion
        userRepository.save(boundUser(INITIAL_ADMIN, "sub-initial-admin"));
        assertThatThrownBy(() -> userService.syncGoogleUser(INITIAL_ADMIN, "Impostor", null, "sub-impostor"))
                .isInstanceOf(GoogleIdentityMismatchException.class);
        assertThat(userRepository.existsByRole(UserRole.ROLE_ADMIN)).isFalse();

        // A known subject whose Google e-mail changed keeps its account
        ExegeseUser renamed = userService.syncGoogleUser("alice.nova@exegese.test", "Alice", null, "sub-alice-original");
        assertThat(renamed.getId()).isEqualTo(legacy.getId());
        assertThat(renamed.getEmail()).isEqualTo("alice.nova@exegese.test");

        // ...but never by taking the e-mail of another local account
        userRepository.save(boundUser("bruno@exegese.test", "sub-bruno"));
        assertThatThrownBy(() -> userService.syncGoogleUser("bruno@exegese.test", "Alice", null, "sub-alice-original"))
                .isInstanceOf(GoogleIdentityMismatchException.class);
    }

    private static ExegeseUser boundUser(String email, String googleSub) {
        ExegeseUser user = new ExegeseUser(email, email, UserRole.ROLE_USER);
        user.setGoogleSub(googleSub);
        return user;
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
