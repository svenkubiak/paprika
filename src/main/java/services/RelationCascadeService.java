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
 * Targets are client-chosen and not covered by the request's AuthorizationDecision, so each is
 * authorized against its own collection's delete rule; forbidden targets are skipped. One level
 * deep by design, since relations may form cycles.
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

        // Unprojected, so the rule sees the same record as for a direct DELETE
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