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

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test validating application internationalization (i18n),
 * cookie persistence and dynamic locale switching across pt-BR, en and es.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class I18nWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Default locale is Portuguese (pt-BR) when accessing login without language parameter")
    void testDefaultLocaleIsPortuguese() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entrar com Google")))
                .andExpect(content().string(containsString("Acesso Institucional")))
                .andExpect(content().string(containsString("Plataforma RAG de Rigor Exegético")));
    }

    @Test
    @DisplayName("Switching to English (?lang=en) updates content and issues EXEGESE_LOCALE cookie")
    void testSwitchToEnglishLocale() throws Exception {
        mockMvc.perform(get("/login").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("EXEGESE_LOCALE"))
                .andExpect(content().string(containsString("Sign in with Google")))
                .andExpect(content().string(containsString("Institutional Access")))
                .andExpect(content().string(containsString("RAG Platform with Exegetical Rigor")));
    }

    @Test
    @DisplayName("Switching to Spanish (?lang=es) updates content and issues EXEGESE_LOCALE cookie")
    void testSwitchToSpanishLocale() throws Exception {
        mockMvc.perform(get("/login").param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("EXEGESE_LOCALE"))
                .andExpect(content().string(containsString("Iniciar sesión con Google")))
                .andExpect(content().string(containsString("Acceso Institucional")))
                .andExpect(content().string(containsString("Plataforma RAG de Rigor Exegético y Fundamentación Normativa")));
    }

    @Test
    @DisplayName("Request with EXEGESE_LOCALE cookie retains user's preferred language")
    void testLocalePersistedViaCookie() throws Exception {
        Cookie localeCookie = new Cookie("EXEGESE_LOCALE", "en");

        mockMvc.perform(get("/login").cookie(localeCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in with Google")))
                .andExpect(content().string(containsString("Institutional Access")));
    }
}
