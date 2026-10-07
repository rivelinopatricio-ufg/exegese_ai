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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying loading and overriding of custom configuration
 * properties from external app_config file.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
class AppConfigIntegrationTest {

    private static final Path APP_CONFIG_PATH = Path.of("app_config");

    @Autowired
    private Environment environment;

    @BeforeAll
    static void setUpAppConfg() throws IOException {
        String content = "exegese.custom.property=custom-value-loaded-from-app-confg\n"
                + "spring.datasource.hikari.maximum-pool-size=42\n";
        Files.writeString(APP_CONFIG_PATH, content, StandardCharsets.UTF_8);
    }

    @AfterAll
    static void tearDownAppConfg() throws IOException {
        Files.deleteIfExists(APP_CONFIG_PATH);
    }

    @Test
    @DisplayName("External app_config properties are loaded and accessible via Environment")
    void testAppConfgPropertiesLoaded() {
        String customVal = environment.getProperty("exegese.custom.property");
        assertThat(customVal).isEqualTo("custom-value-loaded-from-app-confg");
    }

    @Test
    @DisplayName("Custom properties in app_config override defaults from application.properties")
    void testAppConfgOverridesDefaults() {
        String poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size");
        assertThat(poolSize).isEqualTo("42");
    }
}
