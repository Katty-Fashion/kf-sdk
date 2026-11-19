<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/Manifest.java (Manifest.HelpCapability). Do not edit by hand.

declare(strict_types=1);

namespace Kf\Sdk\Manifest;

/**
 * One help note of the module (protocol 2.2, sdk-protocol.md §7.13): a link, with a title and a summary, to a page the
 * module already serves. The host reads it from the `conf` of the active version; nothing is registered. Exactly one of
 * `page` and `section` is set; the audience is given by it (`page`: the tenant, `section`: the platform administrator).
 *
 * - `key`: `^[a-z][a-z0-9-]{0,40}$`, unique; the public key is `m.{slug}.help.{key}`
 * - `title`: a key of `i18n.en`; `summary`: optional, a key of `i18n.en`, `null` when absent
 * - `page`: a `surfaces[].page` this manifest declares on `module.pages`; `null` when the target is a section
 * - `section`: a `surfaces[].page` this manifest declares on `setup.sections`; `null` when the target is a page
 * - `context`: the `module.pages` pages on which the host shows a "?" that opens the note; never null
 * - `right`: optional, the rules of `nav[].right`; `null` when absent. Presentation only
 * - `order`: 0 to 10000, Manifest::HELP_DEFAULT_ORDER (100) when absent
 */
final class HelpCapability
{
    /**
     * @param list<string> $context
     */
    public function __construct(
        public readonly ?string $key,
        public readonly ?string $title,
        public readonly ?string $summary,
        public readonly ?string $page,
        public readonly ?string $section,
        public readonly array $context,
        public readonly ?string $right,
        public readonly int $order,
    ) {
    }
}
