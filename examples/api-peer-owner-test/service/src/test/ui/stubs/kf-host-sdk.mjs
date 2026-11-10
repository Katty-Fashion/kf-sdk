// Stand-ins for the host's @kf/host-sdk (smoke test only), host API 5 with Slot.
export const apiVersion = 5;
export const calls = [];
export function http(path) {
  calls.push(path);
  return Promise.resolve({ items: [{ id: 'item-1', name: 'Button A' }] });
}
export function moduleUrl(module, path) {
  return '/api/proxy/m/' + module + '/' + (path || '');
}
export function locale() {
  return 'en';
}
export function Slot(props) {
  return { type: 'Slot', props, children: [] };
}
