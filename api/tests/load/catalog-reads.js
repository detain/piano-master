// P0.6.5 load baseline — cached-read proxy (plan §13.6, §20 P0.6.5).
//
// GET /cache/now is the Dragonfly read-through cache endpoint. Until the
// Phase-1 catalog endpoints exist, it is the PROXY for "cached catalog
// reads": a single Redis GET (cache hit) with the same shape the catalog
// routes will have. See api/tests/load/README.md for the full methodology
// and the caveats that come with the proxy.
//
// Reference target (§13.6, 4 vCPU node): >= 5 000 req/s, p99 < 120 ms.
// This box is 64-core; the server is pinned to 4 CPUs (taskset -c 0-3,
// WEBMAN_COUNT=4) and k6 runs on its own CPUs to approximate the reference.
//
// Usage:
//   taskset -c 4-7 ~/bin/k6 run catalog-reads.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8787';
const VUS = Number(__ENV.VUS || 150);
const DURATION = __ENV.DURATION || '30s';
const FAILED = new Rate('failed_requests');

export const options = {
  scenarios: {
    cached_reads: {
      // Ramp to find the sustainable plateau, then hold to measure it.
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: VUS }, // ramp: find the plateau
        { duration: DURATION, target: VUS }, // hold: steady-state numbers
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    failed_requests: ['rate<0.01'],
    // Gate the exit code on check failures too: any failed check (including
    // 'not degraded') must fail the run, not just the status rate above.
    checks: ['rate>0.99'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/cache/now`);
  let data = null;
  try {
    data = res.json();
  } catch (error) {
    // Non-JSON body (e.g. a proxy error page): keep data null so the
    // 'not degraded' check fails and FAILED.add still runs below.
    data = null;
  }
  check(res, {
    'GET /cache/now -> 200': (r) => r.status === 200,
    // Non-drill runs must fail loudly on an outage: the drill (drills.md)
    // verifies the degraded path deliberately with curl probes, so this
    // check only guards regression runs against an unintended outage.
    'not degraded': () => data !== null && data.degraded !== true,
  });
  FAILED.add(res.status !== 200);
}
