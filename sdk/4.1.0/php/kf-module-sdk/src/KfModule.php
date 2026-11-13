<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/KfModule.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\Decision\Allow;
use Kf\Sdk\Decision\Patch;
use Kf\Sdk\Decision\Reject;
use Kf\Sdk\HookEvents\AfterEvent;
use Kf\Sdk\HookEvents\DeliveryContext;
use Kf\Sdk\HookEvents\Envelope;
use Kf\Sdk\HookEvents\ModuleEvent;
use Kf\Sdk\HookEvents\FieldChange;
use Kf\Sdk\HookEvents\TenantDeleted;
use Kf\Sdk\HookEvents\TenantRestored;
use Kf\Sdk\HookEvents\TenantSoftDeleted;
use Kf\Sdk\FlowEvents\FlowEvent;

/**
 * Entry point of the KF module SDK.
 *
 *   // kf.php: the module definition, shared by bin/kf-register (CLI, at container start) and public/index.php (HTTP)
 *   $kf = KfModule::fromEnv();
 *   $kf->models()
 *       ->beforeCreate(fn (BeforeContext $ctx) => str_contains($ctx->after?->name ?? '', 'x') ? $ctx->reject('veto.no-x') : $ctx->allow())
 *       ->beforeUpdate(fn (BeforeContext $ctx) => $ctx->allow(), priority: 50, onFailure: OnFailure::BLOCK)  // block needs the manifest capability
 *       ->afterUpdate(fn (HookEvents\AfterEvent $e) => error_log("updated {$e->id}"));
 *   $kf->registerField('order', 'priority_code', FieldType::STRING, 'orders_extra', 'field.priority'); // label = i18n key
 *   $kf->ui()->contribute('models.form.edit.fields.after',
 *       Surface::react('/m/my-mod/ui/surfaces.mjs', 'MyFields')->css('/m/my-mod/ui/surfaces.css')->order(10));
 *   return $kf;
 *
 *   // bin/kf-register kf.php: checks kf-module-conf.json locally, then registers, retrying transient errors only
 *   // public/index.php: Sapi::handleGlobals($kf) on POST /_kf/hooks, Sapi::serveUi() on /ui/**, Sapi::verifyGlobals($kf)
 *   //                   on the module's own routes
 *
 * PHP has no long-lived process: the Java SDK's background registration thread is `registerWithRetry()`, run once by
 * `bin/kf-register` at container start; what must outlive a request (registration state, flags) lives in StateStore.
 *
 * Environment (injected by the platform): KF_PLATFORM_URL, KF_MODULE_SLUG, KF_MODULE_TOKEN, KF_HOOK_SECRET, and
 * KF_CALLBACK_BASE for exposed services. KF_MODULE_MANIFEST (default `./kf-module-conf.json`) is the manifest the SDK
 * checks the registration against before sending it (ManifestCheck); without it the local check is skipped with a
 * warning. Optional: KF_SDK_STATE_DIR.
 *
 * Version: `SdkVersion::sdkVersion()`, manifest `schemaVersion` `SdkVersion::PROTOCOL_SCHEMA_VERSION`.
 */
final class KfModule
{
    public const DEFAULT_HOOK_PATH = '/_kf/hooks';
    /** Where fromEnv() looks for the manifest when KF_MODULE_MANIFEST is not set. */
    public const DEFAULT_MANIFEST = './kf-module-conf.json';
    /** The registration retry schedule in milliseconds (transient errors only); the last step repeats. */
    public const DEFAULT_RETRY_DELAYS = [1000, 2000, 5000, 10000, 15000, 30000];

    private readonly AuditClient $audit;
    private readonly ReadsClient $reads;
    private readonly SettingsClient $settings;
    private readonly SecretsClient $secrets;
    private readonly ExtensionsClient $extensions;
    private readonly Notifications $notifications;
    private readonly ModelHooks $models;
    private readonly ModelHooks $orders;
    private readonly FlowEvents $flows;
    private readonly OrderServices $orderServices;
    private readonly TenantHooks $tenants;
    private readonly StorageClient $storage;
    private readonly PeersClient $peers;
    private readonly EventsClient $events;
    private readonly UiContributions $ui;
    private readonly FeatureFlags $flags;
    private readonly StateStore $state;
    /** @var list<array<string, string>> */
    private array $fields = [];
    /** @var list<callable> */
    private array $observers = [];
    /** @var list<string> */
    private array $publicPaths = ['/health'];
    /** @var list<int> */
    private array $retryDelays = self::DEFAULT_RETRY_DELAYS;
    private bool $registered = false;
    private bool $registrationStopped = false;
    private bool $manifestChecked = false;
    private ?PlatformProblem $lastRegistrationProblem = null;
    private ?string $lastRegistrationError = null;
    private ?array $lastRegistrationSummary = null;
    private ?string $manifestJson = null;
    private ?string $manifestSource = null;

    /** How many verified contexts are kept for AuditClient (the oldest go first). */
    public const MAX_FORWARDED_CONTEXTS = 4096;

    /**
     * The raw `X-KF-Context` and the platform's `X-KF-Context-Proof` of every context this instance verified, by
     * `requestId`: AuditClient sends them back, unchanged, with the module token. Bounded; an entry is used
     * only while its `ts` is inside the ±300 s window.
     *
     * @var array<string, array{contextHeader: string, proof: ?string, ts: int}>
     */
    private array $forwardedContexts = [];

    private function __construct(
        private readonly ?string $platformUrl,
        private readonly ?string $slug,
        private readonly ?string $token,
        private readonly ?string $hookSecret,
        private readonly ?string $callbackBase,
        private readonly string $hookPath,
        ?string $stateDir,
    ) {
        $this->audit = new AuditClient($this);
        $this->reads = new ReadsClient($this);
        $this->settings = new SettingsClient($this);
        $this->secrets = new SecretsClient($this);
        $this->extensions = new ExtensionsClient($this);
        $this->notifications = new Notifications($this);
        $this->models = new ModelHooks();
        $this->orders = new ModelHooks('order');
        $this->flows = new FlowEvents();
        $this->orderServices = new OrderServices($this);
        $this->tenants = new TenantHooks();
        $this->storage = new StorageClient($this);
        $this->peers = new PeersClient($this);
        $this->events = new EventsClient($this);
        $this->ui = new UiContributions();
        $this->state = new StateStore($stateDir ?? sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'kf-sdk-' . ($slug ?? 'module'));
        $this->flags = new FeatureFlags(function (): ?array {
            $stored = $this->state->read('flags');
            return is_array($stored) && is_array($stored['values'] ?? null) ? $stored['values'] : null;
        });
    }

    /**
     * Reads the KF_* environment variables injected by the platform.
     *
     * @param ?array<string, string> $env defaults to getenv()
     */
    public static function fromEnv(?array $env = null): self
    {
        $env ??= getenv();
        $get = static fn (string $k): ?string => isset($env[$k]) && $env[$k] !== '' ? (string) $env[$k] : null;
        $kf = new self(
            $get('KF_PLATFORM_URL') === null ? null : rtrim($get('KF_PLATFORM_URL'), '/'),
            $get('KF_MODULE_SLUG'),
            $get('KF_MODULE_TOKEN'),
            $get('KF_HOOK_SECRET'),
            $get('KF_CALLBACK_BASE') === null ? null : rtrim($get('KF_CALLBACK_BASE'), '/'),
            self::DEFAULT_HOOK_PATH,
            $get('KF_SDK_STATE_DIR'),
        );
        $location = $get('KF_MODULE_MANIFEST') ?? self::DEFAULT_MANIFEST;
        if (is_file($location)) {
            $json = @file_get_contents($location);
            if ($json === false) {
                Log::warn("KF SDK: the manifest $location cannot be read; the local check is skipped");
            } else {
                $kf->manifestJson = $json;
                $kf->manifestSource = $location;
            }
        } else {
            Log::warn("KF SDK: no manifest at $location (KF_MODULE_MANIFEST); the registration is not checked locally, only by the platform");
        }
        return $kf;
    }

    /** Explicit configuration (tests, local runs). */
    public static function of(?string $platformUrl, ?string $slug, ?string $token, ?string $hookSecret, ?string $callbackBase,
        ?string $stateDir = null): self
    {
        $blank = static fn (?string $s): ?string => $s === null || $s === '' ? null : $s;
        return new self($blank($platformUrl) === null ? null : rtrim($platformUrl, '/'), $blank($slug), $blank($token),
            $blank($hookSecret), $blank($callbackBase) === null ? null : rtrim($callbackBase, '/'), self::DEFAULT_HOOK_PATH, $stateDir);
    }

    /**
     * The manifest the registration is checked against before it is sent (ManifestCheck); replaces the one fromEnv()
     * read. null disables the local check.
     */
    public function withManifest(?string $manifestJson): self
    {
        $this->manifestJson = $manifestJson;
        $this->manifestSource = $manifestJson === null ? null : 'kf-module-conf.json (given)';
        return $this;
    }

    /** The manifest the SDK read, typed, or null when there is none. Never throws: a broken manifest is null here. */
    public function manifest(): ?Manifest
    {
        if ($this->manifestJson === null) return null;
        try {
            return Manifest::parse($this->manifestJson);
        } catch (\InvalidArgumentException) {
            return null;
        }
    }

    // ---- declaration ------------------------------------------------------------------------

    public function models(): ModelHooks
    {
        return $this->models;
    }

    /**
     * Hooks on the platform's orders (protocol 2.0, sdk-protocol.md §16.2; the points `order.*`, public): the shape of models() -- `$kf->orders()->beforeUpdate(fn ($ctx) => …)`,
     * `$kf->orders()->onFieldChange(HookEvents::ORDER_PATH_STATUS, fn ($change) => …)`. A patch carries only the module's
     * own `extensions` (the order's core fields are `patch: []`): a patch that names a core field is answered
     * `500 permanent`, the module's bug.
     */
    public function orders(): ModelHooks
    {
        return $this->orders;
    }

    /**
     * The events of the flow engine (protocol 2.0, sdk-protocol.md §16.3): `$kf->flows()->on(FlowEvents::STEP_ENTERED,
     * fn ($e) => …)`; delivered by the platform with `FLOW_EVENTS`, used only when the platform announces it
     * (FlowEvents).
     */
    public function flows(): FlowEvents
    {
        return $this->flows;
    }

    /**
     * The module's service lines on an order (protocol 2.0, `order.services.v1`, sdk-protocol.md §16.6):
     * `$kf->orderServices()->upsert($ctx, $line)`, `delete($ctx, $orderId, $externalRef)`; delivered by the platform
     * with `ORDER_SERVICES`, used only when the platform announces it (OrderServices).
     */
    public function orderServices(): OrderServices
    {
        return $this->orderServices;
    }

    /** UI contributions to the host's frontend contact surfaces (sent with the registration). */
    public function ui(): UiContributions
    {
        return $this->ui;
    }

    /** The module's feature flags: `$kf->flags()->isEnabled('key')` (unknown keys are off). */
    public function flags(): FeatureFlags
    {
        return $this->flags;
    }

    /**
     * The module's access to the platform's audit trail (capability `audit`; implemented by the platform
     *): `$kf->audit()->record($ctx, new AuditRecord('NOTE_CREATED', 'Note', '42'))` inside a route handler,
     * with the RequestContext the adapter verified (AuditClient).
     */
    public function audit(): AuditClient
    {
        return $this->audit;
    }

    /**
     * The module's reads of platform objects (capability `reads`, protocol 1.1 §15.1):
     * `$kf->reads()->list($ctx, 'order.v1', ['modelVersionId' => '17'], 0, 50)` and `$kf->reads()->get($ctx, 'model.v1', '42')`
     * inside a route or hook handler, with the RequestContext the adapter verified (ReadsClient).
     */
    public function reads(): ReadsClient
    {
        return $this->reads;
    }

    /**
     * The module's settings in the context's tenant (capability `settings`, protocol 1.1 §15.2):
     * `$kf->settings()->effective($ctx)`, typed by the manifest (SettingsClient).
     */
    public function settings(): SettingsClient
    {
        return $this->settings;
    }

    /**
     * The module's secrets (capability `secrets`, protocol 1.1 §15.3): `$kf->secrets()->write($ctx, $name, $value)`
     * → a `vault://` reference, `$kf->secrets()->resolve($ctx, $ref)` → the value; a value is never logged (SecretsClient).
     */
    public function secrets(): SecretsClient
    {
        return $this->secrets;
    }

    /**
     * The module's files through the platform (protocol 1.2 §3.5, capability `storage`):
     * `$kf->storage()->uploadUrl($ctx, $contentType, $size)` → a presigned `PUT` under a key the platform chooses,
     * `$kf->storage()->downloadUrl($ctx, $key)` → a presigned `GET` (StorageClient).
     */
    public function storage(): StorageClient
    {
        return $this->storage;
    }

    /**
     * The module's reads and commands of another module, through the platform (protocol 2.0, sdk-protocol.md §15.9):
     * `$kf->peers()->read($ctx, 'kf-stock', 'm.kf-stock.level.v1', [])` and
     * `$kf->peers()->command($ctx, 'kf-stock', 'reserve', PeersClient::newIdempotencyKey(), $body)`, with the
     * RequestContext the adapter verified or, for a background job, an after delivery's DeliveryContext (PeersClient).
     */
    public function peers(): PeersClient
    {
        return $this->peers;
    }

    /**
     * The module events `m.{owner}.after.{name}` (protocol 2.0, sdk-protocol.md §6.3):
     * `$kf->events()->emit($ctx, 'level-changed', $data)` for the module's own, `$kf->events()->on($owner, $name,
     * $handler)` to subscribe to another module's (EventsClient).
     */
    public function events(): EventsClient
    {
        return $this->events;
    }

    /**
     * Hooks on the platform's tenants (protocol 1.2 §6.1): `$kf->tenants()->onDeleted(fn ($d) => …)` receives
     * `tenant.after.delete` (TenantHooks).
     */
    public function tenants(): TenantHooks
    {
        return $this->tenants;
    }

    /**
     * The module's own extension values on platform objects (protocol 1.1 §15.4):
     * `$kf->extensions()->write($ctx, 'model_version', '17', $values)` sends exactly the given fields (ExtensionsClient).
     */
    public function extensions(): ExtensionsClient
    {
        return $this->extensions;
    }

    /**
     * The notifications the module declared in `capabilities.notifications` (sdk-protocol.md §11.1):
     * `$kf->notifications()->send($tenantId, 'author-changed', [$subject], ['modelName' => 'X'])` (Notifications).
     */
    public function notifications(): Notifications
    {
        return $this->notifications;
    }

    /**
     * Declares an extension field of an extensible entity of the platform (`model`, `order`, `customer`),
     * stored in the real column `$name` of the table `m_{slug}_{table}` (created by the platform). Values appear under
     * `extensions.{slug}.{name}` of that entity.
     *
     * The field must be declared in `capabilities.fields` of `kf-module-conf.json` with the same entity, name, type and
     * table; the label is a translation KEY of `i18n.en`, or null for the field's name.
     */
    public function registerField(string $entity, string $name, FieldType $type, string $table, ?string $labelKey = null): self
    {
        $f = ['entity' => $entity, 'name' => $name, 'type' => $type->value, 'table' => $table];
        if ($labelKey !== null) $f['label'] = $labelKey;
        $this->fields[] = $f;
        return $this;
    }

    /** Observer called for every verified callback (e.g. to keep a debug log of what was received). */
    public function onCallback(callable $observer): self
    {
        $this->observers[] = $observer;
        return $this;
    }

    public function slug(): ?string
    {
        return $this->slug;
    }

    public function hookPath(): string
    {
        return $this->hookPath;
    }

    public function stateDir(): string
    {
        return $this->state->dir();
    }

    /** True once a registration got a 200 (in this process or in `bin/kf-register` of this container). */
    public function isRegistered(): bool
    {
        return $this->registered || (($this->state->read('registration')['registered'] ?? false) === true);
    }

    /** The last registration failure as one line (status, class, `detail`; never the token), or null. */
    public function lastRegistrationError(): ?string
    {
        if ($this->lastRegistrationError !== null || $this->lastRegistrationProblem !== null || $this->registered) {
            return $this->lastRegistrationError;
        }
        $stored = $this->state->read('registration');
        return is_array($stored) && is_string($stored['error'] ?? null) ? $stored['error'] : null;
    }

    /** The last registration failure as read from the platform's answer (this process), or null. */
    public function lastRegistrationProblem(): ?PlatformProblem
    {
        return $this->lastRegistrationProblem;
    }

    /**
     * True once the registration stopped on a permanent error (a wrong token, a module in the wrong state, a refused
     * registration, a manifest the local check refuses): the SDK does not retry it; the module must be fixed and
     * restarted.
     */
    public function isRegistrationStopped(): bool
    {
        return $this->registrationStopped || (($this->state->read('registration')['stopped'] ?? false) === true && !$this->registered);
    }

    /**
     * Replaces the retry schedule of the registration in milliseconds (transient errors only; the last step repeats).
     * The default is DEFAULT_RETRY_DELAYS; a `Retry-After` of the platform is honoured up to the longest step.
     *
     * @param list<int> $delaysMs
     */
    public function withRetryDelays(array $delaysMs): self
    {
        if ($delaysMs === [] || array_filter($delaysMs, static fn ($d) => !is_int($d) || $d < 0) !== []) {
            throw new \InvalidArgumentException('retry delays must be a non-empty list of non-negative durations');
        }
        $this->retryDelays = array_values($delaysMs);
        return $this;
    }

    public function lastRegistrationSummary(): ?array
    {
        return $this->lastRegistrationSummary;
    }

    /** The body of `PUT /api/v1/platform/modules/{slug}/registrations`. */
    /**
     * The warnings of the last successful registration (`warnings[]` of the platform's answer): among them for a
     * contribution to a slot its owner does not offer (yet) — stored inactive, not served until it does
     * (sdk-protocol.md §7.12). Empty before the first registration. Each one is also logged at WARNING.
     *
     * @return list<string>
     */
    public function registrationWarnings(): array
    {
        $summary = $this->lastRegistrationSummary;
        if ($summary === null || !is_array($summary['warnings'] ?? null)) return [];
        return array_values(array_filter($summary['warnings'], 'is_string'));
    }

    public function registrationBody(): array
    {
        return [
            'callbackUrl' => $this->callbackBase === null ? null : $this->callbackBase . $this->hookPath,
            'hooks' => [...$this->models->registration(), ...$this->orders->registration(), ...$this->flows->registration(),
                ...$this->tenants->registration(), ...$this->events->registration()],
            'fields' => $this->fields,
            'surfaces' => $this->ui->registration(),
        ];
    }

    // ---- registration -----------------------------------------------------------------------

    /**
     * Registers, retrying (1 s … 30 s) only **transient** errors -- network errors, 503 during the first install, any
     * answer with `class: transient` (ErrorClass); a permanent error (401, 403, 409, 422, an unknown class) stops it with
     * one ERROR log naming the status and the `detail` (`isRegistrationStopped()`, `lastRegistrationError()`) and is
     * thrown. Blocking: the PHP twin of the Java SDK's background thread, run by `bin/kf-register`. `$maxAttempts` null =
     * until it succeeds or stops; when the attempts run out the last (transient) error is thrown.
     *
     * Before the first request the manifest is checked locally (ManifestCheck): a violation is permanent, nothing is sent.
     *
     * @return array the platform's summary `{slug, callbackUrl, registeredAt, hooks, fieldsCreated, ..., warnings?}`
     * @throws KfException
     */
    public function registerWithRetry(?int $maxAttempts = null): array
    {
        $this->checkManifest();
        $this->requireConfig();
        $this->saveState(false, false, 'registering', null);   // a restarted container must not show the previous run
        $delays = $this->retryDelays;
        for ($attempt = 0; ; $attempt++) {
            try {
                return $this->attempt();
            } catch (KfException $e) {
                $problem = $e->problem();
                if (!$problem->retryable()) {
                    // a permanent error does not fix itself; retrying it forever only hides it.
                    $this->registrationStopped = true;
                    $this->saveState(false, true, $this->lastRegistrationError, null);
                    Log::error("KF SDK: registration of {$this->slug} refused permanently ({$this->lastRegistrationError}); not retrying. "
                        . 'Fix the cause and restart the module.');
                    throw $e;
                }
                if ($maxAttempts !== null && $attempt + 1 >= $maxAttempts) throw $e;
                $delay = $delays[min($attempt, count($delays) - 1)];
                if ($problem->retryAfterSeconds !== null) {
                    // Retry-After paces the retries (503 during the first install), never beyond the schedule's longest step
                    $asked = $problem->retryAfterSeconds * 1000;
                    $longest = $delays[count($delays) - 1];
                    if ($asked > $delay) $delay = min($asked, $longest);
                }
                Log::warn("KF SDK: registration of {$this->slug} failed ({$this->lastRegistrationError}), retrying in $delay ms");
                usleep($delay * 1000);
            }
        }
    }

    /**
     * One registration attempt (`bin/kf-register --once`), after the local manifest check: on 200 the state is stored and
     * the flags are fetched; any other answer is thrown with its class.
     *
     * @throws KfException
     */
    public function register(): array
    {
        $this->checkManifest();
        $this->requireConfig();
        try {
            return $this->attempt();
        } catch (KfException $e) {
            if (!$e->problem()->retryable()) $this->registrationStopped = true;
            $this->saveState(false, $this->registrationStopped, $this->lastRegistrationError, null);
            throw $e;
        }
    }

    private function requireConfig(): void
    {
        if ($this->platformUrl === null || $this->slug === null || $this->token === null) {
            $problem = PlatformProblem::local('KF_PLATFORM_URL / KF_MODULE_SLUG / KF_MODULE_TOKEN not set, not registering (running outside the platform?)');
            Log::warn('KF SDK: ' . $problem->detail);
            throw new KfException($problem);
        }
    }

    /**
     * The local check of the manifest and of the registration against it, before the first request. A violation is
     * permanent: nothing is sent and nothing is retried.
     *
     * @throws KfException
     */
    private function checkManifest(): void
    {
        if ($this->manifestChecked) return;
        $this->manifestChecked = true;
        if ($this->manifestJson === null) return;
        $result = ManifestCheck::check($this->manifestJson, $this->registrationBody(), []);
        foreach ($result->warnings as $warning) Log::warn("KF SDK: manifest {$this->manifestSource}: $warning");
        if ($result->ok()) return;
        $problem = PlatformProblem::local("the registration of {$this->slug} was refused by the local check of "
            . "{$this->manifestSource}: " . implode('; ', $result->errors));
        $this->lastRegistrationProblem = $problem;
        $this->lastRegistrationError = $problem->describe();
        $this->registrationStopped = true;
        $this->saveState(false, true, $this->lastRegistrationError, null);
        Log::error("KF SDK: {$this->lastRegistrationError}; nothing was sent. Fix kf-module-conf.json or the registration and restart the module.");
        throw new KfException($problem);
    }

    /** @throws KfException */
    private function attempt(): array
    {
        $url = $this->platformUrl . '/api/v1/platform/modules/' . $this->slug . '/registrations';
        try {
            [$status, $body, $headers] = Http::request('PUT', $url, [
                'Authorization' => 'Bearer ' . $this->token,
                'Content-Type' => 'application/json',
                'Accept' => 'application/json',
            ], Json::encode($this->registrationBody()));
        } catch (\RuntimeException $e) {
            // describe() walks the cause chain; PHP's stream wrapper names the cause in the message
            $problem = PlatformProblem::network(self::describe($e));
            $this->lastRegistrationProblem = $problem;
            $this->lastRegistrationError = $problem->describe();
            throw new KfException($problem, $e);
        }
        if ($status === 200) {
            $this->registered = true;
            $this->lastRegistrationError = null;
            try {
                $summary = json_decode($body, true, 512, JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                $summary = [];
            }
            $summary = is_array($summary) ? $summary : [];
            $this->lastRegistrationSummary = $summary;
            $this->saveState(true, false, null, $summary);
            Log::info("KF SDK: module {$this->slug} registered: $body");
            foreach ((array) ($summary['warnings'] ?? []) as $w) Log::warn('KF SDK: registration warning: ' . Json::text($w));
            $this->fetchFlags();
            return $summary;
        }
        $problem = PlatformProblem::parse($status, $body, $headers['retry-after'] ?? null);
        $this->lastRegistrationProblem = $problem;
        $this->lastRegistrationError = $problem->describe();
        throw new KfException($problem);
    }

    private function saveState(bool $registered, bool $stopped, ?string $error, ?array $summary): void
    {
        $this->state->write('registration', ['registered' => $registered, 'stopped' => $stopped, 'at' => gmdate('c'),
            'error' => $error, 'summary' => $summary === null ? null : Json::obj($summary)]);
    }

    /**
     * One line for a failed platform call, cause chain included: `RuntimeException: file_get_contents(...): Failed to
     * open stream: Connection refused`.
     *
     * A null or blank message is never printed (the class name stands alone); a cause that renders like the previous
     * one adds nothing and is skipped; at most three causes, and a cyclic chain stops. Only the throwables are read, never
     * the request, so neither the token nor the hook secret can reach the line.
     */
    public static function describe(\Throwable $error): string
    {
        $line = self::render($error);
        $seen = [spl_object_id($error) => true];
        $causes = [];
        $previous = self::render($error);
        for ($cause = $error->getPrevious(); $cause !== null && count($causes) < 3 && !isset($seen[spl_object_id($cause)]); $cause = $cause->getPrevious()) {
            $seen[spl_object_id($cause)] = true;
            $rendered = self::render($cause);
            if ($rendered !== $previous) $causes[] = $rendered;
            $previous = $rendered;
        }
        return $causes === [] ? $line : $line . ' (cause: ' . implode('; ', $causes) . ')';
    }

    private static function render(\Throwable $t): string
    {
        $name = substr(strrchr('\\' . $t::class, '\\'), 1);
        $message = $t->getMessage();
        return trim($message) === '' ? $name : $name . ': ' . $message;
    }

    /** GET /api/v1/platform/modules/{slug}/flags with the module token, once after registering (best effort). */
    private function fetchFlags(): void
    {
        try {
            [$status, $body] = Http::request('GET', $this->platformUrl . '/api/v1/platform/modules/' . $this->slug . '/flags', [
                'Authorization' => 'Bearer ' . $this->token,
                'Accept' => 'application/json',
            ]);
            if ($status !== 200) {
                Log::warn("KF SDK: reading the feature flags failed: HTTP $status");
                return;
            }
            $values = [];
            foreach ((array) json_decode($body, true) as $f) {
                if (is_array($f) && isset($f['key'])) $values[(string) $f['key']] = $f['enabled'] ?? null;
            }
            $this->flags->replace($values);
            $this->state->write('flags', ['at' => time(), 'values' => Json::obj($this->flags->all())]);
            Log::info('KF SDK: feature flags ' . Json::encode(Json::obj($this->flags->all())));
        } catch (\Throwable $e) {
            Log::warn('KF SDK: reading the feature flags failed: ' . $e->getMessage());
        }
    }

    // ---- callbacks --------------------------------------------------------------------------

    /**
     * Handles one platform callback (`POST /_kf/hooks`) and returns what to answer. In this order:
     * 1. the signature on the raw body (Signatures, ±300 s): 401;
     * 2. the body is a CloudEvent JSON object: 400;
     * 3. the envelope version (`kfenvelopeversion`, rule): a major outside SdkVersion::SUPPORTED_ENVELOPE_MAJORS,
     *    a missing or a non-numeric value: 422 `https://kf.ro/problems/unsupported-envelope-version`, `class: permanent`,
     *    with `received` and `supported`; no handler is called;
     * 4. the handlers: 200 with the decision (before) or an ack (after / field change); 500 when a handler throws
     *    (`class: transient`) or answers a patch on delete (`class: permanent`).
     *
     * Every refusal is an RFC 9457 body (`application/problem+json`) with its `class`.
     *
     * With the context the platform puts on an after delivery (protocol 2.0, sdk-protocol.md §5.5) --
     * `X-KF-Context` and `X-KF-Context-Proof`, the named `$contextHeader` and `$proofHeader` -- the handler sees it as
     * Envelope::deliveryContext(), for a background job. The adapters pass both. Both null (a before hook, a platform
     * before 2.0) = no delivery context; a header the SDK cannot read is logged and left out, never a reason to refuse
     * the delivery (the signature covers `ts + "." + body`, not the headers).
     *
     * @param ?int $nowSeconds the time to verify the signature at (epoch seconds), for tests and replays; default now
     */
    public function handle(string $rawBody, ?string $timestampHeader, ?string $signatureHeader, ?int $nowSeconds = null,
        ?string $contextHeader = null, ?string $proofHeader = null): HookResult
    {
        $invalid = Signatures::verify($this->hookSecret, $timestampHeader, $signatureHeader, $rawBody, $nowSeconds ?? time());
        if ($invalid !== null) {
            return self::problem(401, 'invalid-signature', 'Invalid signature', $invalid, ErrorClass::PERMANENT, []);
        }

        try {
            $ce = Json::decode($rawBody);
        } catch (\JsonException) {
            $ce = null;
        }
        if (!Json::isObject($ce)) {
            return self::problem(400, 'invalid-envelope', 'Invalid envelope', 'body is not a CloudEvent JSON object', ErrorClass::PERMANENT, []);
        }

        // the envelope version is read after the signature and the parsing, before any handler
        $received = Json::get($ce, 'kfenvelopeversion');
        if (!self::supportedEnvelope($received)) {
            $supported = SdkVersion::SUPPORTED_ENVELOPE_MAJORS;
            sort($supported);
            $receivedText = Json::text($received);
            Log::warn('KF SDK: envelope version ' . ($receivedText ?? 'null') . ' is not supported by kf-module-sdk '
                . SdkVersion::sdkVersion() . ' (supported majors: [' . implode(', ', $supported) . ']); the handler was not called');
            return self::problem(422, 'unsupported-envelope-version', 'Unsupported envelope version',
                'kfenvelopeversion ' . ($received === null ? 'is missing' : '"' . $receivedText . '" is not supported')
                . '; this SDK supports the majors [' . implode(', ', $supported) . ']',
                ErrorClass::PERMANENT, ['received' => $receivedText, 'supported' => $supported]);
        }

        $data = Json::get($ce, 'data');
        $data = Json::isObject($data) ? $data : new \stdClass();
        // the module's effective flags as the platform had them when it sent the event
        $kfflags = Json::get($ce, 'kfflags');
        if (Json::isObject($kfflags)) {
            $this->flags->replace($kfflags);
            $this->state->write('flags', ['at' => time(), 'values' => Json::obj($this->flags->all())]);
        }
        $envelope = Envelope::of($ce, self::deliveryContextOf($contextHeader, $proofHeader));
        $event = $envelope->event;

        try {
            $result = $this->dispatch($event, $envelope, $data);
        } catch (\Throwable $e) {
            Log::error("KF SDK: handler for $event failed: " . self::describe($e));
            $patchOnDelete = $e instanceof PatchNotAllowedException;
            $message = trim($e->getMessage()) === '' ? substr(strrchr('\\' . $e::class, '\\'), 1) : $e->getMessage();
            $result = self::problem(500, 'handler-failed', 'Handler failed', 'handler failed: ' . $message,
                $patchOnDelete ? ErrorClass::PERMANENT : ErrorClass::TRANSIENT, []);
        }
        $this->notifyObservers($event, $envelope, $data, $result);
        return $result;
    }

    /** The context of an after delivery, or null when absent or unreadable (logged, never refused). */
    private static function deliveryContextOf(?string $contextHeader, ?string $proofHeader): ?DeliveryContext
    {
        if ($contextHeader === null || trim($contextHeader) === '') return null;
        if ($proofHeader === null || trim($proofHeader) === '') {
            Log::warn('KF SDK: the delivery carried ' . RequestContext::CONTEXT_HEADER . ' without ' . RequestContext::PROOF_HEADER
                . '; no delivery context for the handler');
            return null;
        }
        try {
            return DeliveryContext::of($contextHeader, $proofHeader);
        } catch (\Throwable $e) {
            Log::warn('KF SDK: the delivery context cannot be read (' . $e->getMessage() . '); no delivery context for the handler');
            return null;
        }
    }

    /** @internal Whether `kfenvelopeversion` (text, e.g. "1" or "1.3") has a supported major. */
    public static function supportedEnvelope(mixed $version): bool
    {
        if (!is_string($version) || trim($version) === '') return false;
        $dot = strpos($version, '.');
        $major = $dot === false ? $version : substr($version, 0, $dot);
        if ($major === '' || preg_match('/^[0-9]{1,9}$/', $major) !== 1) return false;
        return in_array((int) $major, SdkVersion::SUPPORTED_ENVELOPE_MAJORS, true);
    }

    private function dispatch(string $event, Envelope $envelope, \stdClass $data): HookResult
    {
        $hooks = str_starts_with($event, 'order.') ? $this->orders : $this->models;
        if (str_starts_with($event, 'model.before.') || str_starts_with($event, 'order.before.')) {
            $ctx = new BeforeContext($this->slug ?? '', $event, $envelope, $data);
            $patch = null;
            foreach ($hooks->beforeHandlers($event) as $handler) {
                $d = $handler($ctx);
                if ($d !== null && !$d instanceof Decision) {
                    throw new \UnexpectedValueException('a before-handler must return $ctx->allow(), reject(), patch() or null');
                }
                if ($d instanceof Reject) return self::ok($d->toJson());
                if ($d instanceof Patch && !$d->isEmpty()) $patch = $patch === null ? $d : $patch->merge($d);
            }
            if ($patch !== null && str_ends_with($event, '.before.delete')) throw new PatchNotAllowedException($event);
            if ($patch !== null && $hooks === $this->orders) {
                // protocol 2.0 (§16.2): the order's core fields carry patch: [] -- only extensions.{own slug}
                $core = array_values(array_filter(array_keys($patch->values()), static fn ($k) => $k !== 'extensions'));
                if ($core !== []) throw new PatchNotAllowedException($event, $core);
            }
            return self::ok($patch === null ? (new Allow())->toJson() : $patch->toJson());
        }
        if ($event === HookEvents::FIELD_CHANGED || $event === HookEvents::ORDER_FIELD_CHANGED) {
            $change = FieldChange::of($envelope, $data);
            foreach ($hooks->fieldHandlers($change->field) as $handler) $handler($change);
            return self::ok(['ok' => true]);
        }
        if (in_array($event, FlowEvents::TYPES, true)) {
            // protocol 2.0 (§16.3): the events of the flow engine
            $subscribed = $this->flows->handlers($event);
            if ($subscribed === []) return self::ok(['ok' => true, 'ignored' => $event]);
            try {
                $ev = FlowEvents::parse($envelope, (array) Json::toArray($data));
            } catch (\InvalidArgumentException $e) {
                return self::problem(400, 'invalid-envelope', 'Invalid envelope', $e->getMessage(), ErrorClass::PERMANENT, []);
            }
            foreach ($subscribed as $handler) $handler($ev);
            return self::ok(['ok' => true]);
        }
        if (in_array($event, HookEvents::TENANT_POINTS, true)) {
            $entity = is_string($data->entity ?? null) ? $data->entity : null;
            $raw = $data->id ?? null;
            $tenantId = $raw === null ? null : (is_scalar($raw) ? (string) $raw : (string) json_encode($raw));
            if ($entity !== 'tenant' || $tenantId === null || trim($tenantId) === '') {
                return self::problem(400, 'invalid-envelope', 'Invalid envelope', $event
                    . ' needs data.entity = tenant and data.id', ErrorClass::PERMANENT, []);
            }
            if ($event === HookEvents::TENANT_AFTER_DELETE) {
                if ($this->tenants->deleted === []) return self::ok(['ok' => true, 'ignored' => $event]);
                $deleted = new TenantDeleted($tenantId, $envelope);
                foreach ($this->tenants->deleted as $handler) $handler($deleted);
            } elseif ($event === HookEvents::TENANT_AFTER_SOFT_DELETE) {
                if ($this->tenants->softDeleted === []) return self::ok(['ok' => true, 'ignored' => $event]);
                $softDeleted = new TenantSoftDeleted($tenantId, $envelope);
                foreach ($this->tenants->softDeleted as $handler) $handler($softDeleted);
            } else {
                if ($this->tenants->restored === []) return self::ok(['ok' => true, 'ignored' => $event]);
                $restored = new TenantRestored($tenantId, $envelope);
                foreach ($this->tenants->restored as $handler) $handler($restored);
            }
            return self::ok(['ok' => true]);
        }
        if (str_starts_with($event, HookEvents::MODULE_EVENT_PREFIX)) {
            $point = preg_match('/' . HookEvents::MODULE_EVENT_POINT . '/D', $event, $m) === 1;
            $subscribed = $point ? $this->events->handlers($event) : [];
            if ($subscribed === []) return self::ok(['ok' => true, 'ignored' => $event]);
            $ev = new ModuleEvent($m[1], $m[2], (array) Json::toArray($data), $envelope);
            foreach ($subscribed as $handler) $handler($ev);
            return self::ok(['ok' => true]);
        }
        $handlers = $hooks->afterHandlers($event);
        if ($handlers !== null) {
            $ev = AfterEvent::of($event, $envelope, $data);
            foreach ($handlers as $handler) $handler($ev);
            return self::ok(['ok' => true]);
        }
        return self::ok(['ok' => true, 'ignored' => $event]);
    }

    // ---- the module's own routes -------------------------------------------------------

    /**
     * Verifies the signed context of a request the platform forwards to one of the module's own routes (`X-KF-Context`,
     * `X-KF-Timestamp`, `X-KF-Signature`) with this module's KF_HOOK_SECRET, now. The adapters (Sapi, the PSR-15
     * middleware) call it.
     *
     * @throws Rejected (401, PERMANENT) for a missing, altered, expired or incoherent context
     */
    public function verifyContext(?string $contextHeader, ?string $timestampHeader, ?string $signatureHeader, ?string $body,
                                  ?string $proofHeader = null): RequestContext
    {
        $ctx = RequestContext::verify($contextHeader, $timestampHeader, $signatureHeader, $body, $this->hookSecret, time());
        // Java: the overload with proofHeader -- keeps the raw context and the platform's proof for AuditClient.
        unset($this->forwardedContexts[$ctx->requestId]);
        $this->forwardedContexts[$ctx->requestId] = [
            'contextHeader' => trim((string) $contextHeader),
            'proof' => $proofHeader === null || trim($proofHeader) === '' ? null : trim($proofHeader),
            'ts' => $ctx->ts,
        ];
        while (count($this->forwardedContexts) > self::MAX_FORWARDED_CONTEXTS) {
            unset($this->forwardedContexts[array_key_first($this->forwardedContexts)]);
        }
        return $ctx;
    }

    /**
     * @internal The raw context and proof of a context this instance verified within ±300 s; null otherwise.
     * @return ?array{contextHeader: string, proof: ?string, ts: int}
     */
    public function forwardedContext(RequestContext $ctx): ?array
    {
        $forwarded = $this->forwardedContexts[$ctx->requestId] ?? null;
        if ($forwarded === null || abs(time() - $forwarded['ts']) > Signatures::MAX_SKEW_SECONDS) return null;
        return $forwarded;
    }

    /** @internal The platform base URL, for AuditClient and Notifications (never logged). */
    public function platformUrlValue(): ?string
    {
        return $this->platformUrl;
    }

    /** @internal The module token, for AuditClient and Notifications (never logged). */
    public function tokenValue(): ?string
    {
        return $this->token;
    }

    /**
     * Paths of the module served WITHOUT a platform context: the hook callback (`hookPath()`), the UI assets (`/ui/**`,
     * fetched by the platform's asset proxy) and these prefixes (default `/health`).
     */
    public function publicPath(string $prefix): self
    {
        $this->publicPaths[] = $prefix;
        return $this;
    }

    /** Whether a request path is served without a platform context (see publicPath()). */
    public function isPublicPath(?string $path): bool
    {
        if ($path === null) return false;
        if ($path === $this->hookPath || str_starts_with($path, '/ui/')) return true;
        foreach ($this->publicPaths as $prefix) {
            if ($path === $prefix || str_starts_with($path, str_ends_with($prefix, '/') ? $prefix : $prefix . '/')) return true;
        }
        return false;
    }

    private function notifyObservers(string $event, Envelope $envelope, \stdClass $data, HookResult $result): void
    {
        if ($this->observers === []) return;
        try {
            $answer = json_decode($result->body, true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $answer = $result->body;
        }
        $record = [
            'receivedAt' => gmdate('Y-m-d\TH:i:s\Z'),
            'type' => $event,
            'eventId' => $envelope->eventId ?? $envelope->deliveryId,
            'entityId' => Json::text(Json::get($data, 'id')),
            'changed' => HookEvents::stringList(Json::get($data, 'changed')),
            'field' => Json::text(Json::get($data, 'field')),
            'status' => $result->status,
            'answer' => $answer,
            'flags' => $this->flags->all(),
            'tenant' => $envelope->tenant,
            'locale' => $envelope->locale,
        ];
        foreach ($this->observers as $o) {
            try {
                $o($record);
            } catch (\Throwable) {
                // observers must not break callbacks
            }
        }
    }

    private static function ok(array $body): HookResult
    {
        return new HookResult(200, Json::encode($body), HookResult::CONTENT_TYPE);
    }

    /** An RFC 9457 answer with its `class`. */
    private static function problem(int $status, string $type, string $title, string $detail, ErrorClass $errorClass, array $extra): HookResult
    {
        $body = ['type' => 'https://kf.ro/problems/' . $type, 'title' => $title, 'status' => $status, 'detail' => $detail,
            ErrorClass::PROPERTY => $errorClass->wire()] + $extra;
        return new HookResult($status, Json::encode($body), HookResult::PROBLEM_CONTENT_TYPE);
    }
}

/**
 * A patch on `model.before.delete` / `order.before.delete`, or a patch of an order's core field: the module's own bug,
 * answered 500 PERMANENT. @internal
 */
final class PatchNotAllowedException extends \LogicException
{
    /** @param ?list<string> $coreFields */
    public function __construct(string $event, ?array $coreFields = null)
    {
        if ($coreFields === null) {
            parent::__construct("a patch is not allowed on $event; answer allow or reject");
            return;
        }
        sort($coreFields, SORT_STRING);
        parent::__construct("a patch on $event may carry only extensions of this module (the order's core fields are "
            . 'patch: []); it names ' . implode(', ', $coreFields));
    }
}
