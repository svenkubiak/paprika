package utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The Maven version of this build, filled into paprika-version.properties by resource filtering.
 * Read once at class load: the value can not change while the process runs, and it is served on
 * every admin bootstrap request.
 */
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

            // An unfiltered copy would still hold the literal ${project.version}, which is worse
            // than admitting the version is unknown.
            String version = properties.getProperty("version");
            return StringUtils.isBlank(version) || version.startsWith("${") ? UNKNOWN : version;
        } catch (IOException e) {
            LOG.warn("Failed to read {}, reporting the application version as '{}'", RESOURCE, UNKNOWN, e);
            return UNKNOWN;
        }
    }
}
