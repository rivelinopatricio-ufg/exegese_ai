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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermission;
import br.org.rivelino.exegese_ai.domain.enums.SubjectPermissionLevel;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.service.AdminActionRejectedException;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test validating the administrative user management panel and permissions delegation.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserManagementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExegeseUserRepository userRepository;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private UserService userService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin lists users and subjects successfully")
    void testAdminListUsers() throws Exception {
        userRepository.save(new ExegeseUser("user1@receita.gov.br", "Operador 1", UserRole.ROLE_OPERATOR));
        subjectRepository.save(new ExegeseSubject("tributario-irpf", "Tributário IRPF 2026", "Manual"));

        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin promotes user to ROLE_OPERATOR")
    void testUpdateUserRole() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("servidor@gov.br", "Servidor", UserRole.ROLE_USER));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/role")
                .param("role", "ROLE_OPERATOR")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/users"));

        ExegeseUser updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getRole()).isEqualTo(UserRole.ROLE_OPERATOR);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin grants subject permission to user")
    void testGrantSubjectPermissions() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("analista@gov.br", "Analista", UserRole.ROLE_USER));
        ExegeseSubject subject = subjectRepository.save(new ExegeseSubject("trabalhista", "Normas Trabalhistas", "CLT"));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/permissions")
                .param("subjectIds", subject.getId().toString())
                .param("level", "MANAGE")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/users"));

        List<UserSubjectPermission> perms = userService.getUserPermissions(user.getId());
        assertThat(perms).hasSize(1);
        assertThat(perms.get(0).getPermissionLevel()).isEqualTo("MANAGE");
        assertThat(perms.get(0).getSubject().getId()).isEqualTo(subject.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin toggles user active status")
    void testToggleUserActive() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("suspenso@gov.br", "Usuário Suspenso", UserRole.ROLE_USER));
        assertThat(user.isActive()).isTrue();

        mockMvc.perform(post("/admin/users/" + user.getId() + "/toggle-active")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/users"));

        ExegeseUser updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.isActive()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Permission level outside the enum (READ, WRITE, MANAGE) is rejected with HTTP 400")
    void testInvalidPermissionLevelRejected() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("nivel@gov.br", "Nível", UserRole.ROLE_USER));
        ExegeseSubject subject = subjectRepository.save(new ExegeseSubject("nivel-invalido", "Nível Inválido", "Teste"));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/permissions")
                .param("subjectIds", subject.getId().toString())
                .param("level", "SUPERUSER")
                .with(csrf()))
            .andExpect(status().isBadRequest());

        assertThat(userService.getUserPermissions(user.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin revokes a subject permission through the revoke endpoint")
    void testRevokeSubjectPermission() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("revogar@gov.br", "Revogar", UserRole.ROLE_USER));
        ExegeseSubject subject = subjectRepository.save(new ExegeseSubject("revogavel", "Assunto Revogável", "Teste"));
        userService.grantSubjectPermission("admin", user.getId(), subject.getId(), SubjectPermissionLevel.WRITE);

        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                    "/admin/users/" + user.getId() + "/permissions/" + subject.getId() + "/revoke")))
            .andExpect(content().string(containsString("Assunto Revogável")));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/permissions/" + subject.getId() + "/revoke")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/users"))
            .andExpect(flash().attribute("adminSuccessKey", "admin.users.success.permission_revoked"));

        assertThat(userService.getUserPermissions(user.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "self.admin@gov.br", roles = "ADMIN")
    @DisplayName("Admin cannot demote or deactivate their own account")
    void testAdminCannotDemoteOrDeactivateSelf() throws Exception {
        ExegeseUser self = userRepository.save(new ExegeseUser("self.admin@gov.br", "Self Admin", UserRole.ROLE_ADMIN));
        userRepository.save(new ExegeseUser("other.admin@gov.br", "Other Admin", UserRole.ROLE_ADMIN));

        mockMvc.perform(post("/admin/users/" + self.getId() + "/role")
                .param("role", "ROLE_USER")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("adminErrorKey", "admin.users.error.self_change"));

        mockMvc.perform(post("/admin/users/" + self.getId() + "/toggle-active")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("adminErrorKey", "admin.users.error.self_change"));

        ExegeseUser unchanged = userRepository.findById(self.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(UserRole.ROLE_ADMIN);
        assertThat(unchanged.isActive()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("The last active administrator cannot be demoted or deactivated")
    void testLastActiveAdminIsProtected() throws Exception {
        demoteEveryAdmin();
        ExegeseUser lastAdmin = userRepository.save(new ExegeseUser("ultimo.admin@gov.br", "Último Admin", UserRole.ROLE_ADMIN));

        mockMvc.perform(post("/admin/users/" + lastAdmin.getId() + "/role")
                .param("role", "ROLE_OPERATOR")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("adminErrorKey", "admin.users.error.last_admin"));

        mockMvc.perform(post("/admin/users/" + lastAdmin.getId() + "/toggle-active")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("adminErrorKey", "admin.users.error.last_admin"));

        ExegeseUser unchanged = userRepository.findById(lastAdmin.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(UserRole.ROLE_ADMIN);
        assertThat(unchanged.isActive()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("An administrator can be demoted while another active administrator remains")
    void testAdminDemotedWhenAnotherActiveAdminExists() throws Exception {
        demoteEveryAdmin();
        ExegeseUser first = userRepository.save(new ExegeseUser("admin.a@gov.br", "Admin A", UserRole.ROLE_ADMIN));
        userRepository.save(new ExegeseUser("admin.b@gov.br", "Admin B", UserRole.ROLE_ADMIN));

        mockMvc.perform(post("/admin/users/" + first.getId() + "/role")
                .param("role", "ROLE_USER")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("adminSuccessKey", "admin.users.success.role_updated"));

        assertThat(userRepository.findById(first.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ROLE_USER);
    }

    @Test
    @DisplayName("An inactive administrator does not count as the remaining active administrator")
    void testInactiveAdminDoesNotCountAsRemainingAdmin() {
        demoteEveryAdmin();
        ExegeseUser active = userRepository.save(new ExegeseUser("ativo.admin@gov.br", "Ativo", UserRole.ROLE_ADMIN));
        ExegeseUser inactive = new ExegeseUser("inativo.admin@gov.br", "Inativo", UserRole.ROLE_ADMIN);
        inactive.setActive(false);
        userRepository.save(inactive);

        assertThatThrownBy(() -> userService.updateRole("someone@gov.br", active.getId(), UserRole.ROLE_USER))
            .isInstanceOf(AdminActionRejectedException.class)
            .hasMessage("admin.users.error.last_admin");
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
}
