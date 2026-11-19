package ro.kf.sdk.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.kf.sdk.ErrorClass;
import ro.kf.sdk.KfException;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.NotificationOptions;
import ro.kf.sdk.Notifications;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 1.3: a module sends a notification to a ROLE ({@code recipientRoles}) through the
 * SDK, against {@link FakePlatform} with two tenants. The fake resolves the active members of the role in the
 * notification's tenant only, joins them with {@code recipients} (each person once), refuses {@code SUPER_ADMIN} and
 * any other unknown role with {@code 422 unknown-role}, more than 200 resolved people with
 * {@code 422 too-many-recipients}, and answers only {@code {accepted, duplicates}} -- no subject reaches the module.
 *
 * <p>In {@code ro.kf.sdk.testing} (not derived, {@code sdk/tools/derive-map.json}): it tests the module's view through
 * the Java tests artifact's fake. The SDK's own local rules are in {@code NotificationsTest} (derived).
 */
class NotificationsRecipientRolesTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String KEY = "m2f-impact-received";

    private FakePlatform fake;
    private Notifications notifications;

    @BeforeEach
    void start() throws Exception {
        fake = FakePlatform.start("roles-mod");
        fake.member(TENANT_A.toString(), "admin-a1", "TENANT_ADMIN")
                .member(TENANT_A.toString(), "admin-a2", "TENANT_ADMIN", "QC")
                .member(TENANT_A.toString(), "designer-a", "DESIGNER")
                .inactiveMember(TENANT_A.toString(), "admin-a-gone", "TENANT_ADMIN")
                .member(TENANT_B.toString(), "admin-b", "TENANT_ADMIN")
                .member(TENANT_B.toString(), "super-b", "SUPER_ADMIN");
        KfModule kf = fake.module();
        notifications = kf.notifications().withRetryDelays(List.of(Duration.ofMillis(1)));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    @DisplayName("recipientRoles(TENANT_ADMIN), no recipients → the active admins of A only, none of B; no subject in the answer")
    void roleResolvedInTheNotificationsTenantOnly() {
        Notifications.Result result = notifications.send(TENANT_A, KEY, List.of(), Map.of("entityName", "Rochie"),
                NotificationOptions.roles("TENANT_ADMIN"));

        assertEquals(2, result.accepted());
        assertEquals(List.of(List.of("admin-a1", "admin-a2")), fake.notificationRecipients());
        Map<String, Object> sent = fake.notifications().get(0);
        assertEquals(List.of(), sent.get("recipients"));
        assertEquals(List.of("TENANT_ADMIN"), sent.get("recipientRoles"));
    }

    @Test
    @DisplayName("recipients ∪ roles, each person once: the creator who is also an admin counts once")
    void unionDeduplicated() {
        Notifications.Result result = notifications.send(TENANT_A, KEY, List.of("admin-a1", "designer-a"),
                Map.of(), NotificationOptions.builder().recipientRoles(List.of("TENANT_ADMIN", "QC")).build());

        assertEquals(3, result.accepted());
        assertEquals(List.of(List.of("admin-a1", "designer-a", "admin-a2")), fake.notificationRecipients());
    }

    @Test
    @DisplayName("a role without active members is no error: accepted 0")
    void roleWithoutMembers() {
        Notifications.Result result = notifications.send(TENANT_B, KEY, List.of(), Map.of(),
                NotificationOptions.roles("OPERATOR"));

        assertEquals(0, result.accepted());
    }

    @Test
    @DisplayName("SUPER_ADMIN, a custom role or another platform name → 422 unknown-role, permanent, nothing recorded")
    void unknownRoleRefused() {
        for (String role : List.of("SUPER_ADMIN", "QA_LEAD", "PLATFORM_ADMIN")) {
            Notifications.Rejected e = assertThrows(Notifications.Rejected.class, () -> notifications.send(TENANT_B, KEY,
                    List.of(), Map.of(), NotificationOptions.roles(role)), role);
            assertEquals(422, e.problem().status(), role);
            assertEquals("unknown-role", e.reason(), role);
            assertEquals(ErrorClass.PERMANENT, e.errorClass(), role);
        }
        assertTrue(fake.notificationRecipients().isEmpty(), "a refused notification is not recorded as sent");
    }

    @Test
    @DisplayName("more than 200 people resolved → 422 too-many-recipients")
    void tooManyResolved() {
        IntStream.rangeClosed(1, 201).forEach(i -> fake.member(TENANT_B.toString(), "qc-" + i, "QC"));

        Notifications.Rejected e = assertThrows(Notifications.Rejected.class, () -> notifications.send(TENANT_B, KEY,
                List.of(), Map.of(), NotificationOptions.roles("QC")));

        assertEquals(422, e.problem().status());
        assertEquals("too-many-recipients", e.reason());
    }

    @Test
    @DisplayName("no recipient and no role → refused locally, as today: nothing reaches the platform")
    void neitherRecipientsNorRoles() {
        KfException e = assertThrows(KfException.class, () -> notifications.send(TENANT_A, KEY, List.of(), Map.of()));

        assertEquals(0, e.problem().status());
        assertTrue(e.getMessage().contains("recipients names no one"), e.getMessage());
        assertFalse(fake.requests().stream().anyMatch(r -> r.path().endsWith("/notifications")));
    }
}
