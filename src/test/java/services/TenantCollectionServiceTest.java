package services;

import auth.TenantContext;
import enums.FieldType;
import enums.IndexDirection;
import io.mangoo.core.Application;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.IndexDefinition;
import models.IndexField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rules.RuleParseException;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(io.mangoo.test.TestRunner.class)
class TenantCollectionServiceTest {

    @Test
    void validateDefinitionAcceptsValidSchema() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null, null)),
                List.of(new IndexDefinition(
                        "title_idx",
                        List.of(new IndexField("title", IndexDirection.ASC)),
                        false)),
                CollectionRules.locked(),
                false);

        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    @Test
    void validateDefinitionRejectsDuplicateFieldName() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "posts",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null, null),
                        new FieldDefinition("title", FieldType.STRING, false, false, null, null)),
                List.of(),
                CollectionRules.locked(),
                false);

        assertThrows(IllegalArgumentException.class, () -> service.validateDefinition(definition));
    }

    @Test
    void validateDefinitionRejectsReservedFieldName() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "posts",
                List.of(new FieldDefinition("id", FieldType.STRING, true, false, null, null)),
                List.of(),
                CollectionRules.locked(),
                false);

        assertThrows(IllegalArgumentException.class, () -> service.validateDefinition(definition));
    }

    @Test
    void validateDefinitionRejectsDuplicateIndexName() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null, null)),
                List.of(
                        new IndexDefinition(
                                "title_idx",
                                List.of(new IndexField("title", IndexDirection.ASC)),
                                false),
                        new IndexDefinition(
                                "title_idx",
                                List.of(new IndexField("title", IndexDirection.DESC)),
                                false)),
                CollectionRules.locked(),
                false);

        assertThrows(IllegalArgumentException.class, () -> service.validateDefinition(definition));
    }

    @Test
    void validateDefinitionRejectsUnknownIndexField() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null, null)),
                List.of(new IndexDefinition(
                        "missing_idx",
                        List.of(new IndexField("missing", IndexDirection.ASC)),
                        false)),
                CollectionRules.locked(),
                false);

        assertThrows(IllegalArgumentException.class, () -> service.validateDefinition(definition));
    }

    @Test
    void validateDefinitionRejectsInvalidRules() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "posts",
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null, null)),
                List.of(),
                new CollectionRules("status = published", null, null, null, null, "owner"),
                false);

        assertThrows(RuleParseException.class, () -> service.validateDefinition(definition));
    }

    @Test
    void findDefinitionByIdReturnsSeededCollection() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        String collectionName = "lookup_by_id_test";
        TenantTestUtils.seedCollection(collectionName, CollectionRules.locked());

        CollectionDefinition byName = service.findDefinition(ctx, collectionName);
        assertThat(byName, notNullValue());

        CollectionDefinition byId = service.findDefinitionById(ctx, collectionName, byName.id());
        assertThat(byId, notNullValue());
        assertThat(byId.name(), is(collectionName));
    }

    @Test
    void findDefinitionByIdReturnsNullWhenMissing() {
        TenantCollectionService service = Application.getInstance(TenantCollectionService.class);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();

        assertThat(service.findDefinitionById(ctx, "missing", "missing-id"), is(nullValue()));
    }
}
