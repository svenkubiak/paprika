package utils;

import org.apache.commons.lang3.StringUtils;

/**
 * Builds the absolute links Paprika mails out (superadmin invite, email confirmation) from the
 * request they were triggered on - the origin the admin who triggered it is working on. Paprika has
 * no configured public URL, so this is the only source of truth available, and a request without a
 * host header simply produces no link: the caller then falls back to the copy link in the admin UI
 * rather than mailing a wrong address.
 */
public final class InstanceLinks {
    private InstanceLinks() {
    }

    public static String absolute(String host, String forwardedProto, String path) {
        if (StringUtils.isBlank(host)) {
            return null;
        }

        String scheme = StringUtils.isNotBlank(forwardedProto) ? forwardedProto : "https";

        return scheme + "://" + host + path;
    }
}
