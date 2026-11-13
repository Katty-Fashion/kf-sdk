// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/Material.java. Do not edit by hand: fix Java, then re-derive.

/** One line of a model's material list. */
export class Material {
  constructor(
    readonly material: string | null,
    readonly quantity: number | null,
    readonly unit: string | null,
  ) {}

  /** @internal */
  static fromMap(m: Record<string, unknown>): Material {
    const q = m.quantity;
    return new Material(
      m.material == null ? null : String(m.material),
      q == null ? null : Number(q),
      m.unit == null ? null : String(m.unit),
    );
  }

  /** @internal */
  toMap(): Record<string, unknown> {
    return { material: this.material, quantity: this.quantity, unit: this.unit };
  }
}
