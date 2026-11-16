# Changelog: kf-demo-model-card

Signed releases of the tutorial module. Each section is the body of the module's GitHub release.

## 0.2.0 — 2026-11-15

Maintenance release on the current platform and module SDK. No functional change.

### Changed
- Requires platform `>=4.1` (was `>=6.1` under the previous platform numbering).
- Built on module SDK 4.1.1 (protocol 2.2); the manifest moves to `schemaVersion` 3.

## 0.1.0 — 2026-11-02

Initial release. The tutorial module: a model card on the model's Overview tab and a page with the tenant's models and each user's pinned cards.

### Added
- Model card with the version image (with refresh), the owner, a monogram and a QR code to the model.
- Cards page with all models (paginated) and the cards pinned by the user.
- Models and images come only through platform reads (`model.v1`, `model-version.garment-image.v1`); the module stores only the pins, in its own database.
- Hooks `model.after.delete` and `tenant.after.delete`, a notification, a menu entry, and the tenant data export and import.
- Built on module SDK 0.9.1 (protocol 1.3.1); requires platform `>=6.1`.
