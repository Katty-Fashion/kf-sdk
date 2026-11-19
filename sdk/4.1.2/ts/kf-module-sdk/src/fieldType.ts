// kf-sdk-derived-from: sdk/java@c781e2415a5ef9bd4d2e6a1892d4e4e1944d3668 v4.1.2
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
