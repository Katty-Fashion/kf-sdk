<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ReadsClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's reads of platform objects: capability `reads` in `kf-module-conf.json` (`[{"port": "model.v1"}]`),
 * protocol 1.1 §15.1 (the ports decided on 2026-10-21 in `docs/modules/specifications/10-citirile.md`).
 * Obtained from `KfModule::reads()`.
 *
 *     $page = $kf->reads()->list($ctx, 'order.v1', ['modelVersionId' => '17'], 0, 50);
 *     $version = $kf->reads()->get($ctx, 'model-version.v1', '17', ['grading']);
 *
 * - `list()`: `GET …/reads/{port}?<filters>&page=<n>&size=<n>` → ReadPage; `page` from 0, `size` 1–MAX_SIZE (default
 *   DEFAULT_SIZE); the filters in the order given; the list filter `ids` takes at most MAX_IDS values, comma-separated;
 * - `get()`: `GET …/reads/{port}/{id}[?include=a,b]` → the object, or null when the platform answers `404` (not visible
 *   in the context's tenant).
 *
 * Both calls carry the module token, the context and its proof, unchanged (PlatformChannel); the platform reads in the
 * context's tenant, with the right of the port held by the context's user. Refused locally, PERMANENT, nothing sent: a
 * port outside the grammar (Manifest::PORT_ID), a port the module's manifest does not declare (when the
 * manifest is known), `page < 0`, `size` outside 1–MAX_SIZE, more than MAX_IDS values in `ids`, a filter or include
 * name outside `[a-z][A-Za-z0-9]{0,40}` (or a reserved name), a blank id, a context not verified or without proof, and the two rules of the system context:
 * it reads *only objects* and *only the base fields* (no `include`). The one exception (protocol 1.3): the platform's own context (`system` via `platform`) lists the ports of PLATFORM_LISTS --
 * `tenant.v1`, without `ids`. The platform's refusals are
 * PlatformRefusals (`403` with `insufficient-rights`, `capability-not-declared` or `module-not-live`; `401`
 * `invalid-context`; `400` on the filters); a `503` is a transient KfException.
 */
final class ReadsClient
{
    /** The default page size of the protocol. */
    public const DEFAULT_SIZE = 20;
    /** The largest page the protocol serves. */
    public const MAX_SIZE = 200;
    /** The most values the list filter `ids` takes. */
    public const MAX_IDS = 200;
    /** The list filter by ids, its values comma-separated. */
    public const IDS = 'ids';
    /** A filter name or an `include` name. */
    public const NAME = '/^[a-z][A-Za-z0-9]{0,40}$/';
    /** Query names the client writes itself. */
    public const RESERVED = ['page', 'size', 'include'];
    /**
     * The ports the platform's own context (`system` via `platform`) may list (protocol 1.3 §15.1):
     * `tenant.v1`, every tenant with `deletedAt`. Any other list from a system context is refused locally.
     */
    public const PLATFORM_LISTS = ['tenant.v1'];

    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'reads');
    }

    /**
     * One page of the port's list in the tenant of `$context` (Java's two `list` overloads: without `$page` and
     * `$size`, page 0 with the default size).
     *
     * @param ?array<string, string> $filters the port's filters (name → value, sent in this order); a list filter such
     *                                        as `ids` takes its values comma-separated, `ids` at most MAX_IDS of
     *                                        them; empty or null for none
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`, `404`)
     */
    public function list(RequestContext $context, string $port, ?array $filters, int $page = 0, int $size = self::DEFAULT_SIZE): ReadPage
    {
        $errors = [];
        self::checkPort($errors, $port);
        if ($page < 0) $errors[] = 'page must be at least 0';
        if ($size < 1 || $size > self::MAX_SIZE) $errors[] = 'size must be between 1 and ' . self::MAX_SIZE;
        $given = $filters ?? [];
        foreach ($given as $name => $value) {
            $name = (string) $name;
            if (preg_match(self::NAME, $name) !== 1) $errors[] = "filter $name must match [a-z][A-Za-z0-9]{0,40}";
            elseif (in_array($name, self::RESERVED, true)) $errors[] = "filter $name is reserved";
            elseif ($value === null) $errors[] = "filter $name has no value";
            elseif ($name === self::IDS && count(explode(',', (string) $value)) > self::MAX_IDS) {
                $errors[] = 'filter ' . self::IDS . ' takes at most ' . self::MAX_IDS . ' values';
            }
        }
        if ($errors !== []) throw PlatformChannel::local('invalid read: ' . implode('; ', $errors));
        $platformActor = $context->actor->type === 'system' && $context->actor->via === 'platform'
            && $context->actor->hookId === null;
        if ($context->actor->type === 'system' && !($platformActor && in_array($port, self::PLATFORM_LISTS, true))) {
            throw PlatformChannel::local("the system context reads only objects ($port list refused)");
        }
        $this->checkDeclared($port);
        $forwarded = $this->channel->forwarded($context);

        $query = '';
        foreach ($given as $name => $value) {
            $query .= ($query === '' ? '?' : '&') . $name . '=' . PlatformChannel::queryValue((string) $value);
        }
        $query .= ($query === '' ? '?' : '&') . 'page=' . $page . '&size=' . $size;
        $answer = $this->channel->send('GET', $forwarded, '/' . $port, $query, null);
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);

        $body = $this->channel->answerObject($answer, 'a page {items, page, size, total}');
        $items = $body['items'] ?? null;
        if (!is_array($items) || !array_is_list($items) || !is_int($body['page'] ?? null) || !is_int($body['size'] ?? null)
            || !is_int($body['total'] ?? null)) {
            throw new KfException(new PlatformProblem(200, null, null, 'the reads answer is not a page {items, page, size, total}',
                ErrorClass::PERMANENT, null));
        }
        $objects = [];
        foreach ($items as $item) {
            if (is_array($item) && ($item === [] || !array_is_list($item))) $objects[] = $item;
        }
        return new ReadPage($objects, $body['page'], $body['size'], $body['total']);
    }

    /**
     * One object of the port in the tenant of `$context`, with the heavy sub-collections named in `$include` (
     * none = the base fields); null when the platform answers `404`.
     *
     * @param list<string> $include
     * @return ?array<string, mixed>
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`)
     */
    public function get(RequestContext $context, string $port, string $id, array $include = []): ?array
    {
        $errors = [];
        self::checkPort($errors, $port);
        if (trim($id) === '') $errors[] = 'id is required';
        foreach ($include as $name) {
            if (!is_string($name) || preg_match(self::NAME, $name) !== 1) $errors[] = "include $name must match [a-z][A-Za-z0-9]{0,40}";
        }
        if ($errors !== []) throw PlatformChannel::local('invalid read: ' . implode('; ', $errors));
        if ($context->actor->type === 'system' && $include !== []) {
            throw PlatformChannel::local('the system context reads only the base fields (include ' . implode(',', $include)
                . ' refused)');
        }
        $this->checkDeclared($port);
        $forwarded = $this->channel->forwarded($context);

        $query = $include === [] ? '' : '?include=' . implode(',', $include);
        $answer = $this->channel->send('GET', $forwarded, '/' . $port . '/' . PlatformChannel::segment($id), $query, null);
        if ($answer['status'] === 404) return null;
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);
        return $this->channel->answerObject($answer, 'an object');
    }

    // ---------------------------------------------------------------------------------------------------------

    /** @param list<string> $errors */
    private static function checkPort(array &$errors, string $port): void
    {
        if (preg_match('/' . Manifest::PORT_ID . '/', $port) !== 1) $errors[] = 'port must match ' . Manifest::PORT_ID;
    }

    /** With the manifest known, the port must be one the module declares (the platform would answer 403). */
    private function checkDeclared(string $port): void
    {
        $manifest = $this->channel->kf()->manifest();
        if ($manifest === null) return;
        foreach ($manifest->capabilities->reads as $read) {
            if ($read->port === $port) return;
        }
        throw PlatformChannel::local("$port is not declared in capabilities.reads of the manifest");
    }
}
