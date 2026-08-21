# KeyQuest CMS

Internal admin SPA for the KeyQuest piano learning app (plan §14) — Vue 3 +
Vite + TypeScript + Pinia. Served as a SPA against the same API as the app,
admin-scoped.

## Purpose (plan §14)

- **Song intake** — upload MusicXML/MIDI + stems → pipeline runs (§8.2) →
  validation report (range, voice/hand sanity, chunking suggestions).
- **Interactive song editor** (flagship) — piano-roll + staff preview,
  hands/fingering/chunk editing, per-chunk teaching mode, audition,
  arrangement level + skill tags, version diff, one-click publish.
- **Curriculum builder** — drag-drop lessons/steps, skill tagging, prerequisite
  DAG visualization (cycle detection!), preview-as-device.
- **Catalog ops** — rights-provenance metadata (mandatory before publish), tier
  assignment, release calendar.
- **Analytics dashboards** — funnel, per-lesson drop-off, per-measure error
  heatmaps, detection-accuracy telemetry.
- **Remote config editor** — staged rollout + kill switches.

## Dev

Requires Node 20.19+ / 22+ (Vite 6).

```bash
npm install        # first install generates package-lock.json (not committed yet)
npm run dev        # Vite dev server on http://localhost:5173
npm run build      # vue-tsc -b && vite build
npm run preview    # preview the production build
npm run lint       # ESLint (flat config)
```

The dev server proxies `/api` to `http://localhost:8787` (Webman's default
listen) — see `vite.config.ts`. All dependencies are pinned to exact versions;
upgrade deliberately.

## Auth

The CMS is admin-scoped: same API as the app, with role checks on the backend.
The auth store stub (`src/stores/auth.ts`) is wired in P2.B1.