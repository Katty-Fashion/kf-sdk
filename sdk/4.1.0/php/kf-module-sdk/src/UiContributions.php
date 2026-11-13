<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/UiContributions.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's UI contributions to the host's frontend contact surfaces, sent with the registration. Only the surfaces
 * of the platform's release list exist for a module: any other slug is refused at registration
 * (422, the same answer for an internal and an unknown surface). The release list is
 * `sdk/catalog/surfaces.public.json`; its slugs are the constants of `Surfaces` (GENERATED).
 */
final class UiContributions
{
    /** @var list<array{0: string, 1: Surface}> */
    private array $entries = [];

    /** Contributes `$surface` to the host surface `$slug`, e.g. `models.form.edit.fields.after`. */
    public function contribute(string $slug, Surface $surface): self
    {
        $this->entries[] = [$slug, $surface];
        return $this;
    }

    /** @internal The `surfaces` part of the registration body. */
    public function registration(): array
    {
        return array_map(static fn (array $e): array => $e[1]->toJson($e[0]), $this->entries);
    }
}
