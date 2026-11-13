package ro.kf.sdk;

import ro.kf.sdk.HookEvents.AfterEvent;
import ro.kf.sdk.HookEvents.AfterHandler;
import ro.kf.sdk.HookEvents.CallbackRecord;
import ro.kf.sdk.HookEvents.FieldChange;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Entry point of the KF module SDK.
 *
 * <pre>{@code
 * KfModule kf = KfModule.fromEnv();
 * kf.models()
 *   .beforeCreate(ctx -> ctx.after().name().contains("x") ? ctx.reject("veto.no-x") : ctx.allow())
 *   .beforeUpdate(50, ModelHooks.OnFailure.BLOCK, ctx -> ctx.allow())   // block needs the manifest capability
 *   .afterUpdate(ev -> log.info("updated {}", ev.id()));
 * kf.registerField("order", "priority_code", FieldType.STRING, "orders_extra", "field.priority"); // label = i18n key
 * kf.ui().contribute("models.form.edit.fields.after",
 *         Surface.react("/m/my-mod/ui/surfaces.mjs", "MyFields").css("/m/my-mod/ui/surfaces.css").order(10));
 * kf.start();                               // checks kf-module-conf.json locally, then registers in the background,
 *                                           // retrying transient errors only
 * kf.flags().isEnabled("my-flag");          // feature flags: fetched after registering, refreshed by every callback
 * kf.notifications().send(tenantId, "author-changed", List.of(subject), Map.of("modelName", "X")); // declared,
 * kf.reads().get(ctx, "model.v1", "42");    // protocol 1.1: the platform's objects, in the context's tenant
 * kf.settings().effective(ctx);             // settings, secrets(), extensions(): the other services of protocol 1.1
 * // serve POST /_kf/hooks with kf.handle(...) — see KfHttpServer or ro.kf.sdk.spring.EnableKfModule; the module's
 * // own routes get the verified context (RequestContext) from the same adapters
 * }</pre>
 *
 * Environment (injected by the platform): {@code KF_PLATFORM_URL}, {@code KF_MODULE_SLUG}, {@code KF_MODULE_TOKEN},
 * {@code KF_HOOK_SECRET}, and {@code KF_CALLBACK_BASE} for exposed services. {@code KF_MODULE_MANIFEST} (default
 * {@code ./kf-module-conf.json}) is the manifest the SDK checks the registration against before sending it
 * ({@link ManifestCheck}); without it the local check is skipped with a warning.
 *
 * <p>Version: {@link SdkVersion#SDK_VERSION}, manifest {@code schemaVersion} {@link SdkVersion#PROTOCOL_SCHEMA_VERSION}.
 */
public final class KfModule {

    public static final String DEFAULT_HOOK_PATH = "/_kf/hooks";
    /** Where {@link #fromEnv()} looks for the manifest when {@code KF_MODULE_MANIFEST} is not set. */
    public static final String DEFAULT_MANIFEST = "./kf-module-conf.json";
    private static final System.Logger LOG = System.getLogger("ro.kf.sdk");

    private final String platformUrl;
    private final String slug;
    private final String token;
    private final String hookSecret;
    private final String callbackBase;
    private final String hookPath;

    private final AuditClient audit = new AuditClient(this);
    private final ReadsClient reads = new ReadsClient(this);
    private final SettingsClient settings = new SettingsClient(this);
    private final SecretsClient secrets = new SecretsClient(this);
    private final ExtensionsClient extensions = new ExtensionsClient(this);
    private final Notifications notifications = new Notifications(this);
    private final ModelHooks models = new ModelHooks();
    private final ModelHooks orders = new ModelHooks("order");
    private final FlowEvents flows = new FlowEvents();
    private final OrderServices orderServices = new OrderServices(this);
    private final TenantHooks tenants = new TenantHooks();
    private final StorageClient storage = new StorageClient(this);
    private final PeersClient peers = new PeersClient(this);
    private final EventsClient events = new EventsClient(this);
    private final UiContributions ui = new UiContributions();
    private final FeatureFlags flags = new FeatureFlags();
    private final List<Map<String, Object>> fields = new ArrayList<>();
    private final List<Consumer<CallbackRecord>> observers = new CopyOnWriteArrayList<>();
    private final Set<String> publicPaths = new CopyOnWriteArraySet<>(Set.of("/health"));
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** The registration retry schedule (transient errors only); the last step repeats. */
    public static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(30));

    private volatile List<Duration> retryDelays = DEFAULT_RETRY_DELAYS;
    private volatile Thread registrationThread;
    private volatile boolean started;
    private volatile boolean registered;
    private volatile boolean registrationStopped;
    private volatile PlatformProblem lastRegistrationProblem;
    private volatile String lastRegistrationError;
    private volatile Map<String, Object> lastRegistrationSummary;
    private volatile String manifestJson;
    private volatile String manifestSource;

    /** How many verified contexts are kept for {@link AuditClient} (the oldest go first). */
    static final int MAX_FORWARDED_CONTEXTS = 4096;

    /**
     * The raw {@code X-KF-Context} and the platform's {@code X-KF-Context-Proof} of every context this instance
     * verified, by {@code requestId}: {@link AuditClient} sends them back, unchanged, with the module token.
     * Bounded; an entry is used only while its {@code ts} is inside the ±300 s window.
     */
    private final Map<String, ForwardedContext> forwardedContexts = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ForwardedContext> eldest) {
                    return size() > MAX_FORWARDED_CONTEXTS;
                }
            });

    /** What {@link AuditClient} sends back for one verified context. */
    record ForwardedContext(String contextHeader, String proof, long ts) {
    }

    private KfModule(String platformUrl, String slug, String token, String hookSecret, String callbackBase, String hookPath) {
        this.platformUrl = platformUrl == null ? null : platformUrl.replaceAll("/+$", "");
        this.slug = slug;
        this.token = token;
        this.hookSecret = hookSecret;
        this.callbackBase = callbackBase == null ? null : callbackBase.replaceAll("/+$", "");
        this.hookPath = hookPath;
    }

    /** Reads the {@code KF_*} environment variables injected by the platform. */
    public static KfModule fromEnv() {
        return fromEnv(System.getenv());
    }

    public static KfModule fromEnv(Map<String, String> env) {
        KfModule kf = new KfModule(env.get("KF_PLATFORM_URL"), env.get("KF_MODULE_SLUG"), env.get("KF_MODULE_TOKEN"),
                env.get("KF_HOOK_SECRET"), env.get("KF_CALLBACK_BASE"), DEFAULT_HOOK_PATH);
        String location = env.getOrDefault("KF_MODULE_MANIFEST", DEFAULT_MANIFEST);
        Path path = Path.of(location);
        if (Files.isRegularFile(path)) {
            try {
                kf.manifestJson = Files.readString(path, StandardCharsets.UTF_8);
                kf.manifestSource = path.toString();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "KF SDK: the manifest {0} cannot be read ({1}); the local check "
                        + "is skipped", location, describe(e));
            }
        } else {
            LOG.log(System.Logger.Level.WARNING, "KF SDK: no manifest at {0} (KF_MODULE_MANIFEST); the registration is "
                    + "not checked locally, only by the platform", location);
        }
        return kf;
    }

    /** Explicit configuration (tests, local runs). */
    public static KfModule of(String platformUrl, String slug, String token, String hookSecret, String callbackBase) {
        return new KfModule(platformUrl, slug, token, hookSecret, callbackBase, DEFAULT_HOOK_PATH);
    }

    /**
     * The manifest the registration is checked against before it is sent ({@link ManifestCheck}); replaces the one
     * {@link #fromEnv()} read. {@code null} disables the local check.
     */
    public KfModule withManifest(String manifestJson) {
        this.manifestJson = manifestJson;
        this.manifestSource = manifestJson == null ? null : "kf-module-conf.json (given)";
        return this;
    }

    /** The manifest the SDK read, typed, or null when there is none. Never throws: a broken manifest is null here. */
    public Manifest manifest() {
        String json = manifestJson;
        if (json == null) return null;
        try {
            return Manifest.parse(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- declaration ------------------------------------------------------------------------

    public ModelHooks models() {
        return models;
    }

    /**
     * Hooks on the platform's orders (protocol 2.0, sdk-protocol.md §16.2; the points {@code order.*}, public): the shape of {@link #models()} -- {@code kf.orders().beforeUpdate(ctx -> …)},
     * {@code kf.orders().onFieldChange(HookEvents.ORDER_PATH_STATUS, change -> …)}. A patch carries only the module's
     * own {@code extensions} (the order's core fields are {@code patch: []}): a patch that names a core field is
     * answered {@code 500 permanent}, the module's bug.
     */
    public ModelHooks orders() {
        return orders;
    }

    /**
     * The events of the flow engine (protocol 2.0, sdk-protocol.md §16.3): {@code kf.flows().on(FlowEvents.STEP_ENTERED,
     * e -> …)}; delivered by the platform with {@code FLOW_EVENTS}, used only when the platform announces it
     * ({@link FlowEvents}).
     */
    public FlowEvents flows() {
        return flows;
    }

    /**
     * The module's service lines on an order (protocol 2.0, {@code order.services.v1}, sdk-protocol.md §16.6):
     * {@code kf.orderServices().upsert(ctx, line)}, {@code delete(ctx, orderId, externalRef)}; delivered by the
     * platform with {@code ORDER_SERVICES}, used only when the platform announces it ({@link OrderServices}).
     */
    public OrderServices orderServices() {
        return orderServices;
    }

    /** UI contributions to the host's frontend contact surfaces (sent with the registration). */
    public UiContributions ui() {
        return ui;
    }

    /** The module's feature flags: {@code kf.flags().isEnabled("key")} (unknown keys are off). */
    public FeatureFlags flags() {
        return flags;
    }

    /**
     * The module's access to the platform's audit trail (capability {@code audit}; implemented by the
     * platform): {@code kf.audit().record(ctx, new AuditRecord("NOTE_CREATED", "Note", "42"))} inside a
     * route handler, with the {@link RequestContext} the adapter verified ({@link AuditClient}).
     */
    public AuditClient audit() {
        return audit;
    }

    /**
     * The module's reads of platform objects (capability {@code reads}, protocol 1.1 §15.1):
     * {@code kf.reads().list(ctx, "order.v1", Map.of("modelVersionId", "17"), 0, 50)} and
     * {@code kf.reads().get(ctx, "model.v1", "42")} inside a route or hook handler, with the {@link RequestContext} the
     * adapter verified ({@link ReadsClient}).
     */
    public ReadsClient reads() {
        return reads;
    }

    /**
     * The module's settings in the context's tenant (capability {@code settings}, protocol 1.1 §15.2):
     * {@code kf.settings().effective(ctx)}, typed by the manifest ({@link SettingsClient}).
     */
    public SettingsClient settings() {
        return settings;
    }

    /**
     * The module's secrets (capability {@code secrets}, protocol 1.1 §15.3):
     * {@code kf.secrets().write(ctx, name, value)} → a {@code vault://} reference, {@code kf.secrets().resolve(ctx, ref)}
     * → the value; a value is never logged ({@link SecretsClient}).
     */
    public SecretsClient secrets() {
        return secrets;
    }

    /**
     * The module's files through the platform (protocol 1.2 §3.5, capability {@code storage}):
     * {@code kf.storage().uploadUrl(ctx, contentType, size)} → a presigned {@code PUT} under a key the platform chooses,
     * {@code kf.storage().downloadUrl(ctx, key)} → a presigned {@code GET} ({@link StorageClient}).
     */
    public StorageClient storage() {
        return storage;
    }

    /**
     * The module's reads and commands of another module, through the platform (protocol 2.0, sdk-protocol.md §15.9):
     * {@code kf.peers().read(ctx, "kf-stock", "m.kf-stock.level.v1", Map.of())} and
     * {@code kf.peers().command(ctx, "kf-stock", "reserve", PeersClient.newIdempotencyKey(), body)}, with the
     * {@link RequestContext} the adapter verified or, for a background job, an after delivery's
     * {@link HookEvents.DeliveryContext} ({@link PeersClient}).
     */
    public PeersClient peers() {
        return peers;
    }

    /**
     * The module events {@code m.{owner}.after.{name}} (protocol 2.0, sdk-protocol.md §6.3):
     * {@code kf.events().emit(ctx, "level-changed", data)} for the module's own, {@code kf.events().on(owner, name,
     * handler)} to subscribe to another module's ({@link EventsClient}).
     */
    public EventsClient events() {
        return events;
    }

    /**
     * Hooks on the platform's tenants (protocol 1.2 §6.1): {@code kf.tenants().onDeleted(d -> …)} receives
     * {@code tenant.after.delete}; from protocol 1.3, {@code onSoftDelete} / {@code onRestore} receive
     * {@code tenant.after.soft-delete} / {@code tenant.after.restore} ({@link TenantHooks}).
     */
    public TenantHooks tenants() {
        return tenants;
    }

    /**
     * The module's own extension values on platform objects (protocol 1.1 §15.4):
     * {@code kf.extensions().write(ctx, "model_version", "17", values)} sends exactly the given fields
     * ({@link ExtensionsClient}).
     */
    public ExtensionsClient extensions() {
        return extensions;
    }

    /**
     * The notifications the module declared in {@code capabilities.notifications} (sdk-protocol.md §11.1):
     * {@code kf.notifications().send(tenantId, "author-changed", List.of(subject), Map.of("modelName", "X"))}
     * ({@link Notifications}).
     */
    public Notifications notifications() {
        return notifications;
    }

    /**
     * Declares an extension field of an extensible entity of the platform ({@code model}, {@code order},
     * {@code customer}), stored in the real column {@code name} of the table {@code m_{slug}_{table}}
     * (created by the platform). Values appear under {@code extensions.{slug}.{name}} of that entity.
     *
     * <p>The field must be declared in {@code capabilities.fields} of {@code kf-module-conf.json} with the same entity,
     * name, type and table; the label is a translation KEY of {@code i18n.en}, or null for the
     * field's name.
     *
     * @param entity   the logical entity name, e.g. {@code order}; the platform's registry decides which exist
     * @param labelKey the translation key of the label, or null
     */
    public KfModule registerField(String entity, String name, FieldType type, String table, String labelKey) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("entity", Objects.requireNonNull(entity, "entity"));
        f.put("name", Objects.requireNonNull(name, "name"));
        f.put("type", Objects.requireNonNull(type, "type").name());
        f.put("table", Objects.requireNonNull(table, "table"));
        if (labelKey != null) f.put("label", labelKey);
        fields.add(f);
        return this;
    }

    /** Observer called for every verified callback (e.g. to keep a debug log of what was received). */
    public KfModule onCallback(Consumer<CallbackRecord> observer) {
        observers.add(observer);
        return this;
    }

    public String slug() {
        return slug;
    }

    public String hookPath() {
        return hookPath;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** The last registration failure as one line (status, class, {@code detail}; never the token), or null. */
    public String lastRegistrationError() {
        return lastRegistrationError;
    }

    /** The last registration failure as read from the platform's answer, or null. */
    public PlatformProblem lastRegistrationProblem() {
        return lastRegistrationProblem;
    }

    /**
     * True once the registration stopped on a permanent error (a wrong token, a module in the wrong state, a refused
     * registration, a manifest the local check refuses): the SDK does not retry it; the module must be fixed and
     * restarted.
     */
    public boolean isRegistrationStopped() {
        return registrationStopped;
    }

    /**
     * Replaces the retry schedule of the registration (transient errors only; the last step repeats). The default is
     * {@link #DEFAULT_RETRY_DELAYS}; a {@code Retry-After} of the platform is honoured up to the longest step.
     */
    public KfModule withRetryDelays(List<Duration> delays) {
        if (delays == null || delays.isEmpty() || delays.stream().anyMatch(d -> d == null || d.isNegative())) {
            throw new IllegalArgumentException("retry delays must be a non-empty list of non-negative durations");
        }
        this.retryDelays = List.copyOf(delays);
        return this;
    }

    public Map<String, Object> lastRegistrationSummary() {
        return lastRegistrationSummary;
    }

    /** The body of {@code PUT /api/v1/platform/modules/{slug}/registrations}. */
    /**
     * The warnings of the last successful registration ({@code warnings[]} of the platform's answer): among them
     * for a contribution to a slot its owner does not offer (yet) — stored inactive, not served until it does
     * (sdk-protocol.md §7.12). Empty before the first registration. Each one is also logged at WARNING.
     */
    public java.util.List<String> registrationWarnings() {
        Map<String, Object> summary = lastRegistrationSummary;
        if (summary == null || !(summary.get("warnings") instanceof List<?> warnings)) return List.of();
        return warnings.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    public Map<String, Object> registrationBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callbackUrl", callbackBase == null ? null : callbackBase + hookPath);
        List<Map<String, Object>> hooks = new ArrayList<>(models.registration());
        hooks.addAll(orders.registration());
        hooks.addAll(flows.registration());
        hooks.addAll(tenants.registration());
        hooks.addAll(events.registration());
        body.put("hooks", hooks);
        body.put("fields", List.copyOf(fields));
        body.put("surfaces", ui.registration());
        return body;
    }

    // ---- registration -----------------------------------------------------------------------

    /**
     * Starts the registration in a background daemon thread. It retries (1 s … 30 s) only <strong>transient</strong>
     * errors -- network errors, {@code 503} during the first install, any answer with {@code class: transient}
     * ({@link ErrorClass}); a permanent error ({@code 401}, {@code 403}, {@code 409}, {@code 422}, an unknown class)
     * stops it with one ERROR log naming the status and the {@code detail} ({@link #isRegistrationStopped()},
     * {@link #lastRegistrationError()}). Idempotent. Without {@code KF_PLATFORM_URL}/{@code KF_MODULE_SLUG}/{@code KF_MODULE_TOKEN} it only logs a warning.
     */
    public synchronized KfModule start() {
        if (started) return this;
        started = true;
        if (!checkManifest()) return this;
        if (platformUrl == null || slug == null || token == null) {
            LOG.log(System.Logger.Level.WARNING, "KF SDK: KF_PLATFORM_URL / KF_MODULE_SLUG / KF_MODULE_TOKEN not set, "
                    + "not registering (running outside the platform?)");
            return this;
        }
        Thread t = new Thread(this::registerLoop, "kf-sdk-register");
        t.setDaemon(true);
        registrationThread = t;
        t.start();
        return this;
    }

    /**
     * The local check of the manifest and of the registration against it, before the first request. A violation is
     * permanent: nothing is sent and nothing is retried.
     */
    private boolean checkManifest() {
        String json = manifestJson;
        if (json == null) return true;
        ManifestCheck.Result result = ManifestCheck.check(json, registrationBody(), Set.of());
        for (String warning : result.warnings()) {
            LOG.log(System.Logger.Level.WARNING, "KF SDK: manifest {0}: {1}", manifestSource, warning);
        }
        if (result.ok()) return true;
        PlatformProblem problem = PlatformProblem.local("the registration of " + slug + " was refused by the local check "
                + "of " + manifestSource + ": " + String.join("; ", result.errors()));
        lastRegistrationProblem = problem;
        lastRegistrationError = problem.describe();
        registrationStopped = true;
        LOG.log(System.Logger.Level.ERROR, "KF SDK: {0}; nothing was sent. Fix kf-module-conf.json or the "
                + "registration and restart the module.", lastRegistrationError);
        return false;
    }

    /** Stops a registration still retrying (shutdown, tests). Idempotent; a finished registration is unaffected. */
    public synchronized void stop() {
        Thread t = registrationThread;
        registrationThread = null;
        if (t != null) t.interrupt();
    }

    private void registerLoop() {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        String url = platformUrl + "/api/v1/platform/modules/" + slug + "/registrations";
        List<Duration> delays = retryDelays;
        int attempt = 0;
        while (!registered && !Thread.currentThread().isInterrupted()) {
            PlatformProblem problem;
            try {
                String body = json.writeValueAsString(registrationBody());
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    registered = true;
                    lastRegistrationError = null;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> summary = json.readValue(response.body(), Map.class);
                    lastRegistrationSummary = summary;
                    LOG.log(System.Logger.Level.INFO, "KF SDK: module {0} registered: {1}", slug, response.body());
                    if (summary.get("warnings") instanceof List<?> warnings) {
                        for (Object w : warnings) LOG.log(System.Logger.Level.WARNING, "KF SDK: registration warning: {0}", w);
                    }
                    fetchFlags(client);
                    return;
                }
                problem = PlatformProblem.parse(response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // describe() walks the cause chain: HttpClient throws ConnectException with a null message and
                // keeps the reason (refused, unresolvable host) on its causes.
                problem = PlatformProblem.network(e);
            }
            lastRegistrationProblem = problem;
            lastRegistrationError = problem.describe();
            if (!problem.retryable()) {
                // a permanent error does not fix itself; retrying it forever only hides it.
                registrationStopped = true;
                LOG.log(System.Logger.Level.ERROR, "KF SDK: registration of {0} refused permanently ({1}); not retrying. "
                        + "Fix the cause and restart the module.", slug, lastRegistrationError);
                return;
            }
            Duration delay = delays.get(Math.min(attempt++, delays.size() - 1));
            if (problem.retryAfterSeconds() != null) {
                // Retry-After paces the retries (503 during the first install), never beyond the schedule's longest step
                Duration asked = Duration.ofSeconds(problem.retryAfterSeconds());
                Duration longest = delays.get(delays.size() - 1);
                if (asked.compareTo(delay) > 0) delay = asked.compareTo(longest) > 0 ? longest : asked;
            }
            LOG.log(System.Logger.Level.WARNING, "KF SDK: registration of {0} failed ({1}), retrying in {2} ms",
                    slug, lastRegistrationError, delay.toMillis());
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * One line for a failed platform call, cause chain included: {@code ConnectException (cause: ClosedChannelException)}
     * instead of {@code ConnectException: null}.
     *
     * <p>{@link HttpClient#send} reports a refused connection or an unresolvable host as a {@code ConnectException} with no
     * message, the reason sitting two causes down. Without it "platform down" and "wrong host" read the same in the module's
     * log. A {@code null} message is never printed; a cause that renders like the previous one adds nothing and is skipped;
     * at most three causes, and a cyclic chain stops. Only the throwables are read, never the request, so neither the token
     * nor the hook secret can reach the line.
     */
    static String describe(Throwable error) {
        StringBuilder line = new StringBuilder(render(error));
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(error);
        List<String> causes = new ArrayList<>();
        String previous = render(error);
        for (Throwable cause = error.getCause(); cause != null && causes.size() < 3 && seen.add(cause); cause = cause.getCause()) {
            String rendered = render(cause);
            if (!rendered.equals(previous)) causes.add(rendered);
            previous = rendered;
        }
        if (!causes.isEmpty()) line.append(" (cause: ").append(String.join("; ", causes)).append(')');
        return line.toString();
    }

    private static String render(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }

    /** {@code GET /api/v1/platform/modules/{slug}/flags} with the module token, once after registering (best effort). */
    private void fetchFlags(HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl + "/api/v1/platform/modules/" + slug + "/flags"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.log(System.Logger.Level.WARNING, "KF SDK: reading the feature flags failed: HTTP {0}", response.statusCode());
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Object o : json.readValue(response.body(), List.class)) {
                if (o instanceof Map<?, ?> f && f.get("key") != null) values.put(String.valueOf(f.get("key")), f.get("enabled"));
            }
            flags.replace(values);
            LOG.log(System.Logger.Level.INFO, "KF SDK: feature flags {0}", flags.all());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "KF SDK: reading the feature flags failed: {0}", e.getMessage());
        }
    }

    // ---- callbacks --------------------------------------------------------------------------

    /**
     * Handles one platform callback ({@code POST /_kf/hooks}) and returns what to answer. In this order:
     * <ol>
     *   <li>the signature on the raw body ({@link Signatures}, ±300 s): {@code 401};</li>
     *   <li>the body is a CloudEvent JSON object: {@code 400};</li>
     *   <li>the envelope version ({@code kfenvelopeversion}, rule): a major outside
     *       {@link SdkVersion#SUPPORTED_ENVELOPE_MAJORS}, a missing or a non-numeric value: {@code 422}
     *       {@code https://kf.ro/problems/unsupported-envelope-version}, {@code class: permanent}, with
     *       {@code received} and {@code supported}; no handler is called;</li>
     *   <li>the handlers: {@code 200} with the decision (before) or an ack (after / field change); {@code 500} when a
     *       handler throws ({@code class: transient}) or answers a patch on delete ({@code class: permanent}).</li>
     * </ol>
     * Every refusal is an RFC 9457 body ({@code application/problem+json}) with its {@code class}.
     */
    public HookResult handle(byte[] rawBody, String timestampHeader, String signatureHeader) {
        return handle(rawBody, timestampHeader, signatureHeader, Instant.now().getEpochSecond());
    }

    /**
     * {@link #handle(byte[], String, String)} with the context the platform puts on an after delivery (protocol 2.0,
     * sdk-protocol.md §5.5): {@code X-KF-Context} and {@code X-KF-Context-Proof}, exposed to the handler as
     * {@link HookEvents.Envelope#deliveryContext()} for a background job. The adapters call this one. Both null (a
     * before hook, a platform before 2.0) = no delivery context; a header the SDK cannot read is logged and left out,
     * never a reason to refuse the delivery (the signature covers {@code ts + "." + body}, not the headers).
     */
    public HookResult handle(byte[] rawBody, String timestampHeader, String signatureHeader, String contextHeader,
                             String proofHeader) {
        return handle(rawBody, timestampHeader, signatureHeader, contextHeader, proofHeader,
                Instant.now().getEpochSecond());
    }

    public HookResult handle(String rawBody, String timestampHeader, String signatureHeader) {
        return handle(rawBody.getBytes(StandardCharsets.UTF_8), timestampHeader, signatureHeader);
    }

    /** {@link #handle(byte[], String, String)} at a given time (epoch seconds), for tests and replays. */
    public HookResult handle(byte[] rawBody, String timestampHeader, String signatureHeader, long nowSeconds) {
        return handle(rawBody, timestampHeader, signatureHeader, null, null, nowSeconds);
    }

    /** {@link #handle(byte[], String, String, String, String)} at a given time (epoch seconds), for tests and replays. */
    public HookResult handle(byte[] rawBody, String timestampHeader, String signatureHeader, String contextHeader,
                             String proofHeader, long nowSeconds) {
        String invalid = Signatures.verify(hookSecret, timestampHeader, signatureHeader, rawBody, nowSeconds);
        if (invalid != null) {
            return problem(401, "invalid-signature", "Invalid signature", invalid, ErrorClass.PERMANENT, Map.of());
        }

        Map<String, Object> ce;
        try {
            Object parsed = json.readValue(rawBody, Object.class);
            if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("not an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) parsed;
            ce = object;
        } catch (RuntimeException e) {
            return problem(400, "invalid-envelope", "Invalid envelope", "body is not a CloudEvent JSON object",
                    ErrorClass.PERMANENT, Map.of());
        }

        // the envelope version is read after the signature and the parsing, before any handler
        Object received = ce.get("kfenvelopeversion");
        if (!supportedEnvelope(received)) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("received", received == null ? null : String.valueOf(received));
            extra.put("supported", SdkVersion.SUPPORTED_ENVELOPE_MAJORS.stream().sorted().toList());
            LOG.log(System.Logger.Level.WARNING, "KF SDK: envelope version {0} is not supported by kf-module-sdk {1} "
                    + "(supported majors: {2}); the handler was not called", received, SdkVersion.SDK_VERSION,
                    SdkVersion.SUPPORTED_ENVELOPE_MAJORS);
            return problem(422, "unsupported-envelope-version", "Unsupported envelope version",
                    "kfenvelopeversion " + (received == null ? "is missing" : "\"" + received + "\" is not supported")
                            + "; this SDK supports the majors " + extra.get("supported"), ErrorClass.PERMANENT, extra);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = ce.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        // the module's effective flags as the platform had them when it sent the event
        if (ce.get("kfflags") instanceof Map<?, ?> kfflags) flags.replace(kfflags);
        HookEvents.Envelope envelope = HookEvents.Envelope.of(ce, deliveryContext(contextHeader, proofHeader));
        String event = envelope.event();

        HookResult result;
        try {
            result = dispatch(event, envelope, data);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "KF SDK: handler for " + event + " failed", e);
            boolean patchOnDelete = e instanceof PatchNotAllowedException;
            result = problem(500, "handler-failed", "Handler failed",
                    "handler failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    patchOnDelete ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT, Map.of());
        }
        notifyObservers(event, envelope, data, result);
        return result;
    }

    /** The context of an after delivery, or null when absent or unreadable (logged, never refused). */
    private static HookEvents.DeliveryContext deliveryContext(String contextHeader, String proofHeader) {
        if (contextHeader == null || contextHeader.isBlank()) return null;
        if (proofHeader == null || proofHeader.isBlank()) {
            LOG.log(System.Logger.Level.WARNING, "KF SDK: the delivery carried " + RequestContext.CONTEXT_HEADER
                    + " without " + RequestContext.PROOF_HEADER + "; no delivery context for the handler");
            return null;
        }
        try {
            return HookEvents.DeliveryContext.of(contextHeader, proofHeader);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "KF SDK: the delivery context cannot be read ({0}); no delivery "
                    + "context for the handler", e.getMessage());
            return null;
        }
    }

    /** Whether {@code kfenvelopeversion} (text, e.g. {@code "1"} or {@code "1.3"}) has a supported major. */
    static boolean supportedEnvelope(Object version) {
        if (!(version instanceof String text) || text.isBlank()) return false;
        int dot = text.indexOf('.');
        String major = dot < 0 ? text : text.substring(0, dot);
        if (major.isEmpty() || !major.chars().allMatch(c -> c >= '0' && c <= '9')) return false;
        try {
            return SdkVersion.SUPPORTED_ENVELOPE_MAJORS.contains(Integer.parseInt(major));
        } catch (NumberFormatException tooLong) {
            return false;
        }
    }

    /**
     * A patch on {@code model.before.delete} / {@code order.before.delete}, or a patch of an order's core field: the
     * module's own bug, answered 500 {@code permanent}.
     */
    static final class PatchNotAllowedException extends IllegalStateException {
        PatchNotAllowedException(String event) {
            super("a patch is not allowed on " + event + "; answer allow or reject");
        }

        PatchNotAllowedException(String event, java.util.Set<String> coreFields) {
            super("a patch on " + event + " may carry only extensions of this module (the order's core fields are "
                    + "patch: []); it names " + String.join(", ", new java.util.TreeSet<>(coreFields)));
        }
    }

    private HookResult dispatch(String event, HookEvents.Envelope envelope, Map<String, Object> data) throws Exception {
        ModelHooks hooks = event.startsWith("order.") ? orders : models;
        if (event.startsWith("model.before.") || event.startsWith("order.before.")) {
            BeforeContext ctx = new BeforeContext(slug, event, envelope, data);
            Decision.Patch patch = null;
            for (ModelHooks.Before b : hooks.before.getOrDefault(event, List.of())) {
                Decision d = b.handler().handle(ctx);
                if (d instanceof Decision.Reject) return ok(d.toJson());
                if (d instanceof Decision.Patch p && !p.isEmpty()) patch = patch == null ? p : patch.merge(p);
            }
            if (patch != null && event.endsWith(".before.delete")) throw new PatchNotAllowedException(event);
            if (patch != null && hooks == orders) {
                // protocol 2.0 (§16.2): the order's core fields carry patch: [] -- only extensions.{own slug}
                java.util.Set<String> core = new java.util.HashSet<>(patch.values().keySet());
                core.remove("extensions");
                if (!core.isEmpty()) throw new PatchNotAllowedException(event, core);
            }
            return ok(patch == null ? new Decision.Allow().toJson() : patch.toJson());
        }
        if (event.equals(HookEvents.FIELD_CHANGED) || event.equals(HookEvents.ORDER_FIELD_CHANGED)) {
            FieldChange change = FieldChange.of(envelope, data);
            for (ModelHooks.FieldWatch w : hooks.fieldWatches) {
                if (w.matches(change.field())) w.handler().handle(change);
            }
            return ok(Map.of("ok", true));
        }
        if (FlowEvents.TYPES.contains(event)) {
            // protocol 2.0 (§16.3): the events of the flow engine
            List<FlowEvents.FlowEventHandler> subscribed = flows.handlers(event);
            if (subscribed.isEmpty()) return ok(Map.of("ok", true, "ignored", event));
            FlowEvents.FlowEvent ev;
            try {
                ev = FlowEvents.parse(envelope, data);
            } catch (IllegalArgumentException e) {
                return problem(400, "invalid-envelope", "Invalid envelope", e.getMessage(), ErrorClass.PERMANENT,
                        Map.of());
            }
            for (FlowEvents.FlowEventHandler h : subscribed) h.handle(ev);
            return ok(Map.of("ok", true));
        }
        if (HookEvents.TENANT_POINTS.contains(event)) {
            String entity = data.get("entity") instanceof String e ? e : null;
            String tenantId = data.get("id") == null ? null : String.valueOf(data.get("id"));
            if (!"tenant".equals(entity) || tenantId == null || tenantId.isBlank()) {
                return problem(400, "invalid-envelope", "Invalid envelope", event
                        + " needs data.entity = tenant and data.id", ErrorClass.PERMANENT, Map.of());
            }
            if (event.equals(HookEvents.TENANT_AFTER_DELETE)) {
                if (tenants.deleted.isEmpty()) return ok(Map.of("ok", true, "ignored", event));
                HookEvents.TenantDeleted deleted = new HookEvents.TenantDeleted(tenantId, envelope);
                for (HookEvents.TenantDeletedHandler h : tenants.deleted) h.handle(deleted);
            } else if (event.equals(HookEvents.TENANT_AFTER_SOFT_DELETE)) {
                if (tenants.softDeleted.isEmpty()) return ok(Map.of("ok", true, "ignored", event));
                HookEvents.TenantSoftDeleted softDeleted = new HookEvents.TenantSoftDeleted(tenantId, envelope);
                for (HookEvents.TenantSoftDeletedHandler h : tenants.softDeleted) h.handle(softDeleted);
            } else {
                if (tenants.restored.isEmpty()) return ok(Map.of("ok", true, "ignored", event));
                HookEvents.TenantRestored restored = new HookEvents.TenantRestored(tenantId, envelope);
                for (HookEvents.TenantRestoredHandler h : tenants.restored) h.handle(restored);
            }
            return ok(Map.of("ok", true));
        }
        if (event.startsWith(HookEvents.MODULE_EVENT_PREFIX)) {
            java.util.regex.Matcher point = HookEvents.MODULE_EVENT_POINT.matcher(event);
            List<HookEvents.ModuleEventHandler> subscribed = point.matches() ? events.handlers(event) : List.of();
            if (subscribed.isEmpty()) return ok(Map.of("ok", true, "ignored", event));
            HookEvents.ModuleEvent ev = new HookEvents.ModuleEvent(point.group(1), point.group(2), data, envelope);
            for (HookEvents.ModuleEventHandler h : subscribed) h.handle(ev);
            return ok(Map.of("ok", true));
        }
        List<AfterHandler> handlers = hooks.after.get(event);
        if (handlers != null) {
            AfterEvent ev = AfterEvent.of(event, envelope, data);
            for (AfterHandler h : handlers) h.handle(ev);
            return ok(Map.of("ok", true));
        }
        return ok(Map.of("ok", true, "ignored", event));
    }

    // ---- the module's own routes -------------------------------------------------------

    /**
     * Verifies the signed context of a request the platform forwards to one of the module's own routes
     * ({@code X-KF-Context}, {@code X-KF-Timestamp}, {@code X-KF-Signature}) with this module's
     * {@code KF_HOOK_SECRET}, now. The adapters ({@link KfHttpServer#route}, the Spring configuration) call it.
     *
     * @throws RequestContext.Rejected (401, {@code permanent}) for a missing, altered, expired or incoherent context
     */
    public RequestContext verifyContext(String contextHeader, String timestampHeader, String signatureHeader, byte[] body) {
        return verifyContext(contextHeader, timestampHeader, signatureHeader, null, body);
    }

    /**
     * {@link #verifyContext(String, String, String, byte[])} that also keeps the platform's proof of the context
     * ({@code X-KF-Context-Proof}), for {@link AuditClient}. The adapters call this one.
     *
     * @param proofHeader {@code X-KF-Context-Proof} as received; null when the platform sent none
     */
    public RequestContext verifyContext(String contextHeader, String timestampHeader, String signatureHeader,
                                        String proofHeader, byte[] body) {
        RequestContext ctx = RequestContext.verify(contextHeader, timestampHeader, signatureHeader, body, hookSecret,
                Instant.now().getEpochSecond());
        forwardedContexts.put(ctx.requestId(), new ForwardedContext(contextHeader.trim(),
                proofHeader == null || proofHeader.isBlank() ? null : proofHeader.trim(), ctx.ts()));
        return ctx;
    }

    /** The raw context and proof of a context this instance verified within ±300 s; null otherwise. */
    ForwardedContext forwardedContext(RequestContext ctx) {
        if (ctx == null || ctx.requestId() == null) return null;
        ForwardedContext forwarded = forwardedContexts.get(ctx.requestId());
        if (forwarded == null || Math.abs(Instant.now().getEpochSecond() - forwarded.ts()) > Signatures.MAX_SKEW_SECONDS) {
            return null;
        }
        return forwarded;
    }

    /** The platform base URL, the slug and the token, for {@link AuditClient} and {@link Notifications} (never logged). */
    String platformUrl() {
        return platformUrl;
    }

    String token() {
        return token;
    }

    /**
     * Paths of the module served WITHOUT a platform context: the hook callback ({@link #hookPath()}), the UI assets
     * ({@code /ui/**}, fetched by the platform's asset proxy) and these prefixes (default {@code /health}).
     */
    public KfModule publicPath(String prefix) {
        publicPaths.add(Objects.requireNonNull(prefix, "prefix"));
        return this;
    }

    /** Whether a request path is served without a platform context (see {@link #publicPath}). */
    public boolean isPublicPath(String path) {
        if (path == null) return false;
        if (path.equals(hookPath) || path.startsWith("/ui/")) return true;
        for (String prefix : publicPaths) {
            if (path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) return true;
        }
        return false;
    }

    private void notifyObservers(String event, HookEvents.Envelope envelope, Map<String, Object> data, HookResult result) {
        if (observers.isEmpty()) return;
        CallbackRecord record = new CallbackRecord(Instant.now().toString(), event,
                envelope.eventId() != null ? envelope.eventId() : envelope.deliveryId(),
                data.get("id") == null ? null : String.valueOf(data.get("id")), HookEvents.stringList(data.get("changed")),
                data.get("field") == null ? null : String.valueOf(data.get("field")), result.status(), parse(result.body()),
                flags.all(), envelope.tenant(), envelope.locale());
        for (Consumer<CallbackRecord> o : observers) {
            try {
                o.accept(record);
            } catch (RuntimeException ignored) {
                // observers must not break callbacks
            }
        }
    }

    private Object parse(String body) {
        try {
            return json.readValue(body, Object.class);
        } catch (RuntimeException e) {
            return body;
        }
    }

    private HookResult ok(Map<String, Object> body) {
        return new HookResult(200, json.writeValueAsString(body), HookResult.CONTENT_TYPE);
    }

    /** An RFC 9457 answer with its {@code class}. */
    private HookResult problem(int status, String type, String title, String detail, ErrorClass errorClass,
                               Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://kf.ro/problems/" + type);
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put(ErrorClass.PROPERTY, errorClass.wire());
        body.putAll(extra);
        return new HookResult(status, json.writeValueAsString(body), HookResult.PROBLEM_CONTENT_TYPE);
    }
}
