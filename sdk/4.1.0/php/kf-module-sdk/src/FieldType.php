<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
