// P0.6.7 coroutine-posture benchmark — outbound-HTTP fan-out (plan §13.4.3).
//
// Drives GET /bench/outbound (Phase-0 spike endpoint) which fans out N
// requests to the local mock upstream (api/tests/load/README.md "mock
// upstream" section; the threaded Python server in /tmp). mode=blocking runs
// N sequential Guzzle requests in the worker; mode=fiber runs N concurrent
// Workerman fibers joined with Workerman\Coroutine\Parallel (requires the
// Fiber event loop: WEBMAN_EVENT_LOOP="Workerman\Events\Fiber").
//
// This is the measurement behind docs/adr/0002-coroutine-posture.md — see
// that ADR for the decision. Run each mode against the correctly configured
// server and record the numbers in the ADR:
//
//   blocking (select loop): taskset -c 0-3 env WEBMAN_COUNT=4 php start.php start
//   fiber    (fiber loop):  taskset -c 0-3 env WEBMAN_COUNT=4 \
//                             WEBMAN_EVENT_LOOP="Workerman\Events\Fiber" php start.php start
//   then: taskset -c 4-7 ~/bin/k6 run outbound-fanout.js -e MODE=fiber
//
// A threaded upstream is REQUIRED for a fair comparison: the PHP built-in
// server serializes the sleep, which would fake a "no win" result.

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8787';
const MODE = __ENV.MODE || 'blocking';
const FANOUT = __ENV.FANOUT || '8';
const FAILED = new Rate('failed_requests');

export const options = {
  scenarios: {
    outbound_fanout: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    failed_requests: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/bench/outbound?mode=${MODE}&fanout=${FANOUT}`);
  check(res, { 'GET /bench/outbound -> 200': (r) => r.status === 200 });
  FAILED.add(res.status !== 200);
}