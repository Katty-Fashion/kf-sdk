<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/BeforeContext.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

use Kf\Sdk\Decision\Allow;
use Kf\Sdk\Decision\Patch;
use Kf\Sdk\HookEvents\Envelope;
use Kf\Sdk\HookEvents\VersionField;

/**
 * Input of a before-hook (`model.before.create|update|delete`).
 * - `before`: stored model (null on create)
 * - `after`: model as it will be saved, including patches of modules with a lower priority (null on delete)
 * - `data`: the raw CloudEvent `data` (associative array)
 * - `changedVersionFields()`, `changedVersionIds()`: a version's paths in the model's envelope (§5.1), their values
 *   through `ModelSnapshot::versionExtension($versionId, $slug, $field)`
 * - `tenant()`, `locale()`, `eventId()`, `envelopeVersion()`: from the envelope (`kftenant`, `kflocale`, `kfeventid`,
 *   `kfenvelopeversion`)
 *
 * Return `allow()`, `reject($key, $params)` (a translation KEY of the manifest's `i18n` and its parameters) or
 * `patch()` (null means allow).
 */
final class BeforeContext
{
    public readonly ?ModelSnapshot $before;
    public readonly ?ModelSnapshot $after;
    /** @var list<string> Changed field paths (`name`, `materialList`, `extensions.{slug}.{field}`). */
    public readonly array $changed;
    public readonly array $data;

    /** @internal */
    public function __construct(
        private readonly string $slug,
        /** e.g. `model.before.update` */
        public readonly string $event,
        /** Every attribute of the envelope. */
        public readonly Envelope $envelope,
        \stdClass $data,
    ) {
        $this->before = ModelSnapshot::fromMap(Json::get($data, 'before'));
        $this->after = ModelSnapshot::fromMap(Json::get($data, 'after'));
        $this->changed = HookEvents::stringList(Json::get($data, 'changed'));
        $this->data = Json::toArray($data);
    }

    /** `kfeventid`: the per-mutation id of this before-hook call; null from an older platform. */
    public function eventId(): ?string
    {
        return $this->envelope->eventId;
    }

    /** CloudEvents `id`: the id of this delivery. */
    public function deliveryId(): ?string
    {
        return $this->envelope->deliveryId;
    }

    /** `kftenant`: the tenant the mutation happens in. */
    public function tenant(): ?string
    {
        return $this->envelope->tenant;
    }

    /** `kflocale`: the language of the user who causes the mutation (`en` by default). */
    public function locale(): ?string
    {
        return $this->envelope->locale;
    }

    /** `kfactor`: the OIDC subject of that user; null for the system. */
    public function actor(): ?string
    {
        return $this->envelope->actor;
    }

    /** `kfenvelopeversion`, e.g. "1". */
    public function envelopeVersion(): ?string
    {
        return $this->envelope->envelopeVersion;
    }

    /** Model id; null on create. */
    public function id(): ?string
    {
        return Json::text($this->data['id'] ?? null);
    }

    /**
     * The version paths of `changed` (`versions.{versionId}.extensions.{slug}.{field}`), in order: a module's write on
     * `model_version` (§5.1). Read each value with `$ctx->after->versionExtension($f->versionId, $f->slug, $f->field)`.
     *
     * @return list<VersionField>
     */
    public function changedVersionFields(): array
    {
        return HookEvents::versionFields($this->changed);
    }

    /**
     * The versions those paths touch, distinct, in order of first appearance.
     *
     * @return list<string>
     */
    public function changedVersionIds(): array
    {
        return HookEvents::versionIds($this->changedVersionFields());
    }

    /** This module's own value in `after` (or `before` on delete). */
    public function ownExtension(string $field): mixed
    {
        $s = $this->after ?? $this->before;
        return $s?->extension($this->slug, $field);
    }

    public function allow(): Decision
    {
        return new Allow();
    }

    /**
     * A veto: `{"decision":"reject","key":…,"params":{…}}`. The platform shows the key's text on the user's
     * language, with the parameters interpolated (texts at most 500 characters, numbers or booleans; at most 20).
     */
    public function reject(string $key, ?array $params = null): Decision
    {
        return Decision::reject($key, $params ?? []);
    }

    /** Starts a patch; chain `->name(..)`, `->set(..)`, `->extension($field, $value)` and return it. */
    public function patch(): Patch
    {
        return new Patch($this->slug);
    }
}
