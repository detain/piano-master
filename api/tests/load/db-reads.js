// P0.6.5 load baseline — MySQL read scenario (optional, cheap).
//
// GET /db/version runs SELECT VERSION() through the MySQL pool. It is the
// proxy for the "uncached catalog read" class: one MySQL round-trip per
// request. Included so the report can show cached (Dragonfly) vs uncached
// (MySQL) read cost side by side on the same box.
//
// Usage:
//   taskset -c 4-7 ~/bin/k6 run db-reads.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8787';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '20s';
const FAILED = new Rate('failed_requests');

export const options = {
  scenarios: {
    db_reads: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: VUS },
        { duration: DURATION, target: VUS },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    failed_requests: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/db/version`);
  check(res, { 'GET /db/version -> 200': (r) => r.status === 200 });
  FAILED.add(res.status !== 200);
}