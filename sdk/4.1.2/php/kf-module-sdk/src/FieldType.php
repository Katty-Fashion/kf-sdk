<?php // kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
// Derived by /kf-sdk-derive from ro/kf/sdk/FieldType.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/** Extension field types (mirror KF's AasFieldType). Stored as text / numeric / date / boolean / jsonb columns. */
enum FieldType: string
{
    case STRING = 'STRING';
    case NUMBER = 'NUMBER';
    case DATE = 'DATE';
    case BOOLEAN = 'BOOLEAN';
    case LIST = 'LIST';
}
