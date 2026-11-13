// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
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
