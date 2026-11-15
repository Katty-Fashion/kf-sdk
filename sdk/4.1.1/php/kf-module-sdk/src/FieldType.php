<?php // kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
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
