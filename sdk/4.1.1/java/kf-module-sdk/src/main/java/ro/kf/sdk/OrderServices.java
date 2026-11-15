package ro.kf.sdk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The module's service lines on an order (protocol 2.0, {@code order.services.v1}, sdk-protocol.md §16.6): the module
 * adds, updates and removes its OWN lines with a cost; the platform keys them on {@code (orderId, slug, externalRef)}
 * (a second upsert with the same key updates the line), takes {@code source} from the token, never from the body, and
 * keeps the lines when the module stops or the order is cancelled. The user of the context needs
 * {@code api.v1.orders.put}; a {@code system} context writes no line. Delivered by the platform with
 * {@code ORDER_SERVICES} (together with the provider {@code OrderServiceLinesAdapter}); a module relies on it
 * only when the platform announces the feature. A platform before it answers {@code 422 not-delivered}, and one that
 * delivers the feature without a provider {@code 501 no-provider}.
 *
 * <pre>{@code
 * kf.orderServices().upsert(ctx, new OrderServices.Line("812", "4401", "Embroidery",
 *         new BigDecimal("12.50"), "EUR", "embroidery:4401"));
 * kf.orderServices().delete(ctx, "812", "embroidery:4401");
 * }</pre>
 *
 * The local refusals ({@link KfException}, {@code class: permanent}, nothing sent): the capability not declared in
 * {@code capabilities.services} of the manifest, a name outside 1..{@value #MAX_NAME} characters, a negative amount, a
 * currency that is not ISO 4217, an {@code externalRef} outside {@code ^[A-Za-z0-9._:-]{1,128}$}. The platform's
 * refusals are {@link PlatformRefusal}s: {@code invalid} (400, its {@code errors} by path), {@code forbidden} (403),
 * {@code not-found} (404, an unknown port), {@code conflict} (409 {@code order-service-line-owned-by-module}: a
 * delete of a line another module wrote), {@code refused} (422 {@code not-delivered}), {@code unavailable}
 * (501 {@code no-provider}).
 */
public final class OrderServices {

    /**
     * The reason of {@code 409} on a delete (§16.6): the line was written by another module, and a module deletes
     * only its own ({@link PlatformRefusal.Kind#CONFLICT}, permanent).
     */
    public static final String LINE_OWNED_BY_MODULE = "order-service-line-owned-by-module";

    /** The port of protocol 2.0. */
    public static final String PORT = Manifest.SERVICES_PORT;

    /** The longest name of a line. */
    public static final int MAX_NAME = 200;

    /** The module's own key of a line on an order. */
    public static final Pattern EXTERNAL_REF = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /** An ISO 4217 code. */
    public static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /**
     * One service line: {@code orderId} and {@code entryId} the platform's ids (as text), the line's {@code name}, its
     * cost ({@code amount} ≥ 0 in {@code currency}) and the module's own key {@code externalRef}.
     */
    public record Line(String orderId, String entryId, String name, BigDecimal amount, String currency,
                       String externalRef) {

        /** The JSON body of the upsert. */
        public Map<String, Object> toJson() {
            Map<String, Object> cost = new LinkedHashMap<>();
            cost.put("amount", amount);
            cost.put("currency", currency);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderId", orderId);
            body.put("entryId", entryId);
            body.put("name", name);
            body.put("cost", cost);
            body.put("externalRef", externalRef);
            return body;
        }
    }

    private final PlatformChannel channel;

    OrderServices(KfModule kf) {
        this.channel = new PlatformChannel(kf, "services");
    }

    /**
     * Inserts or updates the line of key {@code (line.orderId, this module, line.externalRef)}: {@code POST
     * …/services/order.services.v1} → {@code 204}.
     *
     * @throws KfException     a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the line
     */
    public void upsert(RequestContext context, Line line) {
        Map<String, Object> body = check(line);
        done(channel.send("POST", channel.forwarded(context), "/" + PORT, "", body));
    }

    /** {@link #upsert(RequestContext, Line)} for a background job, with an after delivery's context. */
    public void upsert(HookEvents.DeliveryContext delivery, Line line) {
        Map<String, Object> body = check(line);
        done(channel.send("POST", channel.forwarded(delivery), "/" + PORT, "", body));
    }

    /**
     * Removes this module's line {@code externalRef} of the order {@code orderId}: {@code DELETE
     * …/services/order.services.v1/{externalRef}?orderId=} → {@code 204} (an absent line is not an error).
     */
    public void delete(RequestContext context, String orderId, String externalRef) {
        String[] target = checkDelete(orderId, externalRef);
        done(channel.send("DELETE", channel.forwarded(context), target[0], target[1], null));
    }

    /** {@link #delete(RequestContext, String, String)} for a background job, with an after delivery's context. */
    public void delete(HookEvents.DeliveryContext delivery, String orderId, String externalRef) {
        String[] target = checkDelete(orderId, externalRef);
        done(channel.send("DELETE", channel.forwarded(delivery), target[0], target[1], null));
    }

    // ---------------------------------------------------------------------------------------------------------

    private Map<String, Object> check(Line line) {
        List<String> errors = new ArrayList<>();
        if (line == null) throw PlatformChannel.local("invalid service line: the line is required");
        if (blank(line.orderId())) errors.add("orderId is required");
        if (blank(line.entryId())) errors.add("entryId is required");
        if (blank(line.name()) || line.name().length() > MAX_NAME) {
            errors.add("name must be 1 to " + MAX_NAME + " characters");
        }
        if (line.amount() == null || line.amount().signum() < 0) errors.add("cost.amount must be a number >= 0");
        if (line.currency() == null || !CURRENCY.matcher(line.currency()).matches()) {
            errors.add("cost.currency must be an ISO 4217 code (three upper-case letters)");
        }
        if (line.externalRef() == null || !EXTERNAL_REF.matcher(line.externalRef()).matches()) {
            errors.add("externalRef must match " + EXTERNAL_REF.pattern());
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid service line: " + String.join("; ", errors));
        declared();
        return line.toJson();
    }

    private String[] checkDelete(String orderId, String externalRef) {
        List<String> errors = new ArrayList<>();
        if (blank(orderId)) errors.add("orderId is required");
        if (externalRef == null || !EXTERNAL_REF.matcher(externalRef).matches()) {
            errors.add("externalRef must match " + EXTERNAL_REF.pattern());
        }
        if (!errors.isEmpty()) throw PlatformChannel.local("invalid service line: " + String.join("; ", errors));
        declared();
        // the grammar of externalRef is safe in a path segment: sent as it is (the platform matches it as given)
        return new String[]{"/" + PORT + "/" + externalRef,
                "?orderId=" + PlatformChannel.queryValue(orderId)};
    }

    /** The capability, when the module gave its manifest ({@code KF_MODULE_MANIFEST}). */
    private void declared() {
        Manifest manifest = channel.kf().manifest();
        if (manifest != null && manifest.capabilities().services().stream().noneMatch(s -> PORT.equals(s.port()))) {
            throw PlatformChannel.local("the service " + PORT + " is not declared in capabilities.services of the "
                    + "manifest");
        }
    }

    private void done(PlatformChannel.Answer answer) {
        if (answer.status() >= 200 && answer.status() <= 299) return;
        throw channel.servicesFailure(answer);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
