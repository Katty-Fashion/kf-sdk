// kf-sdk-derived-from: sdk/java@36b01afaa472c152519a21f8c005625d89c64a90 v4.1.1
// Derived by /kf-sdk-derive from ro/kf/sdk/FieldType.java. Do not edit by hand: fix Java, then re-derive.

/** Extension field types (mirror KF's AasFieldType). Stored as text / numeric / date / boolean / jsonb columns. */
export type FieldType = 'STRING' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'LIST';

export const FieldType = {
  STRING: 'STRING' as FieldType,
  NUMBER: 'NUMBER' as FieldType,
  DATE: 'DATE' as FieldType,
  BOOLEAN: 'BOOLEAN' as FieldType,
  LIST: 'LIST' as FieldType,
  values(): readonly FieldType[] {
    return ['STRING', 'NUMBER', 'DATE', 'BOOLEAN', 'LIST'];
  },
} as const;
