# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Active task

Setting up the deploy pipeline (Cloud Run + Cloud Build + Firestore,
reusing the `foodie-503510` GCP project and `northamerica-northeast1`
region — see `context.md`'s Configuration reference).

- [x] `cloudbuild.yaml` + `backend/Dockerfile` added, Ktor Gradle plugin
      added to `build.gradle.kts` (needed for the `buildFatJar` task)
- [x] Firestore database `home-os` created manually (Montreal)
- [ ] Merge this to `main`, then create the Cloud Build trigger
      (GitHub push → `main`, config file `/cloudbuild.yaml`) — maintainer
      doing this manually per `CLAUDE.md`'s Deploy pipeline section
- [ ] First deploy via the trigger
- [ ] Maintainer adds OAuth client + redirect URI, and sets Cloud Run env
      vars (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `SESSION_SECRET`,
      `OAUTH_REDIRECT_BASE_URL`, `FIRESTORE_DATABASE_ID=home-os`) manually,
      after the first deploy gives them a Cloud Run URL
