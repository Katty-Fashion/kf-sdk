package ro.kf.sdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The optional part of {@link Notifications#send(UUID, String, List, java.util.Map, NotificationOptions)}:
 * <ul>
 *   <li>{@code deepLink} -- where the bell takes the recipient: a path of the platform starting with a single
 *       {@code /} (never an absolute URL);</li>
 *   <li>{@code channels} -- {@code in_app} and/or {@code email}, each declared by the notification; absent = every
 *       declared channel;</li>
 *   <li>{@code idempotencyKey} -- the key that makes a repeated request write nothing new; absent = the SDK generates
 *       one per call (the retries of that call reuse it);</li>
 *   <li>{@code recipientRoles} (protocol 1.3, SDK 0.9.0) -- names of grantable system roles
 *       ({@code TENANT_ADMIN}, {@code QC}, ...): the platform adds the active members of each role in the
 *       notification's tenant to {@code recipients}, each person once, and the module sees no subject; absent = only
 *       {@code recipients}. With roles, {@code recipients} may be empty. Which roles exist is the platform's answer
 *       ({@code 422 unknown-role}, {@code SUPER_ADMIN} included).</li>
 * </ul>
 *
 * <pre>{@code
 * kf.notifications().send(tenantId, "m2f-impact-received", List.of(), variables, NotificationOptions.roles("TENANT_ADMIN"));
 * }</pre>
 *
 * @param deepLink       a relative path, or null
 * @param channels       the channels, or null for every declared one
 * @param idempotencyKey the request's idempotency key, or null for a generated one
 * @param recipientRoles the roles whose active members also receive it, or null for none
 */
public record NotificationOptions(String deepLink, List<String> channels, UUID idempotencyKey,
                                  List<String> recipientRoles) {

    public NotificationOptions {
        channels = channels == null ? null : List.copyOf(channels);
        // a null or blank name is kept for Notifications#send to refuse, naming it
        recipientRoles = recipientRoles == null ? null : Collections.unmodifiableList(new ArrayList<>(recipientRoles));
    }

    /** The options before SDK 0.9.0: no role. */
    public NotificationOptions(String deepLink, List<String> channels, UUID idempotencyKey) {
        this(deepLink, channels, idempotencyKey, null);
    }

    /** No option: every declared channel, no link, a generated idempotency key, no role. */
    public static NotificationOptions none() {
        return new NotificationOptions(null, null, null, null);
    }

    /** Only {@code recipientRoles} (protocol 1.3): every declared channel, no link, a generated idempotency key. */
    public static NotificationOptions roles(String... roles) {
        return new NotificationOptions(null, null, null, roles == null ? null : Arrays.asList(roles));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds {@link NotificationOptions}. */
    public static final class Builder {
        private String deepLink;
        private List<String> channels;
        private UUID idempotencyKey;
        private List<String> recipientRoles;

        private Builder() {
        }

        public Builder deepLink(String deepLink) {
            this.deepLink = deepLink;
            return this;
        }

        public Builder channels(List<String> channels) {
            this.channels = channels;
            return this;
        }

        public Builder idempotencyKey(UUID idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /** Protocol 1.3: the roles whose active members in the tenant also receive the notification. */
        public Builder recipientRoles(List<String> recipientRoles) {
            this.recipientRoles = recipientRoles;
            return this;
        }

        public NotificationOptions build() {
            return new NotificationOptions(deepLink, channels, idempotencyKey, recipientRoles);
        }
    }
}
