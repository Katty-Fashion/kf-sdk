/** The host API the contributions need (KF_HOST_API_VERSION 4: `moduleUrl`, `t`, `locale`, the React surfaces). */
import { apiVersion } from '@kf/host-sdk';

export const REQUIRED_HOST_API = 4;

/** `apiVersion` is read at each call: the host's live binding. */
export function hostTooOld(): boolean {
  return !(typeof apiVersion === 'number' && apiVersion >= REQUIRED_HOST_API);
}

export function hostApiVersion(): number {
  return apiVersion;
}
