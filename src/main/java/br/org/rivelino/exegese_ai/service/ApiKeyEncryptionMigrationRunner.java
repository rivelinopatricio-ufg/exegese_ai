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
import br.org.rivelino.exegese_ai.repository.AiModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * One-shot startup migration that re-encrypts provider API keys still stored with the legacy
 * (unversioned, hard-coded key) AES scheme using the configured master key ({@code EXEGESE_AES_SECRET}).
 * <p>
 * The migration is idempotent: values already in the {@code v1:} format are skipped, so it runs on
 * every startup at negligible cost. Only provider names are logged, never key material.
 *
 * @author Rivelino Patrício
 */
@Component
public class ApiKeyEncryptionMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyEncryptionMigrationRunner.class);

    private final AiModelConfigRepository modelConfigRepository;
    private final CryptoService cryptoService;

    public ApiKeyEncryptionMigrationRunner(AiModelConfigRepository modelConfigRepository, CryptoService cryptoService) {
        this.modelConfigRepository = modelConfigRepository;
        this.cryptoService = cryptoService;
    }

    @Override
    @Transactional
    public void run(@SuppressWarnings("unused") ApplicationArguments args) {
        migrateLegacyApiKeys();
    }

    /**
     * Re-encrypts every legacy {@link AiModelConfig#getApiKeyEncrypted()} value with the current master key.
     *
     * @return number of provider configurations migrated
     */
    @Transactional
    public int migrateLegacyApiKeys() {
        int migrated = 0;
        for (AiModelConfig config : modelConfigRepository.findAll()) {
            String stored = config.getApiKeyEncrypted();
            if (!cryptoService.isLegacyCiphertext(stored)) {
                continue;
            }
            try {
                config.setApiKeyEncrypted(cryptoService.reencryptLegacy(stored));
                config.setUpdatedAt(Instant.now());
                modelConfigRepository.save(config);
                migrated++;
                log.info("Re-encrypted stored API key of provider {} with the configured AES master key", config.getProvider());
            } catch (IllegalStateException e) {
                log.warn("Stored API key of provider {} could not be decrypted with the legacy key and was left untouched; "
                        + "re-enter it in the admin panel", config.getProvider());
            }
        }
        if (migrated > 0) {
            log.info("Legacy API key encryption migration finished: {} provider(s) migrated", migrated);
        }
        return migrated;
    }
}
