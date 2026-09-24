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
 * Reproduces what mangoo does with {@code config.yaml} at startup: the active environment is
 * merged onto the defaults and the result is flattened into dotted keys.
 * <p>
 * The merge only recurses when <em>both</em> sides are maps ({@code MangooUtils#mergeMaps}). An
 * environment that overrides a nested block with a scalar therefore replaces the whole subtree,
 * and the keys below it stop existing - silently, because every reader falls back to its
 * hardcoded default. That is a class of bug a config file cannot show you by looking at it,
 * which is why it is asserted here.
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

    /**
     * The environment variable name mangoo derives from the key. Spelled out so that renaming
     * the key without renaming it in the installers, the compose file and the docs fails here
     * rather than in somebody's deployment.
     */
    @Test
    void theStorageKeyMapsToTheDocumentedEnvironmentVariable() {
        assertThat(envVariableFor(FileStorageService.STORAGE_KEY), equalTo("PAPRIKA_STORAGE"));
    }

    /**
     * mangoo replaces "." with "_" but leaves every other character alone, so a key with a
     * hyphen derives a variable name no shell can set. Such a key must not be an env{} override.
     */
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
     * For the application's own keys the defaults have to carry a value: mangoo drops an env{}
     * whose variable is unset, so a key without a default simply is not there and every reader
     * falls back to whatever it hardcoded. Framework keys are excluded - mangoo brings its own
     * defaults for those.
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

    /** The same trap as the storage key, on the one other hand-written key with a compound name. */
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
