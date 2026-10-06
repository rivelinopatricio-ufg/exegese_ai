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
package br.org.rivelino.exegese_ai.web;

import br.org.rivelino.exegese_ai.domain.dto.ModelConfigDTO;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.service.LlmProviderRouter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * Controller managing multi-provider AI model configurations, default selection, and connectivity checks.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/models")
@PreAuthorize("hasRole('ADMIN')")
public class AdminModelController {

    private final LlmProviderRouter providerRouter;

    public AdminModelController(LlmProviderRouter providerRouter) {
        this.providerRouter = providerRouter;
    }

    @GetMapping
    public String listModels(Model model,
                             @RequestParam(required = false) String saved,
                             @RequestParam(required = false) String switched,
                             @RequestParam(required = false) String pingResult) {
        List<ModelConfigDTO> models = providerRouter.getAllConfigs();
        model.addAttribute("models", models);
        model.addAttribute("activeTab", "models");
        model.addAttribute("saved", saved != null);
        model.addAttribute("switched", switched != null);
        model.addAttribute("pingResult", pingResult);
        return "admin/models";
    }

    @PostMapping("/{provider}/save")
    public String saveConfig(@PathVariable ModelProvider provider,
                             @RequestParam String displayName,
                             @RequestParam String modelName,
                             @RequestParam(required = false) String baseUrl,
                             @RequestParam(required = false) String apiKey,
                             @RequestParam(defaultValue = "0.10") BigDecimal temperature,
                             @RequestParam(defaultValue = "1024") Integer maxTokens,
                             RedirectAttributes redirectAttributes) {
        providerRouter.updateConfig(provider, displayName, modelName, baseUrl, apiKey, temperature, maxTokens);
        redirectAttributes.addAttribute("saved", "true");
        return "redirect:/admin/models";
    }

    @PostMapping("/{provider}/set-default")
    public String setDefaultProvider(@PathVariable ModelProvider provider,
                                     RedirectAttributes redirectAttributes) {
        providerRouter.setDefaultProvider(provider);
        redirectAttributes.addAttribute("switched", "true");
        return "redirect:/admin/models";
    }

    @PostMapping("/{provider}/ping")
    public String pingProvider(@PathVariable ModelProvider provider,
                               RedirectAttributes redirectAttributes) {
        String result = providerRouter.pingModel(provider);
        redirectAttributes.addAttribute("pingResult", result);
        return "redirect:/admin/models";
    }
}
