package utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

// Filled in by Maven resource filtering; read once at class load.
public final class AppVersion {
    private static final Logger LOG = LogManager.getLogger(AppVersion.class);
    private static final String RESOURCE = "/paprika-version.properties";
    private static final String UNKNOWN = "unknown";
    private static final String VERSION = read();

    private AppVersion() {
    }

    public static String get() {
        return VERSION;
    }

    private static String read() {
        try (InputStream inputStream = AppVersion.class.getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                LOG.warn("Missing {}, reporting the application version as '{}'", RESOURCE, UNKNOWN);
                return UNKNOWN;
            }

            var properties = new Properties();
            properties.load(inputStream);

            // An unfiltered copy still holds the literal ${project.version}
            String version = properties.getProperty("version");
            return StringUtils.isBlank(version) || version.startsWith("${") ? UNKNOWN : version;
        } catch (IOException e) {
            LOG.warn("Failed to read {}, reporting the application version as '{}'", RESOURCE, UNKNOWN, e);
            return UNKNOWN;
        }
    }
}
