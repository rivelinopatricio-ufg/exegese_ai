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
package br.org.rivelino.exegese_ai.service;

import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.repository.AiModelConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ApiKeyEncryptionMigrationRunner}: legacy API key ciphertexts stored by
 * earlier releases are re-encrypted with the configured master key, idempotently.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApiKeyEncryptionMigrationRunnerTest {

    @Autowired
    private ApiKeyEncryptionMigrationRunner migrationRunner;

    @Autowired
    private AiModelConfigRepository modelConfigRepository;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private LlmProviderRouter providerRouter;

    @Test
    @DisplayName("Legacy API keys are re-encrypted with the master key; v1 values and undecryptable values are left untouched")
    void testLegacyApiKeysMigrated() throws Exception {
        AiModelConfig openAi = modelConfigRepository.findByProvider(ModelProvider.OPENAI).orElseThrow();
        openAi.setApiKeyEncrypted(CryptoServiceTest.legacyEncrypt("sk-legacy-openai"));
        modelConfigRepository.save(openAi);

        String currentDeepSeek = cryptoService.encrypt("sk-current-deepseek");
        AiModelConfig deepSeek = modelConfigRepository.findByProvider(ModelProvider.DEEPSEEK).orElseThrow();
        deepSeek.setApiKeyEncrypted(currentDeepSeek);
        modelConfigRepository.save(deepSeek);

        AiModelConfig nemotron = modelConfigRepository.findByProvider(ModelProvider.NEMOTRON).orElseThrow();
        nemotron.setApiKeyEncrypted("bm90LWEtdmFsaWQtY2lwaGVydGV4dC1wYXlsb2Fk");
        modelConfigRepository.save(nemotron);

        int migrated = migrationRunner.migrateLegacyApiKeys();

        assertThat(migrated).isEqualTo(1);
        String migratedOpenAi = modelConfigRepository.findByProvider(ModelProvider.OPENAI).orElseThrow().getApiKeyEncrypted();
        assertThat(migratedOpenAi).startsWith(CryptoService.CIPHERTEXT_V1_PREFIX);
        assertThat(providerRouter.resolveApiKey(ModelProvider.OPENAI)).isEqualTo("sk-legacy-openai");
        assertThat(modelConfigRepository.findByProvider(ModelProvider.DEEPSEEK).orElseThrow().getApiKeyEncrypted())
                .isEqualTo(currentDeepSeek);
        assertThat(modelConfigRepository.findByProvider(ModelProvider.NEMOTRON).orElseThrow().getApiKeyEncrypted())
                .isEqualTo("bm90LWEtdmFsaWQtY2lwaGVydGV4dC1wYXlsb2Fk");

        // Second run is a no-op for everything already migrated
        assertThat(migrationRunner.migrateLegacyApiKeys()).isZero();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.OPENAI).orElseThrow().getApiKeyEncrypted())
                .isEqualTo(migratedOpenAi);
    }

    @Test
    @DisplayName("OLLAMA_LOCAL is seeded with the model pulled by ollama_init.sh")
    void testOllamaDefaultsAligned() {
        AiModelConfig ollama = modelConfigRepository.findByProvider(ModelProvider.OLLAMA_LOCAL).orElseThrow();
        assertThat(ollama.getModelName()).isEqualTo("llama3.2");
        assertThat(ollama.getBaseUrl()).isEqualTo("http://localhost:11434");
    }
}
