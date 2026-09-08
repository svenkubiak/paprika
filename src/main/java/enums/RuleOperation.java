package rules;

public enum RuleOperation {
    LIST("listRule"),
    VIEW("viewRule"),
    CREATE("createRule"),
    UPDATE("updateRule"),
    DELETE("deleteRule");

    private final String fieldName;

    RuleOperation(String fieldName) {
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}
