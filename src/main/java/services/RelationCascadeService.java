package services;

import auth.AuthContext;
import auth.TenantContext;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import rules.RuleOperation;
import rules.RuleService;
import utils.RecordProjections;
import utils.RelationFieldUtils;

import java.util.Objects;

import static com.mongodb.client.model.Filters.eq;

/**
 * Deletes the records a {@code RELATION} field with {@code cascadeDelete} points at, once the
 * record holding that relation has been deleted.
 * <p>
 * The target of this delete is chosen by the client: it is an ordinary field of the request body,
 * validated for existence only. That makes the cascade a delete on a record the caller never named
 * in a route and whose collection was never seen by {@code ApiAuthFilter} - the
 * {@link auth.AuthorizationDecision} of the request covers the <em>holding</em> collection and
 * nothing else. So every target is authorized here, against the delete rule of its own collection
 * and with the caller's own identity, exactly as a direct {@code DELETE} on that record would be.
 * A target the caller may not delete is skipped, not deleted: the holding record is already gone by
 * the time we get here, and refusing the whole operation would neither bring it back nor be
 * expressible in the response.
 * <p>
 * The cascade is one level deep by design. A target that itself holds cascading relations does not
 * cascade further - relations may form cycles, and a delete that walks them would need a visited
 * set and a depth limit to stay bounded. Dependent deletes beyond one hop belong in a hook.
 */
@Singleton
public class RelationCascadeService {
    private static final Logger LOG = LogManager.getLogger(RelationCascadeService.class);

    private final TenantCollectionService tenantCollections;
    private final RuleService ruleService;
    private final FileFieldService fileFieldService;

    @Inject
    public RelationCascadeService(
            TenantCollectionService tenantCollections,
            RuleService ruleService,
            FileFieldService fileFieldService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.fileFieldService = Objects.requireNonNull(fileFieldService, "fileFieldService must not be null");
    }

    /**
     * @param definition   the collection of the record that was just deleted
     * @param record       the deleted record, carrying the relation ids to follow
     * @param auth         the caller, used to evaluate the delete rule of every target collection
     * @param adminBypass  whether the request skipped the rules already (admin UI session or a
     *                     rule-bypassing API key), in which case the targets follow that decision
     */
    public void cascadeDelete(
            TenantContext ctx,
            CollectionDefinition definition,
            Document record,
            AuthContext auth,
            boolean adminBypass) {

        if (definition == null || record == null) {
            return;
        }

        for (FieldDefinition field : RelationFieldUtils.relationFields(definition)) {
            if (!field.optionsOrDefault().cascadeDeleteOrDefault()) {
                continue;
            }

            String target = field.optionsOrDefault().collection();
            if (StringUtils.isBlank(target)) {
                continue;
            }

            String targetCollection = target.trim();
            CollectionDefinition targetDefinition = tenantCollections.findDefinition(ctx, targetCollection);
            if (targetDefinition == null) {
                continue;
            }

            for (String relatedId : RelationFieldUtils.uniqueRelationIds(record.get(field.name()), field)) {
                deleteIfPermitted(ctx, targetDefinition, targetCollection, relatedId, auth, adminBypass);
            }
        }
    }

    private void deleteIfPermitted(
            TenantContext ctx,
            CollectionDefinition targetDefinition,
            String targetCollection,
            String relatedId,
            AuthContext auth,
            boolean adminBypass) {

        // Read unprojected, so the rule sees the same record ApiAuthFilter#checkRecordRule would
        // have seen for a direct DELETE - a projection here could hide the very field a rule is
        // written against.
        Document target = tenantCollections.dataCollection(ctx, targetCollection)
                .find(eq("id", relatedId))
                .first();

        if (target == null) {
            return;
        }

        if (!adminBypass && !mayDelete(ctx, targetDefinition, targetCollection, target, auth)) {
            LOG.warn("Skipped the cascading delete of {}/{}: the caller may not delete that record",
                    targetCollection, relatedId);
            return;
        }

        Document deleted = tenantCollections.dataCollection(ctx, targetCollection).findOneAndDelete(
                eq("id", relatedId),
                new FindOneAndDeleteOptions().projection(RecordProjections.forCollection(targetCollection)));

        if (deleted != null) {
            fileFieldService.deleteRecordFiles(ctx, targetDefinition, deleted);
        }
    }

    private boolean mayDelete(
            TenantContext ctx,
            CollectionDefinition targetDefinition,
            String targetCollection,
            Document target,
            AuthContext auth) {

        if (auth == null) {
            return false;
        }

        CollectionRules rules = targetDefinition.rulesOrDefault();
        String rule = ruleService.ruleFor(rules, RuleOperation.DELETE);
        return ruleService.canAccess(rule, rules, auth, target, null, targetCollection, ctx);
    }
}