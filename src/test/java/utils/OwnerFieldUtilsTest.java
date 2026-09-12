package utils;

import auth.AuthContext;
import constants.SystemCollections;
import enums.FieldType;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class OwnerFieldUtilsTest {

    @Test
    void appliesAuthenticatedUserAsOwnerOnCreate() {
        CollectionDefinition definition = collectionWithOwnerRelation();
        Document document = new Document("title", "hello");
        AuthContext auth = AuthContext.of("user-1", "user", "tenant-1");

        OwnerFieldUtils.applyOwnerOnCreate(document, definition, auth);

        assertThat(document.getString("owner"), equalTo("user-1"));
    }

    @Test
    void rejectsSpoofedOwnerOnCreate() {
        CollectionDefinition definition = collectionWithOwnerRelation();
        Document document = new Document("title", "hello").append("owner", "other-user");
        AuthContext auth = AuthContext.of("user-1", "user", "tenant-1");

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> OwnerFieldUtils.applyOwnerOnCreate(document, definition, auth));
    }

    @Test
    void defaultOwnerFieldPrefersUsersRelationField() {
        CollectionDefinition definition = collectionWithOwnerRelation();

        assertThat(OwnerFieldUtils.defaultOwnerField(definition), equalTo("owner"));
    }

    @Test
    void doesNotAssignOwnerWhenSchemaFieldMissing() {
        CollectionDefinition definition = new CollectionDefinition(
                "id",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)),
                List.of(),
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                false
        );

        Document document = new Document("title", "hello");
        OwnerFieldUtils.applyOwnerOnCreate(document, definition, AuthContext.of("user-1", "user", "tenant-1"));

        assertThat(document.get("owner"), nullValue());
    }

    /**
     * Which field an owner rule binds to is derived from the schema: the first relation pointing at
     * the users collection. Getting this wrong would silently scope records by the wrong field.
     */
    @Test
    void ownerFieldIsDerivedFromTheFirstUsersRelation() {
        CollectionDefinition definition = new CollectionDefinition(
                "id", "notes",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("category", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("categories")),
                        new FieldDefinition("createdBy", FieldType.RELATION, false, true,
                                FieldOptions.forRelation(SystemCollections.USERS)),
                        new FieldDefinition("reviewedBy", FieldType.RELATION, false, true,
                                FieldOptions.forRelation(SystemCollections.USERS))),
                List.of(), CollectionRules.locked(), false);

        assertThat(OwnerFieldUtils.ownerFieldCandidates(definition), equalTo(List.of("createdBy", "reviewedBy")));
        assertThat(OwnerFieldUtils.defaultOwnerField(definition), equalTo("createdBy"));
    }

    @Test
    void ownerFieldFallsBackToTheDefaultNameWithoutAUsersRelation() {
        CollectionDefinition definition = new CollectionDefinition(
                "id", "notes",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("category", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("categories"))),
                List.of(), CollectionRules.locked(), false);

        assertThat(OwnerFieldUtils.ownerFieldCandidates(definition), equalTo(List.of()));
        assertThat(OwnerFieldUtils.defaultOwnerField(definition), equalTo("owner"));
        assertThat(OwnerFieldUtils.defaultOwnerField(null), equalTo("owner"));
        assertThat(OwnerFieldUtils.ownerFieldCandidates(null), equalTo(List.of()));
    }

    @Test
    void aNonUsersRelationIsNeverAnOwnerField() {
        assertThat(OwnerFieldUtils.isUsersRelationField(null), equalTo(false));
        assertThat(OwnerFieldUtils.isUsersRelationField(
                new FieldDefinition("owner", FieldType.STRING, false, true, null)), equalTo(false));
        assertThat("a relation to another collection must not be treated as ownership",
                OwnerFieldUtils.isUsersRelationField(new FieldDefinition(
                        "category", FieldType.RELATION, false, true, FieldOptions.forRelation("categories"))),
                equalTo(false));
        assertThat(OwnerFieldUtils.isUsersRelationField(new FieldDefinition(
                        "owner", FieldType.RELATION, false, true,
                        FieldOptions.forRelation(SystemCollections.USERS))),
                equalTo(true));
    }

    /** An owner is only assigned when a rule actually depends on ownership. */
    @Test
    void ownerIsOnlyAssignedWhenARuleDependsOnIt() {
        AuthContext auth = AuthContext.of("user-1", "user", "tenant-1");

        assertThat(OwnerFieldUtils.shouldAssignOwnerOnCreate(collectionWithOwnerRelation(), auth), equalTo(true));
        assertThat("an unauthenticated caller has no id to assign",
                OwnerFieldUtils.shouldAssignOwnerOnCreate(collectionWithOwnerRelation(), AuthContext.guest()),
                equalTo(false));
        assertThat(OwnerFieldUtils.shouldAssignOwnerOnCreate(null, auth), equalTo(false));

        CollectionDefinition publicRules = new CollectionDefinition(
                "id", "notes",
                List.of(new FieldDefinition("owner", FieldType.RELATION, false, true,
                        FieldOptions.forRelation(SystemCollections.USERS))),
                List.of(),
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                false);
        assertThat("rules that never mention ownership must not silently stamp an owner",
                OwnerFieldUtils.shouldAssignOwnerOnCreate(publicRules, auth), equalTo(false));

        CollectionDefinition authCreate = new CollectionDefinition(
                "id", "notes",
                List.of(new FieldDefinition("owner", FieldType.RELATION, false, true,
                        FieldOptions.forRelation(SystemCollections.USERS))),
                List.of(),
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                false);
        assertThat("an auth create rule still records who created the record",
                OwnerFieldUtils.shouldAssignOwnerOnCreate(authCreate, auth), equalTo(true));
    }

    @Test
    void usesOwnerRuleDetectsTheRuleOnEveryOperation() {
        assertThat(OwnerFieldUtils.usesOwnerRule(null), equalTo(false));
        assertThat(OwnerFieldUtils.usesOwnerRule(CollectionRules.locked()), equalTo(false));
        assertThat(OwnerFieldUtils.usesOwnerRule(
                new CollectionRules("owner", null, null, null, null, "owner")), equalTo(true));
        assertThat(OwnerFieldUtils.usesOwnerRule(
                new CollectionRules(null, "owner", null, null, null, "owner")), equalTo(true));
        assertThat(OwnerFieldUtils.usesOwnerRule(
                new CollectionRules(null, null, "owner", null, null, "owner")), equalTo(true));
        assertThat(OwnerFieldUtils.usesOwnerRule(
                new CollectionRules(null, null, null, "owner", null, "owner")), equalTo(true));
        assertThat(OwnerFieldUtils.usesOwnerRule(
                new CollectionRules(null, null, null, null, "owner", "owner")), equalTo(true));
        assertThat("the rule name is matched case insensitively and trimmed",
                OwnerFieldUtils.usesOwnerRule(new CollectionRules(" OWNER ", null, null, null, null, "owner")),
                equalTo(true));
    }

    @Test
    void effectiveCreateBodyOnlyAppliesToOwnerRules() {
        AuthContext auth = AuthContext.of("user-1", "user", "tenant-1");

        assertThat("a non owner rule leaves the body untouched",
                OwnerFieldUtils.effectiveCreateBody("auth", "owner", auth, java.util.Map.of("title", "x")),
                equalTo(java.util.Map.of("title", "x")));
        assertThat("a null body never becomes null again",
                OwnerFieldUtils.effectiveCreateBody("auth", "owner", auth, null),
                equalTo(java.util.Map.of()));
        assertThat(OwnerFieldUtils.effectiveCreateBody("owner", "owner", AuthContext.guest(), null),
                equalTo(java.util.Map.of()));

        assertThat("a blank owner value is filled in rather than rejected",
                OwnerFieldUtils.effectiveCreateBody("owner", "owner", auth,
                        new java.util.HashMap<>(java.util.Map.of("owner", "  "))).get("owner"),
                equalTo("user-1"));
    }

    @Test
    void effectiveCreateRecordMirrorsTheBody() {
        assertThat(OwnerFieldUtils.effectiveCreateRecord(null), nullValue());
        assertThat(OwnerFieldUtils.effectiveCreateRecord(java.util.Map.of()), nullValue());
        assertThat(OwnerFieldUtils.effectiveCreateRecord(java.util.Map.of("owner", "user-1")).getString("owner"),
                equalTo("user-1"));
    }

    private CollectionDefinition collectionWithOwnerRelation() {
        return new CollectionDefinition(
                "id",
                "notes",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition(
                                "owner",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation(SystemCollections.USERS))
                ),
                List.of(),
                new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"),
                false
        );
    }
}
