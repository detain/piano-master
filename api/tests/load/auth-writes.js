// P0.6.5 load baseline — authenticated-write proxy (plan §13.6, §20 P0.6.5).
//
// POST /auth/echo with a Bearer token performs an Eloquent INSERT into
// skeleton_echo through the MySQL connection pool — the same shape the
// Phase-2 authenticated progress-write routes will have (token check +
// one DB write). It is a PROXY until those routes exist; the real routes
// will add rows to several tables plus outbox entries (§13.4.5), so expect
// the write budget to shift. See api/tests/load/README.md for caveats.
//
// Reference target (§13.6, 4 vCPU node): >= 800 req/s, p99 < 120 ms.
//
// Usage:
//   taskset -c 4-7 ~/bin/k6 run auth-writes.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8787';
const VUS = Number(__ENV.VUS || 60);
const DURATION = __ENV.DURATION || '40s';
const FAILED = new Rate('failed_requests');

export const options = {
  scenarios: {
    auth_writes: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: VUS }, // ramp: find the write plateau
        { duration: DURATION, target: VUS }, // hold: steady-state numbers
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    failed_requests: ['rate<0.01'],
  },
};

export default function () {
  const res = http.post(
    `${BASE_URL}/auth/echo`,
    JSON.stringify({ message: `k6-load-vu${__VU}-iter${__ITER}` }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer dev-token',
      },
    }
  );
  check(res, { 'POST /auth/echo -> 200': (r) => r.status === 200 });
  FAILED.add(res.status !== 200);
}