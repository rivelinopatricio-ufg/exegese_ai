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
import br.org.rivelino.exegese_ai.service.LlmEndpointPolicy;
import br.org.rivelino.exegese_ai.service.LlmEndpointRejectedException;
import br.org.rivelino.exegese_ai.service.LlmPingResult;
import br.org.rivelino.exegese_ai.service.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Controller managing multi-provider AI model configurations, default selection, and connectivity checks.
 * <p>
 * Base URLs outside the provider allowlist (or not using https) are refused with a localized flash error
 * and nothing is saved. Ping results are reported as localized status messages, never as raw upstream
 * responses.
 *
 * @author Rivelino Patrício
 */
@Controller
@RequestMapping("/admin/models")
@PreAuthorize("hasRole('ADMIN')")
public class AdminModelController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelController.class);

    private final LlmProviderRouter providerRouter;
    private final LlmEndpointPolicy endpointPolicy;
    private final MessageSource messageSource;

    public AdminModelController(LlmProviderRouter providerRouter,
                                LlmEndpointPolicy endpointPolicy,
                                MessageSource messageSource) {
        this.providerRouter = providerRouter;
        this.endpointPolicy = endpointPolicy;
        this.messageSource = messageSource;
    }

    @GetMapping
    public String listModels(Model model,
                             @RequestParam(required = false) String saved,
                             @RequestParam(required = false) String switched) {
        List<ModelConfigDTO> models = providerRouter.getAllConfigs();
        model.addAttribute("models", models);
        model.addAttribute("activeTab", "models");
        model.addAttribute("saved", saved != null);
        model.addAttribute("switched", switched != null);
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
                             Locale locale,
                             RedirectAttributes redirectAttributes) {
        try {
            providerRouter.updateConfig(provider, displayName, modelName, baseUrl, apiKey, temperature, maxTokens);
        } catch (LlmEndpointRejectedException e) {
            log.warn("Rejected base URL for provider {}: {}", provider, e.getReason());
            String allowedHosts = String.join(", ", endpointPolicy.allowedHosts(provider));
            redirectAttributes.addFlashAttribute("errorMessage", messageSource.getMessage(
                    e.getMessageKey(), new Object[]{provider.name(), allowedHosts}, locale));
            return "redirect:/admin/models";
        }
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
                               Locale locale,
                               RedirectAttributes redirectAttributes) {
        LlmPingResult result = providerRouter.pingModel(provider);
        String key = "admin.model.ping." + result.status().name().toLowerCase(Locale.ROOT);
        String message = messageSource.getMessage(key,
                new Object[]{provider.name(), String.valueOf(result.httpStatus())}, locale);
        redirectAttributes.addFlashAttribute("pingResult", message);
        redirectAttributes.addFlashAttribute("pingSuccess", result.status() == LlmPingResult.Status.OK);
        return "redirect:/admin/models";
    }
}
