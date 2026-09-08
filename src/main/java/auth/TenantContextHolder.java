package auth;

import io.mangoo.routing.bindings.Request;

public final class TenantContextHolder {
    private TenantContextHolder() {
    }

    public static TenantContext require(Request request) {
        Object attribute = request.getAttribute(TenantContext.REQUEST_ATTRIBUTE);
        if (attribute instanceof TenantContext ctx && ctx.hasTenantContext()) {
            return ctx;
        }
        throw new exceptions.NoTenantContextException();
    }

    public static TenantContext get(Request request) {
        Object attribute = request.getAttribute(TenantContext.REQUEST_ATTRIBUTE);
        if (attribute instanceof TenantContext ctx) {
            return ctx;
        }
        return null;
    }

    public static AuthContext auth(Request request) {
        Object attribute = request.getAttribute(AuthContext.REQUEST_ATTRIBUTE);
        if (attribute instanceof AuthContext auth) {
            return auth;
        }
        return AuthContext.guest();
    }
}
