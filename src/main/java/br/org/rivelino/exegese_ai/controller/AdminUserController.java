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
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Controller for administrative user management and RBAC governance.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final ExegeseSubjectRepository subjectRepository;

    public AdminUserController(UserService userService, ExegeseSubjectRepository subjectRepository) {
        this.userService = userService;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping
    public String listUsers(Model model) {
        List<ExegeseUser> users = userService.findAll();
        List<ExegeseSubject> subjects = subjectRepository.findByActiveTrue();
        model.addAttribute("users", users);
        model.addAttribute("subjects", subjects);
        return "admin/users";
    }

    @PostMapping("/{id}/role")
    public String updateUserRole(@PathVariable UUID id, @RequestParam UserRole role) {
        userService.updateRole(id, role);
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/permissions")
    public String updateUserPermissions(@PathVariable UUID id,
                                        @RequestParam List<UUID> subjectIds,
                                        @RequestParam(defaultValue = "READ") String level) {
        for (UUID subjectId : subjectIds) {
            userService.grantSubjectPermission(id, subjectId, level);
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/toggle-active")
    public String toggleUserActive(@PathVariable UUID id) {
        userService.toggleActive(id);
        return "redirect:/admin/users";
    }
}
