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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.MessageSource;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminModelController} managing AI provider configuration and connectivity endpoints.
 *
 * @author Rivelino Patrício
 */
class AdminModelControllerTest {

    @Mock
    private LlmProviderRouter providerRouter;

    @Mock
    private LlmEndpointPolicy endpointPolicy;

    @Mock
    private MessageSource messageSource;

    private AdminModelController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AdminModelController(providerRouter, endpointPolicy, messageSource);
    }

    @Test
    @DisplayName("listModels sets models list and UI flags on the model")
    void testListModels() {
        ModelConfigDTO dto = new ModelConfigDTO(ModelProvider.GEMINI, "Gemini", "gemini-flash",
                "https://api.example.com", null, true, true, true, BigDecimal.valueOf(0.1), 1024);
        when(providerRouter.getAllConfigs()).thenReturn(List.of(dto));

        Model model = new ConcurrentModel();
        String view = controller.listModels(model, "true", null);

        assertThat(view).isEqualTo("admin/models");
        assertThat(model.getAttribute("models")).isEqualTo(List.of(dto));
        assertThat(model.getAttribute("saved")).isEqualTo(true);
        assertThat(model.getAttribute("switched")).isEqualTo(false);
    }

    @Test
    @DisplayName("saveConfig updates provider configuration and redirects")
    void testSaveConfigSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.saveConfig(
                ModelProvider.GEMINI,
                "Google Gemini",
                "gemini-2.5-flash",
                "https://generativelanguage.googleapis.com",
                "key-123",
                BigDecimal.valueOf(0.2),
                2048,
                Locale.forLanguageTag("pt-BR"),
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/models");
        assertThat(redirectAttributes.asMap()).containsEntry("saved", "true");
        verify(providerRouter).updateConfig(
                ModelProvider.GEMINI, "Google Gemini", "gemini-2.5-flash",
                "https://generativelanguage.googleapis.com", "key-123", BigDecimal.valueOf(0.2), 2048);
    }

    @Test
    @DisplayName("saveConfig handles LlmEndpointRejectedException and populates flash errorMessage")
    void testSaveConfigEndpointRejected() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        doThrow(new LlmEndpointRejectedException(ModelProvider.GEMINI, "Disallowed host"))
                .when(providerRouter).updateConfig(any(), any(), any(), any(), any(), any(), any());
        when(endpointPolicy.allowedHosts(ModelProvider.GEMINI)).thenReturn(Set.of("generativelanguage.googleapis.com"));
        when(messageSource.getMessage(any(), any(), any())).thenReturn("Host não permitido.");

        String view = controller.saveConfig(
                ModelProvider.GEMINI,
                "Google Gemini",
                "gemini-2.5-flash",
                "http://attacker.com",
                "key-123",
                BigDecimal.valueOf(0.2),
                2048,
                Locale.forLanguageTag("pt-BR"),
                redirectAttributes
        );

        assertThat(view).isEqualTo("redirect:/admin/models");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isEqualTo("Host não permitido.");
    }

    @Test
    @DisplayName("setDefaultProvider switches active default and redirects with switched flag")
    void testSetDefaultProvider() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.setDefaultProvider(ModelProvider.CLAUDE, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/models");
        assertThat(redirectAttributes.asMap()).containsEntry("switched", "true");
        verify(providerRouter).setDefaultProvider(ModelProvider.CLAUDE);
    }

    @Test
    @DisplayName("pingProvider tests connectivity and populates localized flash messages")
    void testPingProvider() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(providerRouter.pingModel(ModelProvider.GEMINI)).thenReturn(LlmPingResult.of(LlmPingResult.Status.OK));
        when(messageSource.getMessage(eq("admin.model.ping.ok"), any(), any())).thenReturn("Conexão bem-sucedida.");

        String view = controller.pingProvider(ModelProvider.GEMINI, Locale.forLanguageTag("pt-BR"), redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/models");
        assertThat(redirectAttributes.getFlashAttributes().get("pingResult")).isEqualTo("Conexão bem-sucedida.");
        assertThat(redirectAttributes.getFlashAttributes().get("pingSuccess")).isEqualTo(true);
    }
}
