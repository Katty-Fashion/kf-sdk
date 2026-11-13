package ro.kf.sdk;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rules of a module step in the order flow (protocol 2.0, sdk-protocol.md §16.4): the engine
 * (business-core) calls {@code GET {flowRules[].path}?<order.flow-rule.v1>} with the context {@code system via
 * platform}, before the step advances; the module answers {@code 200 {decision: pass}} or {@code 200 {decision: block,
 * key, params?}}. The engine is fail-closed: a timeout, a non-2xx, a body that is not JSON or an unknown decision block
 * the step; several rules on a step are an AND and every reason is shown. Delivered by the platform with
 * {@code FLOW_RULES}; a module relies on it only when the platform announces the feature. A platform before
 * it refuses {@code capabilities.flowRules} at upload, with the text of the key (§16.7).
 *
 * <p>A rule reads the order ONLY from the projection ({@link Projection#fromQuery}); it never reads {@code order.v1}
 * while it evaluates ({@code order.v1} stays non-{@code system}). A projection it does not know, or a missing
 * required parameter, is answered {@code 400} ({@link IllegalArgumentException} here), which the engine reads as a block.
 */
public final class FlowRules {

    private FlowRules() {
    }

    /** The projection of protocol 2.0: the major is in the key; a rename or a removal is {@code order.flow-rule.v2}. */
    public static final String PROJECTION = Manifest.FLOW_RULE_PROJECTION;

    /** The largest query the engine sends, in bytes (UTF-8, encoded). */
    public static final int MAX_QUERY_BYTES = 4096;

    /** The most {@code params} a {@code block} carries. */
    public static final int MAX_PARAMS = 20;

    /** The required parameters, in the order the engine emits them. */
    public static final List<String> REQUIRED = List.of("projection", "rule.key", "order.id", "order.status",
            "flow.key", "flow.version", "instance.id", "step.key", "step.to");

    /** The optional parameters, in the order the engine emits them; {@code order.stages} is repeated. */
    public static final List<String> OPTIONAL = List.of("order.number", "order.internal", "order.customerId",
            "step.behavesLike", "order.stages", "line.id", "line.modelId", "line.modelVersionId", "line.quantity");

    /** The stages an order may have ticked ({@code order.stages}). */
    public static final List<String> STAGES = List.of("development", "sampling", "production");

    /**
     * The order as a rule sees it ({@code order.flow-rule.v1}); every value is the engine's text, {@code null} when the
     * parameter is absent (not applicable). {@code orderStages} is never null (empty = none ticked).
     */
    public record Projection(String ruleKey, String orderId, String orderStatus, String flowKey, String flowVersion,
                             String instanceId, String stepKey, String stepTo, String orderNumber, Boolean orderInternal,
                             String orderCustomerId, String stepBehavesLike, List<String> orderStages, String lineId,
                             String lineModelId, String lineModelVersionId, String lineQuantity) {

        public Projection {
            orderStages = orderStages == null ? List.of() : List.copyOf(orderStages);
        }

        /** Whether the step is a step of a line ({@code line.id} present). */
        public boolean onLine() {
            return lineId != null;
        }

        /** Whether {@code stage} is ticked on the order. */
        public boolean hasStage(String stage) {
            return orderStages.contains(stage);
        }

        /**
         * Reads the raw query of the rule's request ({@code a=b&c=d}, URL-encoded, a leading {@code ?} allowed).
         * Unknown parameters are ignored.
         *
         * @throws IllegalArgumentException {@code projection} absent or not {@value #PROJECTION}, a required parameter
         *                                  missing or repeated, {@code order.internal} not {@code true|false}, or a
         *                                  query over {@value #MAX_QUERY_BYTES} bytes -- answer {@code 400}
         */
        public static Projection fromQuery(String rawQuery) {
            String q = rawQuery == null ? "" : rawQuery.startsWith("?") ? rawQuery.substring(1) : rawQuery;
            if (q.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
                throw new IllegalArgumentException(PROJECTION + ": the query is over " + MAX_QUERY_BYTES + " bytes");
            }
            Map<String, List<String>> params = new LinkedHashMap<>();
            if (!q.isEmpty()) {
                for (String pair : q.split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String name = decode(eq < 0 ? pair : pair.substring(0, eq));
                    String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                    params.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
                }
            }
            return fromParams(params);
        }

        /**
         * Reads the parameters already decoded ({@code name -> values}, as a web framework gives them); the same rules
         * as {@link #fromQuery(String)}.
         */
        public static Projection fromParams(Map<String, List<String>> params) {
            Map<String, List<String>> p = params == null ? Map.of() : params;
            String projection = single(p, "projection");
            if (projection == null) {
                throw new IllegalArgumentException("the projection is missing (expected projection=" + PROJECTION + ")");
            }
            if (!PROJECTION.equals(projection)) {
                throw new IllegalArgumentException("the projection \"" + projection + "\" is not known by this rule "
                        + "(expected " + PROJECTION + ")");
            }
            List<String> missing = new ArrayList<>();
            for (String name : REQUIRED) {
                if (single(p, name) == null) missing.add(name);
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(PROJECTION + ": missing " + String.join(", ", missing));
            }
            String internal = single(p, "order.internal");
            if (internal != null && !internal.equals("true") && !internal.equals("false")) {
                throw new IllegalArgumentException(PROJECTION + ": order.internal must be true or false (got \""
                        + internal + "\")");
            }
            List<String> stages = new ArrayList<>();
            for (String stage : p.getOrDefault("order.stages", List.of())) {
                if (stage != null && !stage.isBlank()) stages.add(stage);
            }
            return new Projection(single(p, "rule.key"), single(p, "order.id"), single(p, "order.status"),
                    single(p, "flow.key"), single(p, "flow.version"), single(p, "instance.id"), single(p, "step.key"),
                    single(p, "step.to"), single(p, "order.number"), internal == null ? null : Boolean.valueOf(internal),
                    single(p, "order.customerId"), single(p, "step.behavesLike"), stages, single(p, "line.id"),
                    single(p, "line.modelId"), single(p, "line.modelVersionId"), single(p, "line.quantity"));
        }

        /**
         * The query the engine sends for this projection, in the order (absent parameters left
         * out, {@code order.stages} repeated): for a module's tests and the platform's fake.
         */
        public String toQuery() {
            List<String[]> out = new ArrayList<>();
            add(out, "projection", PROJECTION);
            add(out, "rule.key", ruleKey);
            add(out, "order.id", orderId);
            add(out, "order.status", orderStatus);
            add(out, "flow.key", flowKey);
            add(out, "flow.version", flowVersion);
            add(out, "instance.id", instanceId);
            add(out, "step.key", stepKey);
            add(out, "step.to", stepTo);
            add(out, "order.number", orderNumber);
            add(out, "order.internal", orderInternal == null ? null : orderInternal.toString());
            add(out, "order.customerId", orderCustomerId);
            add(out, "step.behavesLike", stepBehavesLike);
            for (String stage : orderStages.stream().sorted().toList()) add(out, "order.stages", stage);
            add(out, "line.id", lineId);
            add(out, "line.modelId", lineModelId);
            add(out, "line.modelVersionId", lineModelVersionId);
            add(out, "line.quantity", lineQuantity);
            StringBuilder sb = new StringBuilder();
            for (String[] e : out) {
                if (!sb.isEmpty()) sb.append('&');
                sb.append(URLEncoder.encode(e[0], StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(e[1], StandardCharsets.UTF_8));
            }
            return sb.toString();
        }

        private static void add(List<String[]> out, String name, String value) {
            if (value != null && !value.isBlank()) out.add(new String[]{name, value});
        }

        private static String single(Map<String, List<String>> p, String name) {
            List<String> values = p.get(name);
            if (values == null || values.isEmpty()) return null;
            if (values.size() > 1) {
                throw new IllegalArgumentException(PROJECTION + ": " + name + " is repeated");
            }
            String v = values.get(0);
            return v == null || v.isBlank() ? null : v;
        }

        private static String decode(String text) {
            try {
                return URLDecoder.decode(text, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(PROJECTION + ": the query is not URL-encoded (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * The answer of a rule: {@code {"decision": "pass"}} or {@code {"decision": "block", "key": …, "params": …}}. The
     * key is a key of the manifest's {@code i18n.en} (the engine shows its text with the params).
     */
    public record Decision(String decision, String key, Map<String, Object> params) {

        public static final String PASS = "pass";
        public static final String BLOCK = "block";

        public Decision {
            params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        /** The step may advance, as far as this rule is concerned. */
        public static Decision pass() {
            return new Decision(PASS, null, Map.of());
        }

        /** The step may not advance: the reason's key, without params. */
        public static Decision block(String key) {
            return block(key, Map.of());
        }

        /**
         * The step may not advance, with the reason.
         *
         * @throws IllegalArgumentException no key, a key outside {@code ^[a-z][a-z0-9_.-]{0,119}$} or more than
         *                                  {@value #MAX_PARAMS} params (the engine would block the step anyway, with
         *                                  its own reason instead of the module's)
         */
        public static Decision block(String key, Map<String, ?> params) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a block needs a key (a key of i18n.en)");
            }
            if (!ro.kf.sdk.Decision.KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("the key \"" + key + "\" must match " + ro.kf.sdk.Decision.KEY.pattern());
            }
            Map<String, Object> p = params == null ? Map.of() : new LinkedHashMap<>(params);
            if (p.size() > MAX_PARAMS) {
                throw new IllegalArgumentException("a block carries at most " + MAX_PARAMS + " params (got " + p.size() + ")");
            }
            return new Decision(BLOCK, key, p);
        }

        /** Whether the step may advance. */
        public boolean passes() {
            return PASS.equals(decision);
        }

        /** The JSON body of the {@code 200} answer. */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", Objects.requireNonNull(decision, "decision"));
            if (BLOCK.equals(decision)) {
                m.put("key", key);
                if (!params.isEmpty()) m.put("params", params);
            }
            return m;
        }
    }
}
