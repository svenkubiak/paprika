package enums;

public enum FieldType {
    STRING("String"),
    NUMBER("Number"),
    BOOLEAN("Boolean"),
    EMAIL("Email"),
    URL("URL"),
    DATE("Date"),
    TIME("Time"),
    DATETIME("Date & time"),
    RELATION("Relation"),
    FILE("File"),
    JSON("Json"),
    SELECT("Select");

    private final String label;

    FieldType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
