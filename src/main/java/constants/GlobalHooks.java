package constants;

public final class GlobalHooks {
    public static final String COLLECTION = "*";

    private GlobalHooks() {
    }

    public static boolean isGlobalCollection(String collection) {
        return COLLECTION.equals(collection);
    }
}
