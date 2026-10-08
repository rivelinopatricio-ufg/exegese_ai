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
package br.org.rivelino.exegese_ai.controller;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.SubjectPermissionLevel;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.security.SecurityContextFacade;
import br.org.rivelino.exegese_ai.service.AdminActionRejectedException;
import br.org.rivelino.exegese_ai.service.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Controller for administrative user management and RBAC governance.
 * Role and permission-level parameters are bound to enums (invalid values answer HTTP 400), and
 * governance rejections (self-demotion, last active administrator) are reported as flash messages.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    static final String FLASH_SUCCESS = "adminSuccessKey";
    static final String FLASH_ERROR = "adminErrorKey";

    private final UserService userService;
    private final ExegeseSubjectRepository subjectRepository;
    private final SecurityContextFacade securityContextFacade;

    public AdminUserController(UserService userService,
                               ExegeseSubjectRepository subjectRepository,
                               SecurityContextFacade securityContextFacade) {
        this.userService = userService;
        this.subjectRepository = subjectRepository;
        this.securityContextFacade = securityContextFacade;
    }

    @GetMapping
    public String listUsers(Model model) {
        List<ExegeseUser> users = userService.findAll();
        List<ExegeseSubject> subjects = subjectRepository.findByActiveTrue();
        model.addAttribute("users", users);
        model.addAttribute("subjects", subjects);
        model.addAttribute("permissionsByUser", userService.getPermissionsByUser());
        model.addAttribute("permissionLevels", SubjectPermissionLevel.values());
        model.addAttribute("currentUserEmail", securityContextFacade.getCurrentUserEmail().orElse(""));
        return "admin/users";
    }

    @PostMapping("/{id}/role")
    public String updateUserRole(@PathVariable UUID id, @RequestParam UserRole role,
                                 RedirectAttributes redirectAttributes) {
        return runGuarded(redirectAttributes, "admin.users.success.role_updated",
                () -> userService.updateRole(actorEmail(), id, role));
    }

    @PostMapping("/{id}/permissions")
    public String updateUserPermissions(@PathVariable UUID id,
                                        @RequestParam List<UUID> subjectIds,
                                        @RequestParam(defaultValue = "READ") SubjectPermissionLevel level,
                                        RedirectAttributes redirectAttributes) {
        String actor = actorEmail();
        return runGuarded(redirectAttributes, "admin.users.success.permission_granted", () -> {
            for (UUID subjectId : subjectIds) {
                userService.grantSubjectPermission(actor, id, subjectId, level);
            }
        });
    }

    @PostMapping("/{id}/permissions/{subjectId}/revoke")
    public String revokeUserPermission(@PathVariable UUID id, @PathVariable UUID subjectId,
                                       RedirectAttributes redirectAttributes) {
        return runGuarded(redirectAttributes, "admin.users.success.permission_revoked",
                () -> userService.revokeSubjectPermission(actorEmail(), id, subjectId));
    }

    @PostMapping("/{id}/toggle-active")
    public String toggleUserActive(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return runGuarded(redirectAttributes, "admin.users.success.status_updated",
                () -> userService.toggleActive(actorEmail(), id));
    }

    private String runGuarded(RedirectAttributes redirectAttributes, String successKey, Runnable action) {
        try {
            action.run();
            redirectAttributes.addFlashAttribute(FLASH_SUCCESS, successKey);
        } catch (AdminActionRejectedException e) {
            redirectAttributes.addFlashAttribute(FLASH_ERROR, e.getMessageKey());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute(FLASH_ERROR, "admin.users.error.not_found");
        }
        return "redirect:/admin/users";
    }

    private String actorEmail() {
        return securityContextFacade.getCurrentUserEmail().orElse("unknown");
    }
}
