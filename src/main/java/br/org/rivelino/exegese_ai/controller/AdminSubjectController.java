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
import br.org.rivelino.exegese_ai.service.SubjectCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Administrative controller for subject and taxonomy management.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/subjects")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminSubjectController {

    private final SubjectCatalogService catalogService;

    public AdminSubjectController(SubjectCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public String listSubjects(Model model) {
        List<ExegeseSubject> subjects = catalogService.findAll();
        model.addAttribute("subjects", subjects);
        return "admin/subjects";
    }

    @PostMapping
    public String createSubject(@RequestParam String code,
                                @RequestParam String name,
                                @RequestParam(required = false) String description) {
        catalogService.createSubject(code, name, description);
        return "redirect:/admin/subjects";
    }

    @PostMapping("/{id}/toggle")
    public String toggleActive(@PathVariable UUID id) {
        catalogService.toggleActive(id);
        return "redirect:/admin/subjects";
    }
}
