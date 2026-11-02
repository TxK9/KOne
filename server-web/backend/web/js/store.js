// Everything the pages show, fetched from the backend and kept fresh while the page is open.

import {get} from './api.js';

export const data = {
  state: {profiles: [], users: [], records: []},
  fleet: {servers: [], nodes: [], exits: [], routes: [], jobs: [], installOrigin: null},
  customers: {groups: [], users: []},
  controller: {format: 1, nodes: [], devices: []},
  loaded: {state: false, fleet: false, customers: false, controller: false},
  stale: false,      // the backend stopped answering; what is shown is the last we heard
  checkedAt: 0,
  controllerReceivedAt: 0,
};

const listeners = new Set();
export const subscribe = callback => { listeners.add(callback); return () => listeners.delete(callback); };
const notify = () => listeners.forEach(callback => callback());

let generation = 0;
const failed = new Set();
const inflight = {};
function once(key, task) {
  if (!inflight[key]) inflight[key] = task().finally(() => { delete inflight[key]; });
  return inflight[key];
}

async function load(key, path) {
  const epoch = generation;
  return once(epoch + ':' + key, async () => {
    try {
      const result = await get(path);
      if (epoch !== generation) return;
      data[key] = result;
      if (key === 'controller') data.controllerReceivedAt = performance.now();
      data.loaded[key] = true;
      failed.delete(key);
      data.stale = failed.size > 0;
      data.checkedAt = Date.now();
    } catch (error) {
      if (epoch !== generation) return;
      failed.add(key);
      data.stale = true;
      throw error;
    } finally {
      if (epoch === generation) notify();
    }
  });
}

export const refreshState = () => load('state', '/api/state');
export const refreshFleet = () => load('fleet', '/api/fleet');
export const refreshCustomers = () => load('customers', '/api/customers');
export const refreshController = () => load('controller', '/api/node-controller');

/** Reloads everything; used after a change, so every page shows its effect. */
export function refreshAll() {
  return Promise.allSettled([refreshState(), refreshFleet(), refreshCustomers(), refreshController()]);
}

// Lookups.
export const server = id => data.fleet.servers.find(item => item.id === id);
export const node = id => data.fleet.nodes.find(item => item.id === id);
export const route = id => data.fleet.routes.find(item => item.id === id);
export const exit = id => data.fleet.exits.find(item => item.id === id);
export const profile = id => data.state.profiles.find(item => item.id === id);
export const customer = id => data.customers.users.find(item => item.id === id);
export const group = id => data.customers.groups.find(item => item.id === id);

/** The route a customer is on, if any. */
export const routeOf = userId => data.fleet.routes.find(r => r.assignments.some(a => a.userId === userId));

// Polling: the fleet changes by itself (agents report every few seconds), the rest mostly
// changes when we change it. Nothing is fetched while the tab is hidden.
let timers = [];
export function startPolling() {
  stopPolling();
  const every = (ms, task) => timers.push(setInterval(() => { if (!document.hidden) task().catch(() => {}); }, ms));
  every(5000, refreshFleet);
  every(12000, refreshCustomers);
  every(20000, refreshState);
  every(10000, refreshController);
  document.addEventListener('visibilitychange', wake);
}

export function stopPolling() {
  timers.forEach(clearInterval);
  timers = [];
  document.removeEventListener('visibilitychange', wake);
}

function wake() {
  if (!document.hidden) refreshAll();
}

export function reset() {
  generation++;
  failed.clear();
  data.state = {profiles: [], users: [], records: []};
  data.fleet = {servers: [], nodes: [], exits: [], routes: [], jobs: [], installOrigin: null};
  data.customers = {groups: [], users: []};
  data.controller = {format: 1, nodes: [], devices: []};
  data.loaded = {state: false, fleet: false, customers: false, controller: false};
  data.stale = false;
  data.checkedAt = 0;
  data.controllerReceivedAt = 0;
}
