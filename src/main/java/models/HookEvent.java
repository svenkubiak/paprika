package models;

public enum HookEvent {
    beforeRequest(true),
    beforeList(true),
    beforeView(true),
    beforeCreate(true),
    afterCreate(false),
    beforeUpdate(true),
    afterUpdate(false),
    beforeDelete(true),
    afterDelete(false),
    beforeRegister(true),
    afterRegister(false),
    beforeLogin(true),
    afterLogin(false),
    beforeRefresh(true),
    afterRefresh(false);

    private final boolean blocking;

    HookEvent(boolean blocking) {
        this.blocking = blocking;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public boolean isAuthEvent() {
        return switch (this) {
            case beforeRegister, afterRegister, beforeLogin, afterLogin, beforeRefresh, afterRefresh -> true;
            default -> false;
        };
    }
}
