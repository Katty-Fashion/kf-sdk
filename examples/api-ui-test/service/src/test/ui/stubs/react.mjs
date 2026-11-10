// A minimal stand-in for the host's React (smoke test only): elements are plain objects, hooks run inline.
export function createElement(type, props, ...children) {
  return { type, props: props || {}, children };
}
export function useState(initial) {
  return [typeof initial === 'function' ? initial() : initial, () => {}];
}
export const effects = [];
export function useEffect(fn) {
  effects.push(fn);
}
