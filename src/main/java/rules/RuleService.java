package rules;

import auth.AuthContext;
import com.mongodb.client.model.Filters;
import jakarta.inject.Singleton;
import models.CollectionRules;
import org.bson.Document;
import org.bson.conversions.Bson;
import rules.ast.RuleNode;
import utils.OwnerFieldUtils;

import java.util.Map;

@Singleton
public final class RuleService {
    public RuleMode resolveMode(String rule) {
        if (rule == null || rule.isBlank()) {
            return RuleMode.LOCKED;
        }
        if ("*".equals(rule.trim())) {
            return RuleMode.PUBLIC;
        }
        return RuleMode.EXPRESSION;
    }

    public String normalizeRule(String rule, String ownerField) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        String trimmed = rule.trim();
        if ("*".equals(trimmed)) {
            return "*";
        }
        if ("auth".equalsIgnoreCase(trimmed)) {
            return "auth.id != null";
        }
        if ("owner".equalsIgnoreCase(trimmed)) {
            return "record." + ownerField + " = auth.id";
        }
        return trimmed;
    }

    public void validateRule(String rule, String ownerField) {
        if (rule == null || rule.isBlank()) {
            return;
        }

        String trimmed = rule.trim();
        if ("*".equals(trimmed)) {
            return;
        }

        if ("auth".equalsIgnoreCase(trimmed) || "owner".equalsIgnoreCase(trimmed)) {
            RuleParser.parse(normalizeRule(trimmed, ownerField));
            return;
        }

        throw new RuleParseException(
                "Unsupported rule: " + rule + ". Allowed values: (empty), *, auth, owner."
        );
    }

    public void validateRules(CollectionRules rules) {
        if (rules == null) {
            return;
        }
        String ownerField = rules.ownerFieldOrDefault();
        validateRule(rules.listRule(), ownerField);
        validateRule(rules.viewRule(), ownerField);
        validateRule(rules.createRule(), ownerField);
        validateRule(rules.updateRule(), ownerField);
        validateRule(rules.deleteRule(), ownerField);
    }

    public String ruleFor(CollectionRules rules, RuleOperation operation) {
        if (rules == null) {
            return null;
        }
        return switch (operation) {
            case LIST -> rules.listRule();
            case VIEW -> rules.viewRule();
            case CREATE -> rules.createRule();
            case UPDATE -> rules.updateRule();
            case DELETE -> rules.deleteRule();
        };
    }

    public boolean canAccess(
            String rule,
            String ownerField,
            AuthContext auth,
            Document record,
            Map<String, Object> body) {

        RuleMode mode = resolveMode(rule);
        if (mode == RuleMode.LOCKED) {
            return false;
        }
        if (mode == RuleMode.PUBLIC) {
            return true;
        }

        Map<String, Object> effectiveBody = OwnerFieldUtils.effectiveCreateBody(rule, ownerField, auth, body);
        if (effectiveBody.isEmpty() && isOwnerRule(rule) && auth.isAuthenticated()) {
            return false;
        }

        Document effectiveRecord = record != null
                ? record
                : OwnerFieldUtils.effectiveCreateRecord(effectiveBody);

        String normalized = normalizeRule(rule, ownerField);
        RuleNode node = RuleParser.parse(normalized);
        return RuleEvaluator.evaluate(node, RuleEvaluationContext.of(auth, effectiveRecord, effectiveBody));
    }

    private boolean isOwnerRule(String rule) {
        return rule != null && "owner".equalsIgnoreCase(rule.trim());
    }

    public Bson listFilter(String rule, String ownerField, AuthContext auth) {
        RuleMode mode = resolveMode(rule);
        if (mode == RuleMode.LOCKED) {
            return null;
        }
        if (mode == RuleMode.PUBLIC) {
            return Filters.empty();
        }

        String normalized = normalizeRule(rule, ownerField);
        RuleNode node = RuleParser.parse(normalized);
        return RuleToMongoConverter.toFilter(node, auth);
    }
}
