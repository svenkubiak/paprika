package exceptions;

public class NoTenantContextException extends RuntimeException {
    public NoTenantContextException() {
        super("No tenant context available");
    }
}
