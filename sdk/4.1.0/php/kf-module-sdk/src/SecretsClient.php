<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SecretsClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's secrets: capability `secrets` in `kf-module-conf.json`, protocol 1.1 §15.3. Obtained from
 * `KfModule::secrets()`. The manifest names a secret, never its value; the platform keeps the value in its vault under
 * the module's path and gives the module a reference `vault://<mount>/kf-modules/<slug>/<platform | tenantId>/<name>`,
 * which the module may store and resolve when it needs the value.
 *
 *     $ref = $kf->secrets()->write($ctx, 'r3-client-secret', $value);   // in a route with the secret's writeRight
 *     $value = $kf->secrets()->resolve($ctx, $ref)->value();
 *
 * - `write()`: `PUT …/secrets/{name}` `{"value"}` → the reference;
 * - `resolve()`: `POST …/secrets/resolve` `{"ref"}` → the value, as a Secret whose `__toString` never shows it.
 *
 * **A value is never logged and never put in an error**: this client logs nothing, and the texts of a platform refusal
 * to a write pass through a redaction of the value. Refused locally, PERMANENT, nothing sent: a name
 * outside `^[a-z][a-z0-9-]{1,40}$` (or not declared in `capabilities.secrets`, when the manifest is known), an empty
 * value, a reference outside the module's path (another slug, another shape) or of another tenant than the context's,
 * and the context rules of ReadsClient. The platform's refusals are PlatformRefusals (`403` without the secret's
 * `writeRight`, or for a reference the module may not read).
 *
 * **An existing reference** (SDK 0.7.2): when a `platform` secret of the manifest declares
 * `external` prefixes (`platform/aas/`), `resolve()` also sends a reference the platform already holds
 * (`vault://platform/aas/kf-r3/client-secret`) strictly under one of them; the platform resolves it and audits every
 * resolve. `externalRefProblem()` says why a reference is not one; without a known manifest every reference outside the
 * module's path is refused locally.
 *
 * **The secret store**: `503 secret-store-unreachable` (TRANSIENT: not configured, not reachable, not logged into) and
 * `503 secret-store-rejected` (PERMANENT: it answered and its policy refuses the path) come back as a KfException whose
 * problem carries the type, for the module to pass on to its user.
 */
final class SecretsClient
{
    /** A secret's name, as the manifest declares it. */
    public const NAME = '/^[a-z][a-z0-9-]{1,40}$/';
    /** The scheme of a reference. */
    public const SCHEME = 'vault://';

    private const MOUNT = '/^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/';
    private const TENANT = '/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/';
    private const REDACTED = '***';
    /** The longest existing reference the platform resolves for a module. */
    private const MAX_EXTERNAL_REF = 512;
    private const REF_SEGMENT = '/^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/D';
    private const MODULES_SEGMENT = 'kf-modules';

    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'secrets');
    }

    /**
     * Writes the value of the secret `$name` (in the tenant of `$context` for a `tenant` secret) and returns its
     * reference.
     *
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`)
     */
    public function write(RequestContext $context, string $name, #[\SensitiveParameter] string $value): string
    {
        $errors = [];
        if (preg_match(self::NAME, $name) !== 1) $errors[] = 'name must match ^[a-z][a-z0-9-]{1,40}$';
        if ($value === '') $errors[] = 'the value is required';
        if ($errors !== []) throw PlatformChannel::local('invalid secret write: ' . implode('; ', $errors));
        $manifest = $this->channel->kf()->manifest();
        if ($manifest !== null) {
            $declared = false;
            foreach ($manifest->capabilities->secrets as $secret) {
                if ($secret->name === $name) $declared = true;
            }
            if (!$declared) throw PlatformChannel::local("secret $name is not declared in capabilities.secrets of the manifest");
        }
        $forwarded = $this->channel->forwarded($context);

        $answer = $this->channel->send('PUT', $forwarded, '/' . $name, '', ['value' => $value]);
        if ($answer['status'] !== 200) {
            throw $this->channel->failure($answer, static fn (string $text): string => str_replace($value, self::REDACTED, $text));
        }
        $body = $this->channel->answerObject($answer, 'an object {ref}');
        $ref = $body['ref'] ?? null;
        if (!is_string($ref) || $this->refProblem($ref, $context) !== null) {
            throw new KfException(new PlatformProblem(200, null, null, 'the secrets answer has no reference of this module',
                ErrorClass::PERMANENT, null));
        }
        return $ref;
    }

    /**
     * The value behind `$ref`, a reference of this module (`platform`, or of the context's tenant), or an existing
     * reference strictly under a prefix a `platform` secret of the manifest declares in `external`.
     *
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`401`, `403`, `404`)
     */
    public function resolve(RequestContext $context, string $ref): Secret
    {
        $problem = $this->refProblem($ref, $context);
        if ($problem !== null && !self::ownFamily($ref)) $problem = $this->externalRefProblem($ref);
        if ($problem !== null) throw PlatformChannel::local('invalid secret reference: ' . $problem);
        $forwarded = $this->channel->forwarded($context);

        $answer = $this->channel->send('POST', $forwarded, '/resolve', '', ['ref' => $ref]);
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);
        $body = $this->channel->answerObject($answer, 'an object {value}');
        if (!is_string($body['value'] ?? null)) {
            throw new KfException(new PlatformProblem(200, null, null, 'the secrets answer has no value', ErrorClass::PERMANENT, null));
        }
        return new Secret($ref, $body['value']);
    }

    /**
     * Why `$ref` is not an existing reference this module may keep and resolve, or null when it is (SDK 0.7.2): `vault://` + a path of at least two segments of `[A-Za-z0-9._-]` (none empty, none starting
     * with a dot), with no `kf-modules` segment, at most 512 characters, strictly under a prefix that a `platform` secret
     * of the manifest declares in `external`. The platform applies the same rules (`403 external-ref-not-declared`
     * otherwise).
     */
    public function externalRefProblem(?string $ref): ?string
    {
        if ($ref === null || !str_starts_with($ref, self::SCHEME)) return 'a reference starts with ' . self::SCHEME;
        if (strlen($ref) > self::MAX_EXTERNAL_REF) return 'a reference has at most ' . self::MAX_EXTERNAL_REF . ' characters';
        $path = substr($ref, strlen(self::SCHEME));
        $segments = explode('/', $path);
        $shaped = count($segments) >= 2;
        foreach ($segments as $segment) {
            if (preg_match(self::REF_SEGMENT, $segment) !== 1 || $segment === self::MODULES_SEGMENT) $shaped = false;
        }
        if (!$shaped) {
            return "$ref is not an existing vault reference (vault://<mount>/<path>: segments of [A-Za-z0-9._-], "
                . 'none empty, none starting with a dot, no kf-modules)';
        }
        $manifest = $this->channel->kf()->manifest();
        if ($manifest === null) {
            return "$ref is outside the module's path, and without the manifest no capabilities.secrets[].external "
                . 'prefix is known';
        }
        foreach ($manifest->capabilities->secrets as $secret) {
            if ($secret->scope !== 'platform') continue;
            foreach ($secret->external as $prefix) {
                if (str_starts_with($path, $prefix) && strlen($path) > strlen($prefix)) return null;
            }
        }
        return "$ref is under no prefix declared in capabilities.secrets[].external";
    }

    /** Whether `$ref` names the modules' family (`…/kf-modules/…`): its problem is refProblem()'s. */
    private static function ownFamily(?string $ref): bool
    {
        return $ref !== null && str_starts_with($ref, self::SCHEME)
            && in_array(self::MODULES_SEGMENT, explode('/', substr($ref, strlen(self::SCHEME))), true);
    }

    /**
     * @internal Why `$ref` is not a reference this module may resolve in the tenant of `$context`, or null when it is:
     * `vault://<mount>/kf-modules/<this slug>/<platform | the context's tenant>/<name>`.
     */
    public function refProblem(?string $ref, ?RequestContext $context): ?string
    {
        $slug = $this->channel->kf()->slug();
        if ($ref === null || !str_starts_with($ref, self::SCHEME)) return 'a reference starts with ' . self::SCHEME;
        $rest = substr($ref, strlen(self::SCHEME));
        $at = strrpos($rest, '/kf-modules/');
        if ($at === false || $at <= 0 || preg_match(self::MOUNT, substr($rest, 0, $at)) !== 1) {
            return "$ref is not a reference of this module (vault://<mount>/kf-modules/$slug/…)";
        }
        $parts = explode('/', substr($rest, $at + strlen('/kf-modules/')));
        if (count($parts) !== 3 || $parts[0] !== $slug) {
            return "$ref is not a reference of this module (vault://<mount>/kf-modules/$slug/…)";
        }
        if (preg_match(self::NAME, $parts[2]) !== 1) return "$ref does not end with a secret name";
        if ($parts[1] === 'platform') return null;
        if (preg_match(self::TENANT, $parts[1]) !== 1) return "$ref names neither platform nor a tenant";
        if ($context !== null && strtolower($parts[1]) !== strtolower($context->tenant)) {
            return "$ref is a reference of another tenant than the context's";
        }
        return null;
    }
}
