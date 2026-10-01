package utils;

import org.apache.commons.lang3.StringUtils;

// There is no configured public URL, so links derive from the triggering request; without a host
// header there is no link and the caller falls back to the copy link in the admin UI.
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
