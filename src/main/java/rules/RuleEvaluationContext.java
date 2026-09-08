package rules;

import auth.AuthContext;
import org.bson.Document;

import java.util.Map;

public record RuleEvaluationContext(
        AuthContext auth,
        Document record,
        Map<String, Object> body
) {
    public static RuleEvaluationContext of(AuthContext auth) {
        return new RuleEvaluationContext(auth, null, null);
    }

    public static RuleEvaluationContext of(AuthContext auth, Document record) {
        return new RuleEvaluationContext(auth, record, null);
    }

    public static RuleEvaluationContext of(AuthContext auth, Map<String, Object> body) {
        return new RuleEvaluationContext(auth, null, body);
    }

    public static RuleEvaluationContext of(AuthContext auth, Document record, Map<String, Object> body) {
        return new RuleEvaluationContext(auth, record, body);
    }
}
