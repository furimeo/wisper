# Panel HTTP

Binding. See [README.md](README.md).

## Who owns which prefix

A package may map any path under the prefixes it owns and no others. Two packages
mapping the same path is a startup failure with a message nobody enjoys reading, and the
point of this table is that it never happens.

| Prefix | Package | Notes |
|---|---|---|
| `/` | `project` | The dashboard. One route, and it belongs to whoever shows the customer their projects. |
| `/login`, `/login/**`, `/logout` | `auth` | Public. `/login/**` covers the second-factor challenge. |
| `/settings/**` | `auth` | Profile, password, 2FA, API tokens. |
| `/orgs/**` | `org` | Members, roles, plan and quota. |
| `/projects/**` | `project` | |
| `/services/**` | `service` | The service itself: overview, environment, volumes, scheduled tasks. |
| `/services/{id}/deployments/**` | `deploy` | Owned by `deploy`, nested under the service it belongs to. |
| `/services/{id}/domains/**` | `domain` | Domains and certificates. |
| `/services/{id}/files/**` | `files` | The file manager, including chunked upload. |
| `/services/{id}/terminal/**` | `files` | Terminal sessions - same agent, same authorization surface. |
| `/services/{id}/logs/**`, `/services/{id}/metrics/**` | `stats` | |
| `/databases/**` | `database` | Customer-facing managed databases. |
| `/backups/**` | `backup` | |
| `/webhooks/**` | `deploy` | Public. Authenticated by the per-service secret in the request. |
| `/admin/**` | whoever owns the screen | Requires `ROLE_ADMIN`. Nodes under `/admin/nodes`, placement under `/admin/placement`, the shared database engines under `/admin/databases`, the audit log under `/admin/audit`. |
| `/api/v1/**` | the owning domain package | Token-authenticated, CSRF-exempt. |
| `/install.sh`, `/dist/**` | `node` | Public. The installer script and the signed binary manifest (design §7.1). |
| `/health`, `/error`, `/assets/**` | `web` | Already implemented. Do not remap. |

Nesting a child under its parent's path is deliberate: `/services/41/files` cannot be
reached without a service id, so the authorization check has something to check. A
flat `/files?serviceId=41` invites the version of the handler that forgets to look.

## Controllers return a view name

```java
@GetMapping("/services/{id}/deployments")
String list(@PathVariable long id, Model model) {
    model.addAttribute("deployments", deploymentRepository.findByServiceId(id));
    return "deploy/DeploymentList";
}
```

The view name is the React component, and it resolves by convention:

```
"deploy/DeploymentList"  ->  frontend/src/features/deploy/DeploymentListPage.tsx
"files/FileManager"      ->  frontend/src/features/files/FileManagerPage.tsx
"auth/SignIn"            ->  frontend/src/features/auth/SignInPage.tsx
```

`<feature>/<PageName>`, and the file is `<PageName>Page.tsx` inside `features/<feature>/`.
The feature folder matches the Java package. A file that does not end in `Page.tsx` is
not routable, which is what stops a helper component becoming a page by accident.

There is no client-side routing table, so there is no second place for a permission rule
to live. That is the whole reason for Inertia over a JSON API.

## Requests are form data

The Inertia client is configured with `forceFormData: true`, so every body arrives as
`multipart/form-data`. Bind it with `@ModelAttribute` on a record, or `@RequestParam`:

```java
record CreateService(@NotBlank String name, @NotNull ServiceKind kind) {}

@PostMapping("/projects/{id}/services")
String create(@PathVariable long id, @Valid @ModelAttribute CreateService form,
              BindingResult errors, RedirectAttributes flash) { ... }
```

Do not use `@RequestBody`. Nothing sends JSON to the panel except `/api/v1/**`, which
authenticates with a token and is not called by this client.

## Use GET and POST

Inertia sends the real HTTP method, and `InertiaRedirectFilter` turns a redirect after
PUT/PATCH/DELETE into a 303 so the follow-up is a GET rather than a repeat of the
original method. That works, but multipart parsing on PUT depends on container
behaviour the servlet specification does not guarantee. Prefer `router.post` for every
write; it is what the rest of the panel does.

## Answering a write

Redirect. Always. A POST that renders a page directly leaves the browser on a URL that
cannot be reloaded, and the customer finds out by pressing F5.

```java
if (errors.hasErrors()) {
    InertiaFlash.errors(flash, errors);
    return "redirect:/projects/" + id + "/services/new";
}
InertiaFlash.success(flash, "Service created.");
return "redirect:/services/" + service.id();
```

`errors` and `flash` are shared props on every page - the client reads
`useSharedProps()` from `@/inertia/sharedProps` and never has to check for undefined.
Field names in `errors` are the `name` attributes of the inputs that produced them.

## Failures

Throw. `NotFoundException` covers both "no such record" and "not yours", on purpose: a
403 on somebody else's resource tells an attacker which ids exist. Everything reaching
`/error` renders `features/error/ErrorPage.tsx`, which is a real page and not a white
frame.

## Adding to the security chain

`SecurityConfig` holds the request-to-role map and nothing else. To add an
authentication mechanism - the API token filter, a second-factor gate - declare a
`@Component` implementing `HttpSecurityContribution` in your own package. To add a prop
every page needs, implement `SharedPropsContributor`. Neither requires editing a shared
file.

If you need a path to be reachable without signing in and it is not in `PUBLIC_PATHS`,
that is a change to `SecurityConfig` and therefore a change to this contract. Raise it;
do not work around it.

## Streaming

Realtime is Server-Sent Events: `SseEmitter` from an ordinary MVC controller, on a
virtual thread. Deployment logs, container logs and metrics all use it. Set the response
`Cache-Control: no-store` and send a comment line as a keepalive - a tunnel will close an
idle stream.

The terminal is the single exception, and it is not a precedent. Those three streams are
one-way; a shell is not, and its input over HTTP was one `POST` per keystroke - each one
through the whole filter chain and a lookup of the account. `/services/{id}/terminal/socket`
is a WebSocket, authorised once by a `HandshakeInterceptor` and never again (pages.md §7).
Adding a second one needs the same argument: that the traffic is two-way and frequent
enough that per-request authentication is the cost. Wanting push is not that argument -
SSE already pushes.

`web.WebSocketConfig` carries `@EnableWebSocket` and nothing else. A package that owns a
path registers its own `WebSocketConfigurer`, the way `HttpSecurityContribution` works, so
the shared file never learns what the handler is for.
