package constants;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class CollectionNameTest {

    @Test
    void userCreatedCollectionsUseUnderscorePrefix() {
        assertThat(CollectionName.physicalTenantData("trips"), is("_trips"));
        assertThat(CollectionName.tenantData("orders"), is("_orders"));
    }

    @Test
    void usersCollectionUsesUnderscorePrefix() {
        assertThat(CollectionName.physicalTenantData(SystemCollections.USERS), is("_users"));
    }
}
