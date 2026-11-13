// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from the package ro.kf.sdk (the public API of the Java SDK). Do not edit by hand: fix Java, then re-derive.
/**
 * KF platform module SDK for Node (>= 22), derived from the Java reference SDK `ro.kf:kf-module-sdk`. Zero
 * runtime dependencies. The wire contract is `sdk/sdk-protocol.md` (v1.3); the behaviour is fixed by the shared
 * conformance suite `sdk/conformance/`.
 *
 *   const kf = KfModule.fromEnv();
 *   kf.models().beforeCreate((ctx) => (ctx.after?.name?.includes('x') ? ctx.reject('veto.no-x') : ctx.allow()));
 *   kf.registerField('model', 'co2_kg', 'NUMBER', 'models_extra', 'field.co2');
 *   kf.ui().contribute('models.detail.tabs', Surface.react('/m/my-mod/ui/surfaces.mjs', 'Co2Tab').key('co2'));
 *   http.createServer(nodeHandler(kf, { uiDir: './ui' })).listen(3000);
 *   kf.start();
 */
export { AuditClient, AuditForbidden, AuditUnauthorized } from './auditClient.js';
export { AuditRecord } from './auditRecord.js';
export { BeforeContext } from './beforeContext.js';
export { Allow, Decision, Patch, Reject } from './decision.js';
export { ErrorClass } from './errorClass.js';
export { ExtensionsClient } from './extensionsClient.js';
export { FeatureFlags } from './featureFlags.js';
export { FieldType } from './fieldType.js';
export { AfterEvent, type AfterHandler, type BeforeHandler, type CallbackRecord, DeliveryContext, Envelope, FieldChange, type FieldChangeHandler, HookEvents, MODULE_EVENT_POINT, MODULE_EVENT_PREFIX, ModuleEvent, type ModuleEventHandler, moduleEventPoint, ORDER_POINTS, ORDER_STATUSES, TENANT_POINTS, TenantDeleted, type TenantDeletedHandler, TenantRestored, type TenantRestoredHandler, TenantSoftDeleted, type TenantSoftDeletedHandler, VersionField } from './hookEvents.js';
export { HookResult } from './hookResult.js';
export { KfException } from './kfException.js';
export { type FieldRegistration, type HandleOptions, KfModule } from './kfModule.js';
export { type SdkLogger, setSdkLogger } from './log.js';
export {
  Activation,
  type AuditCapability,
  type Capabilities,
  type FeatureFlag,
  type FieldCapability,
  type FlowCapability,
  type FlowRuleCapability,
  type HookCapability,
  Manifest,
  type NavCapability,
  type NotificationCapability,
  type ProvideCapability,
  type ReadCapability,
  type Requirement,
  type RouteCapability,
  type SecretCapability,
  type ServiceCapability,
  type SettingCapability,
  type SlotCapability,
  SlotKind,
  SlotVisibility,
  type StorageCapability,
  type SurfaceCapability,
} from './manifest.js';
export { ManifestCheck, ManifestCheckResult } from './manifestCheck.js';
export { NotificationOptions } from './notificationOptions.js';
export { NotificationRejected, type NotificationResult, Notifications } from './notifications.js';
export { Material } from './material.js';
export { type HookRegistration, ModelHooks, OnFailure } from './modelHooks.js';
export { ModelSnapshot } from './modelSnapshot.js';
export { type KfRequest, type NodeHandlerOptions, nodeHandler, route, type RouteHandler, serveUi, type ServeUiOptions } from './node.js';
export { PlatformProblem } from './platformProblem.js';
export { PlatformRefusal, type PlatformRefusalKind } from './platformRefusal.js';
export { ReadPage } from './readPage.js';
export { ReadsClient } from './readsClient.js';
export { PeerAnswer, PeersClient } from './peersClient.js';
export { type EmittedEvent, EventsClient } from './eventsClient.js';
export { FlowEvent, type FlowEventHandler, FlowEvents } from './flowEvents.js';
export { FlowRuleDecision, FlowRuleProjection, type FlowRuleProjectionFields, FlowRules } from './flowRules.js';
export { OrderServiceLine, OrderServices } from './orderServices.js';
export { Actor, type Person, Rejected, RequestContext } from './requestContext.js';
export { SdkVersion } from './sdkVersion.js';
export { Secret, SecretsClient } from './secretsClient.js';
export { SettingsClient } from './settingsClient.js';
export { type DownloadUrl, StorageClient, type UploadUrl } from './storageClient.js';
export { Signatures } from './signatures.js';
export { Surface, type SurfaceRegistration } from './surface.js';
export type * from './surfaces.generated.js';
export { TenantDataExport, type TenantDataExportOptions } from './tenantDataExport.js';
export { TenantHooks } from './tenantHooks.js';
export { UiContributions } from './uiContributions.js';
