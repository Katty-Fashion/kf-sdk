package ro.kf.sdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FlowRules} (protocol 2.0, sdk-protocol.md §16.4): the projection {@code order.flow-rule.v1} read from
 * the rule's query -- every field, {@code order.stages} repeated, unknown parameters ignored, an
 * unknown projection or a missing required field refused (the module answers 400) -- and the rule's answer.
 */
class FlowRulesTest {

    /** The query of a step of a line, as the engine's {@code OrderFlowRuleProjection} emits it. */
    static final String FULL = "projection=order.flow-rule.v1&rule.key=item-ready&order.id=812&order.status=CONFIRMED"
            + "&flow.key=g3-production&flow.version=4&instance.id=31&step.key=cutting&step.to=sewing&order.number=PO+2026%2F7"
            + "&order.internal=false&order.customerId=55&step.behavesLike=IN_PRODUCTION&order.stages=production"
            + "&order.stages=sampling&line.id=4401&line.modelId=90&line.modelVersionId=901&line.quantity=120";

    @Test
    void aFullV1QueryGivesEveryField() {
        FlowRules.Projection p = FlowRules.Projection.fromQuery("?" + FULL);
        assertEquals("item-ready", p.ruleKey());
        assertEquals("812", p.orderId());
        assertEquals("CONFIRMED", p.orderStatus());
        assertEquals("g3-production", p.flowKey());
        assertEquals("4", p.flowVersion());
        assertEquals("31", p.instanceId());
        assertEquals("cutting", p.stepKey());
        assertEquals("sewing", p.stepTo());
        assertEquals("PO 2026/7", p.orderNumber());
        assertEquals(Boolean.FALSE, p.orderInternal());
        assertEquals("55", p.orderCustomerId());
        assertEquals("IN_PRODUCTION", p.stepBehavesLike());
        assertEquals(List.of("production", "sampling"), p.orderStages());
        assertTrue(p.hasStage("sampling"));
        assertFalse(p.hasStage("development"));
        assertTrue(p.onLine());
        assertEquals("4401", p.lineId());
        assertEquals("90", p.lineModelId());
        assertEquals("901", p.lineModelVersionId());
        assertEquals("120", p.lineQuantity());
    }

    @Test
    void anInternalOrderWithoutLineHasNoCustomerAndNoLine() {
        FlowRules.Projection p = FlowRules.Projection.fromQuery("projection=order.flow-rule.v1&rule.key=r&order.id=9"
                + "&order.status=COMPLETED&flow.key=f&flow.version=1&instance.id=2&step.key=a&step.to=b&order.internal=true");
        assertEquals(Boolean.TRUE, p.orderInternal());
        assertNull(p.orderCustomerId());
        assertEquals("COMPLETED", p.orderStatus());
        assertEquals(List.of(), p.orderStages());
        assertFalse(p.onLine());
    }

    @Test
    void unknownParametersAreIgnored() {
        FlowRules.Projection p = FlowRules.Projection.fromQuery(FULL + "&order.priority=high&flow.extra=1");
        assertEquals("item-ready", p.ruleKey());
    }

    @Test
    void anUnknownOrMissingProjectionIsRefused() {
        IllegalArgumentException v2 = assertThrows(IllegalArgumentException.class,
                () -> FlowRules.Projection.fromQuery(FULL.replace("order.flow-rule.v1", "order.flow-rule.v2")));
        assertTrue(v2.getMessage().contains("\"order.flow-rule.v2\" is not known"), v2.getMessage());
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> FlowRules.Projection.fromQuery(FULL.replace("projection=order.flow-rule.v1&", "")));
        assertTrue(none.getMessage().contains("the projection is missing"), none.getMessage());
    }

    @Test
    void aMissingRequiredFieldIsRefusedNamingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FlowRules.Projection.fromQuery(FULL.replace("&step.to=sewing", "").replace("&instance.id=31", "")));
        assertTrue(e.getMessage().contains("missing instance.id, step.to"), e.getMessage());
    }

    @Test
    void aRepeatedSingleFieldABadBooleanAndAnOversizedQueryAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> FlowRules.Projection.fromQuery(FULL + "&step.to=packing"));
        assertThrows(IllegalArgumentException.class,
                () -> FlowRules.Projection.fromQuery(FULL.replace("order.internal=false", "order.internal=yes")));
        assertThrows(IllegalArgumentException.class,
                () -> FlowRules.Projection.fromQuery(FULL + "&pad=" + "x".repeat(FlowRules.MAX_QUERY_BYTES)));
    }

    @Test
    void theQueryRoundTripsInTheEnginesOrder() {
        FlowRules.Projection p = FlowRules.Projection.fromQuery(FULL);
        String query = p.toQuery();
        assertTrue(query.startsWith("projection=order.flow-rule.v1&rule.key=item-ready&order.id=812"), query);
        assertTrue(query.contains("order.stages=production&order.stages=sampling"), "the stages sorted: " + query);
        assertEquals(p, FlowRules.Projection.fromQuery(query));
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("projection", List.of("order.flow-rule.v1"));
        for (String name : List.of("rule.key", "order.id", "order.status", "flow.key", "flow.version", "instance.id",
                "step.key", "step.to")) {
            params.put(name, List.of("x"));
        }
        assertEquals("x", FlowRules.Projection.fromParams(params).stepTo());
    }

    @Test
    void theDecisions() {
        assertEquals(Map.of("decision", "pass"), FlowRules.Decision.pass().toJson());
        assertTrue(FlowRules.Decision.pass().passes());
        Map<String, Object> block = FlowRules.Decision.block("rule.blocked", Map.of("item", "A-1")).toJson();
        assertEquals("block", block.get("decision"));
        assertEquals("rule.blocked", block.get("key"));
        assertEquals(Map.of("item", "A-1"), block.get("params"));
        assertEquals(Map.of("decision", "block", "key", "rule.blocked"), FlowRules.Decision.block("rule.blocked").toJson());
        assertFalse(FlowRules.Decision.block("rule.blocked").passes());
    }

    @Test
    void aBlockWithoutKeyOrWithTooManyParamsIsRefusedLocally() {
        assertThrows(IllegalArgumentException.class, () -> FlowRules.Decision.block(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FlowRules.Decision.block(" ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FlowRules.Decision.block("Not A Key", Map.of()));
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) many.put("p" + i, i);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FlowRules.Decision.block("rule.blocked", many));
        assertTrue(e.getMessage().contains("at most 20 params"), e.getMessage());
        many.remove("p20");
        assertEquals(20, ((Map<?, ?>) FlowRules.Decision.block("rule.blocked", many).toJson().get("params")).size());
    }

    @Test
    void theProjectionKeyIsTheManifestOne() {
        assertEquals("order.flow-rule.v1", FlowRules.PROJECTION);
        assertEquals(List.of("development", "sampling", "production"), FlowRules.STAGES);
    }
}
