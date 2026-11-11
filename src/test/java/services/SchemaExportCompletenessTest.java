package services;

import auth.TenantContext;
import dtos.SchemaExportDto;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.CollectionDefinition;
import models.CollectionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Import refuses a collection that has no field or index list. Export of a definition that was
 * stored without them still has to produce empty lists, or the file could not be imported again.
 */
@ExtendWith({TestRunner.class})
class SchemaExportCompletenessTest {

    @Test
    void exportFillsMissingFieldAndIndexLists() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        String name = "export_gaps_" + DbUtils.id();
        String id = DbUtils.id();
        collections.insertDefinition(ctx, new CollectionDefinition(
                id, name, null, null, CollectionRules.locked(), false));
        try {
            SchemaExportDto exported = Application.getInstance(SchemaService.class).export(ctx);
            CollectionDefinition found = exported.collections().stream()
                    .filter(definition -> name.equals(definition.name()))
                    .findFirst()
                    .orElseThrow();

            assertThat(found.fields(), is(List.of()));
            assertThat(found.indexes(), is(List.of()));
        } finally {
            collections.deleteDefinition(ctx, id);
        }
    }
}
