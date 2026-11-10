package ro.kf.modules.democard.hooks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.modules.democard.testsupport.ModuleDb;
import ro.kf.modules.democard.testsupport.ModuleIT;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** model.after.delete and tenant.after.delete, delivered signed as the platform does. */
class HooksIT extends ModuleIT {

    @Test
    @DisplayName("model.after.delete removes every pin of the model in the event's tenant and notifies each pinner once, even on a redelivery")
    void modelDeleted() throws Exception {
        String t = tenant();
        String other = tenant();
        model(t, 21, "Sacou");
        model(other, 21, "Sacou altul");
        FAKE.member(t, "sub-ana").member(t, "sub-ion");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/21", null);
        calls().user(t, "sub-ion", List.of(), "PUT", "/pins/21", null);
        calls().user(other, "sub-ana", List.of(), "PUT", "/pins/21", null);
        int before = FAKE.notificationRecipients().size();

        String eventId = UUID.randomUUID().toString();
        assertEquals(200, calls().modelDeleted(t, 21, "Sacou", eventId).statusCode());
        assertEquals(0, ModuleDb.pinsOf(t));
        assertEquals(1, ModuleDb.pinsOf(other));
        assertThat(FAKE.notificationRecipients().subList(before, FAKE.notificationRecipients().size()))
                .singleElement().satisfies(r -> assertThat(r).containsExactlyInAnyOrder("sub-ana", "sub-ion"));
        assertThat(FAKE.notifications().getLast()).containsEntry("key", "pinned-model-deleted");

        int after = FAKE.notificationRecipients().size();
        assertEquals(200, calls().modelDeleted(t, 21, "Sacou", eventId).statusCode());
        assertEquals(after, FAKE.notificationRecipients().size());
    }

    @Test
    @DisplayName("model.after.delete for a model nobody pinned sends nothing")
    void modelDeletedWithoutPins() throws Exception {
        int before = FAKE.notifications().size();
        assertEquals(200, calls().modelDeleted(tenant(), 99, "Nimeni", UUID.randomUUID().toString()).statusCode());
        assertEquals(before, FAKE.notifications().size());
    }

    @Test
    @DisplayName("tenant.after.delete deletes the pins of that tenant only, idempotently")
    void tenantDeleted() throws Exception {
        String t = tenant();
        String other = tenant();
        model(t, 3, "Cămașă");
        model(other, 3, "Cămașă");
        calls().user(t, "sub-ana", List.of(), "PUT", "/pins/3", null);
        calls().user(other, "sub-ana", List.of(), "PUT", "/pins/3", null);
        assertEquals(200, FAKE.deliverTenantDeleted(t).status());
        assertEquals(200, FAKE.deliverTenantDeleted(t).status());
        assertEquals(0, ModuleDb.pinsOf(t));
        assertEquals(1, ModuleDb.pinsOf(other));
    }
}
