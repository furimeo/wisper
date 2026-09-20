# Changelog

All notable changes to wisper are documented here. The panel and the node
daemon are versioned together — a tag `vX.Y.Z` builds both.

## v0.1.11 — 2026-09-20

### Fixed

- **i18n: raw plural syntax leaking into the UI.** 15 catalog entries across
  `files.json`, `service.json` and `deploy.json` stored ICU plural syntax
  inside plain strings (`{count, plural, one {volume} other {volumes}}`),
  which the translation engine does not parse. Converted to proper `{one,
  other}` objects (English) and plain strings (Vietnamese). The visible
  symptom was `{count, plural, one {volume} other {volumes}}` rendering
  verbatim under volume counts.

- **Missing `files.browser.show_hidden` key.** The checkbox in the file
  manager rendered the raw key instead of "Show hidden files". Added to both
  locales.

- **Upload progress bar vanishing on interrupted uploads.** The aggregate
  percent excluded `interrupted` items, so the bar disappeared the moment a
  flaky connection stalled an upload — exactly when the customer needed to
  see it. `interrupted` uploads now count toward `busy` and the aggregate
  percent, and the status strip distinguishes "uploading" from "waiting to
  resume" with different copy and color.

- **Volume row localization hack.** `VolumeRow` decided between "used" and
  "đã dùng" by comparing `t('service.shortcuts.set') === 'set'` — a
  string-comparison locale switch. Replaced with a proper
  `service.volumes.used_word` key.

### Added

- **Folder upload.** Dragging a directory into the file manager previously
  showed an error. It now walks the tree via `webkitGetAsEntry`, enqueues
  every file with its relative path, and the node reconstructs the
  directory structure at completion (`MkdirAll` on the parent). A "Choose
  folder" button was added to the upload dialog. Empty folders produce a
  notice; the existing "upload zip → Extract" flow remains as an
  alternative.

- **Copy volume mount path.** Each volume row now has a `CopyButton` next to
  its mount path, so the customer can copy it and paste into Terminal or an
  env var — making the volume usable as a real disk.

- **Admin password reset API + tests.** `AdminSetPassword` use-case and two
  commercial API endpoints (`POST /accounts/{id}/password`,
  `POST /accounts/password-by-email`) for billing dashboard self-service
  password sync. Added `AdminSetPasswordTest` (6 tests) and 7
  `CommercialApiControllerTest` tests covering the happy path, rejection
  paths and admin-only authorization.

- **FastProvision idempotency.** `FastProvisionServer` now checks for an
  existing service with the same slug before creating, so a retry after a
  billing dashboard crash reuses the service instead of hitting
  `QuotaExceeded`.
