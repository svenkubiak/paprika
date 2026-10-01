package app;

import io.mangoo.utils.internal.MangooUtils;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import services.FileStorageService;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * mangoo merges environment maps only when both sides are maps, so an environment that overrides a
 * nested block with a scalar silently drops every key below it. Asserted here because it cannot be seen.
 */
class ConfigYamlTest {

    @Test
    void theStorageRootIsConfigurableInProduction() {
        Map<String, String> prod = flatten("prod");

        assertThat("the key FileStorageService reads has to survive the prod merge",
                prod, hasKey(FileStorageService.STORAGE_KEY));
        assertThat("an env{} placeholder means the value comes from the environment",
                prod.get(FileStorageService.STORAGE_KEY), equalTo("env{}"));
    }

    /** Renaming the key without the installers, compose file and docs must fail here, not in a deployment. */
    @Test
    void theStorageKeyMapsToTheDocumentedEnvironmentVariable() {
        assertThat(envVariableFor(FileStorageService.STORAGE_KEY), equalTo("PAPRIKA_STORAGE"));
    }

    /** mangoo only replaces "." with "_", so a hyphenated key derives a variable no shell can set. */
    @Test
    void noProductionOverrideDerivesAnUnsettableEnvironmentVariable() {
        flatten("prod").forEach((key, value) -> {
            if ("env{}".equals(value)) {
                assertThat("env{} on '" + key + "' derives " + envVariableFor(key)
                                + ", which cannot be set in a POSIX shell",
                        envVariableFor(key).matches("[A-Z_][A-Z0-9_]*"), is(true));
            }
        });
    }

    /**
     * mangoo drops an env{} whose variable is unset, so a Paprika key without a default silently
     * vanishes. Framework keys are excluded; mangoo brings its own defaults.
     */
    @Test
    void everyPaprikaPlaceholderHasADefaultToFallBackOn() {
        Map<String, String> defaults = flatten(null);

        flatten("prod").forEach((key, value) -> {
            if ("env{}".equals(value) && key.startsWith("paprika.")) {
                assertThat("prod marks '" + key + "' as env{} but the defaults do not define it, "
                                + "so an unset variable leaves the key missing entirely",
                        defaults, hasKey(key));
            }
        });
    }

    @Test
    void theSmtpSenderNameMapsToTheDocumentedEnvironmentVariable() {
        assertThat(envVariableFor(services.MailService.SMTP_FROM_NAME_KEY), equalTo("SMTP_FROM_NAME"));
        assertThat(flatten("prod"), hasKey(services.MailService.SMTP_FROM_NAME_KEY));
        assertThat(flatten(null), hasKey(services.MailService.SMTP_FROM_NAME_KEY));
    }

    @Test
    void theTestEnvironmentUsesItsOwnStorageDirectory() {
        Map<String, String> test = flatten("test");

        assertThat(test, hasKey(FileStorageService.STORAGE_KEY));
        assertThat(test.get(FileStorageService.STORAGE_KEY), not(equalTo(flatten(null).get(FileStorageService.STORAGE_KEY))));
    }

    private static String envVariableFor(String key) {
        return key.toUpperCase(java.util.Locale.ENGLISH).replace(".", "_");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> flatten(String environment) {
        Map<String, Object> config;
        try (InputStream inputStream = ConfigYamlTest.class.getResourceAsStream("/config.yaml")) {
            config = new Yaml(new LoaderOptions()).load(inputStream);
        } catch (Exception e) {
            throw new IllegalStateException("config.yaml could not be read", e);
        }

        Map<String, Object> merged = new HashMap<>((Map<String, Object>) config.get("default"));
        if (environment != null) {
            Map<String, Object> environments = (Map<String, Object>) config.get("environments");
            Map<String, Object> active = (Map<String, Object>) environments.get(environment);
            assertThat("config.yaml has no environment '" + environment + "'", active, not(equalTo(null)));
            MangooUtils.mergeMaps(merged, active);
        }

        return MangooUtils.flattenMap(merged);
    }
}
