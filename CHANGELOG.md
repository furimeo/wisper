# Changelog

All notable changes to wisper are documented here. The panel and the node
daemon are versioned together — a tag `vX.Y.Z` builds both.

## v0.1.13 — 2026-09-20

### Fixed

- **Upload 422 errors now return JSON, not HTML.** When the node rejected a
  chunk (`FileOperationFailed`) or the session was unknown, the panel
  returned an HTML error page. The browser's `fetch` client could not parse
  it, so the customer saw "The panel answered 422" instead of the real
  reason. Upload endpoints now have `@ExceptionHandler` methods that return
  JSON (`{message, code}`) for `FileOperationFailed`, `FileOperationTimedOut`,
  `UploadSessionUnknown`, `PathRejected` and `NodeOffline`.

- **Client recovery for 409/422-UNKNOWN_SESSION.** When the panel restarts
  mid-upload and the session registry is lost, the client now reopens the
  session and resumes from where the node left off instead of marking the
  upload as permanently failed.

## v0.1.12 — 2026-09-20

### Fixed

- **Upload 500 on chunk endpoint.** `FileOperationFailed`,
  `FileOperationTimedOut`, `NodeOffline` and `TerminalUnavailable` had no
  `@ResponseStatus`, so when the node rejected or timed out on a chunk the
  panel returned 500 instead of 422/503/504. The browser could not
  distinguish a retryable refusal from a server crash, so the upload stalled
  at "0 bytes on 1.4 MB".

- **Duplicate item count in the file list.** The list footer and the
  status bar both rendered a count ("1 mục" / "1 mục") on two lines. The
  footer now only shows "showing X of Y" when paging — the status bar owns
  the count.

- **File manager trapped inside a Card frame.** Replaced the `Card` wrapper
  with a plain full-bleed container so the listing, path bar and status bar
  fill the page width like a real file manager instead of sitting inside a
  bordered box.

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
