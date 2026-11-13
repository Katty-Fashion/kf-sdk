package ro.kf.sdk;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FlowEvents} (protocol 2.0, sdk-protocol.md §16.3) and the order hooks ({@code kf.orders()}, §16.2): the four
 * events of the flow engine read from their envelope and dispatched to the subscribed handlers (and sent with the
 * registration, without {@code onFailure}); {@code order.field.changed} on the path {@code status}; a before hook on an
 * order may patch only the module's own extensions.
 */
class FlowEventsTest {

    private static final String SECRET = "flow-events-secret";
    private static final String TENANT = "00000000-0000-0000-0000-0000000000f1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Map<String, Object> envelope(String point, String eventId, Map<String, Object> data, String aggregateType) {
        Map<String, Object> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", "delivery-" + eventId);
        ce.put("kfeventid", eventId);
        ce.put("type", "kf." + point);
        ce.put("source", "/" + aggregateType + "/812");
        ce.put("subject", "812");
        ce.put("time", "2026-11-09T10:00:00Z");
        ce.put("datacontenttype", "application/json");
        ce.put("kfmodule", "kf-step");
        ce.put("kfflags", Map.of());
        ce.put("kftenant", TENANT);
        ce.put("kflocale", "en");
        ce.put("kfactor", null);
        ce.put("kfaggregatetype", aggregateType);
        ce.put("kfenvelopeversion", "1");
        ce.put("data", data);
        return ce;
    }

    static Map<String, Object> flowData(String stepKey) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", TENANT);
        data.put("orderId", 812);
        data.put("instanceId", 31);
        data.put("flowKey", "g3-production");
        data.put("flowVersion", 4);
        if (stepKey != null) data.put("stepKey", stepKey);
        data.put("iteration", 1);
        return data;
    }

    private static HookResult send(KfModule kf, Map<String, Object> envelope) {
        byte[] bytes = JSON.writeValueAsBytes(envelope);
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        return kf.handle(bytes, ts, Signatures.sign(SECRET, ts, bytes), now);
    }

    private static KfModule module() {
        return KfModule.of("http://127.0.0.1:1", "kf-step", "kfm_t", SECRET, "http://kf-step:8080");
    }

    @Test
    void eachOfTheFourTypesIsParsed() {
        for (String type : FlowEvents.TYPES) {
            Map<String, Object> ce = envelope(type, "e-" + type, flowData("cutting"), "flow-instance");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ce.get("data");
            FlowEvents.FlowEvent ev = FlowEvents.parse(HookEvents.Envelope.of(ce), data);
            assertEquals(type, ev.type());
            assertEquals("812", ev.orderId());
            assertEquals("31", ev.instanceId());
            assertEquals("g3-production", ev.flowKey());
            assertEquals(4, ev.flowVersion());
            assertEquals("cutting", ev.stepKey());
            assertEquals("e-" + type, ev.eventId());
            assertEquals(TENANT, ev.tenant());
            assertEquals(1, ev.data().get("iteration"), "unknown keys are kept in data");
        }
    }

    @Test
    void stepKeyIsOptionalOnlyOnTheInstanceCompletion() {
        Map<String, Object> completed = envelope(FlowEvents.INSTANCE_COMPLETED, "e1", flowData(null), "flow-instance");
        @SuppressWarnings("unchecked")
        FlowEvents.FlowEvent ev = FlowEvents.parse(HookEvents.Envelope.of(completed), (Map<String, Object>) completed.get("data"));
        assertNull(ev.stepKey());
        Map<String, Object> entered = envelope(FlowEvents.STEP_ENTERED, "e2", flowData(null), "flow-instance");
        @SuppressWarnings("unchecked")
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FlowEvents.parse(HookEvents.Envelope.of(entered), (Map<String, Object>) entered.get("data")));
        assertTrue(e.getMessage().contains("needs data.stepKey"), e.getMessage());
        Map<String, Object> noOrder = new LinkedHashMap<>(flowData("a"));
        noOrder.remove("orderId");
        Map<String, Object> ce = envelope(FlowEvents.STEP_COMPLETED, "e3", noOrder, "flow-instance");
        assertThrows(IllegalArgumentException.class, () -> FlowEvents.parse(HookEvents.Envelope.of(ce), noOrder));
        Map<String, Object> other = envelope("workflow.flow-started", "e4", flowData("a"), "flow-instance");
        assertThrows(IllegalArgumentException.class, () -> FlowEvents.parse(HookEvents.Envelope.of(other), flowData("a")));
    }

    @Test
    void aSubscribedEventReachesItsHandlerAndIsRegisteredWithoutOnFailure() {
        KfModule kf = module();
        List<FlowEvents.FlowEvent> seen = new CopyOnWriteArrayList<>();
        kf.flows().on(FlowEvents.STEP_ENTERED, seen::add).on(FlowEvents.LINE_PROMOTED, seen::add);
        assertThrows(IllegalArgumentException.class, () -> kf.flows().on("order.after.update", e -> { }));

        HookResult result = send(kf, envelope(FlowEvents.STEP_ENTERED, "e-1", flowData("cutting"), "flow-instance"));
        assertEquals(200, result.status(), result.body());
        assertEquals(1, seen.size());
        assertEquals("cutting", seen.get(0).stepKey());

        HookResult notSubscribed = send(kf, envelope(FlowEvents.STEP_COMPLETED, "e-2", flowData("cutting"), "flow-instance"));
        assertEquals(200, notSubscribed.status());
        assertTrue(notSubscribed.body().contains("ignored"), notSubscribed.body());

        HookResult invalid = send(kf, envelope(FlowEvents.STEP_ENTERED, "e-3", Map.of("orderId", 1), "flow-instance"));
        assertEquals(400, invalid.status(), invalid.body());
        assertEquals(1, seen.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) kf.registrationBody().get("hooks");
        assertTrue(hooks.contains(Map.of("event", FlowEvents.STEP_ENTERED)), hooks.toString());
        assertTrue(hooks.contains(Map.of("event", FlowEvents.LINE_PROMOTED)), hooks.toString());
    }

    @Test
    void orderFieldChangedOnStatusReachesTheOrderWatch() {
        KfModule kf = module();
        List<HookEvents.FieldChange> changes = new CopyOnWriteArrayList<>();
        List<HookEvents.FieldChange> modelChanges = new CopyOnWriteArrayList<>();
        kf.orders().onFieldChange(HookEvents.ORDER_PATH_STATUS, changes::add);
        kf.models().onFieldChange(List.of(), modelChanges::add);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", "order");
        data.put("id", "812");
        data.put("field", "status");
        data.put("oldValue", HookEvents.ORDER_STATUS_SHIPPED);
        data.put("newValue", HookEvents.ORDER_STATUS_COMPLETED);
        HookResult r = send(kf, envelope(HookEvents.ORDER_FIELD_CHANGED, "e-9", data, "order"));
        assertEquals(200, r.status(), r.body());
        assertEquals(1, changes.size());
        assertEquals("COMPLETED", changes.get(0).newValue());
        assertEquals(0, modelChanges.size(), "an order change never reaches the model's watches");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) kf.registrationBody().get("hooks");
        assertTrue(hooks.stream().anyMatch(h -> HookEvents.ORDER_FIELD_CHANGED.equals(h.get("event"))
                && List.of("status").equals(h.get("fields"))), hooks.toString());
        assertTrue(HookEvents.ORDER_STATUSES.contains("COMPLETED"));
        assertEquals(7, HookEvents.ORDER_POINTS.size());
    }

    @Test
    void anOrderBeforeHookPatchesOnlyItsOwnExtensions() {
        KfModule kf = module();
        kf.orders().beforeUpdate(ctx -> ctx.patch().extension("checked", true))
                .beforeCreate(ctx -> ctx.patch().set("status", "CONFIRMED"))
                .beforeDelete(ctx -> ctx.reject("order.locked"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", "order");
        data.put("id", "812");
        data.put("before", Map.of("status", "DRAFT"));
        data.put("after", Map.of("status", "CONFIRMED"));
        data.put("changed", List.of("status"));

        HookResult ok = send(kf, envelope(HookEvents.ORDER_BEFORE_UPDATE, "b-1", data, "order"));
        assertEquals(200, ok.status(), ok.body());
        assertTrue(ok.body().contains("\"kf-step\":{\"checked\":true}"), ok.body());

        HookResult core = send(kf, envelope(HookEvents.ORDER_BEFORE_CREATE, "b-2", data, "order"));
        assertEquals(500, core.status(), core.body());
        assertTrue(core.body().contains("permanent") && core.body().contains("status"), core.body());

        HookResult veto = send(kf, envelope(HookEvents.ORDER_BEFORE_DELETE, "b-3", data, "order"));
        assertEquals(200, veto.status());
        assertTrue(veto.body().contains("\"reject\"") && veto.body().contains("order.locked"), veto.body());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) kf.registrationBody().get("hooks");
        assertTrue(hooks.stream().anyMatch(h -> HookEvents.ORDER_BEFORE_UPDATE.equals(h.get("event"))), hooks.toString());
        assertEquals("order", kf.orders().entity());
        assertEquals("model", kf.models().entity());
    }
}
