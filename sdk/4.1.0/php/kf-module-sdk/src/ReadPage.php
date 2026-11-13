<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ReadPage.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * One page of a list read (`ReadsClient::list`, protocol 1.1 §15.1): the objects of the port in the platform's fixed
 * order (`id` ascending), as associative arrays (`null` kept).
 */
final class ReadPage
{
    /** @var list<array<string, mixed>> */
    public readonly array $items;

    /**
     * @param ?list<array<string, mixed>> $items the objects of this page, never null
     * @param int $page  the page number, from 0
     * @param int $size  the page size the platform applied
     * @param int $total how many objects the whole list has
     */
    public function __construct(?array $items, public readonly int $page, public readonly int $size, public readonly int $total)
    {
        $this->items = array_values($items ?? []);
    }

    /** Whether a later page has objects: `(page + 1) * size < total`. */
    public function hasNext(): bool
    {
        return ($this->page + 1) * $this->size < $this->total;
    }
}
