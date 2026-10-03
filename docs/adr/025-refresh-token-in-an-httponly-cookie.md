# ADR-025: The Refresh Token Lives in an HttpOnly Cookie, Never in Page Scripts

## Status
Accepted

## Context
The web app needs to keep a person signed in across page reloads and to refresh a short-lived access token. A refresh token stored where page scripts can read it (`localStorage`, `sessionStorage`, a JavaScript variable that outlives the page) is stolen by any cross-site-scripting bug, and a stolen refresh token is a long-lived session. The platform already rotates refresh tokens and revokes a session on reuse (party-authentication ADR-021), which limits the damage but does not prevent the theft.

## Decision
- The browser holds the refresh token only in a cookie the gateway sets: **HttpOnly** (scripts cannot read it), **Secure** (https only; switched off by `gateway.session-cookie.secure=false` only for plain-http local development), **SameSite=Strict**, and scoped by `Path` to the gateway's session endpoints (`/api/gateway/v1/session`, as the browser sees them behind the web app's `/api` prefix). The access token is short-lived and lives only in the page's memory.
- Two gateway-owned endpoints, outside every BIAN domain and not proxied (they authenticate with the cookie): `POST /gateway/v1/session/refresh` exchanges the cookie for a new access token (it rotates the session at party-authentication and sets the rotated cookie; the response body never contains the refresh token) and `POST /gateway/v1/session/logout` revokes the session and clears the cookie.
- The identity-federation callback is the only other place a refresh token appears: its answer is JSON; the gateway intercepts `GET /identity-federation/v1/login/callback`, moves the refresh token into the cookie and redirects the browser to the web app (`complete-url`), where the app calls `session/refresh` to get its access token. A failed sign-in redirects to `login-url?sso_error=<ERR code>`: the error code only, validated against a strict pattern, never the provider's words. No token ever travels through a URL.
- **CSRF**: besides SameSite=Strict, both endpoints require `X-Requested-With: thinklab-web`, a header a cross-site page cannot add without a CORS preflight the gateway never grants (`403 ERR-GTW-00403` otherwise). No cookie, or a refresh the identity service refuses, is `401 ERR-GTW-00401` and the cookie is left for the app to discard by signing in again.
- `party-authentication`'s `session/federated` (ADR-022 there) is internal: it is on `gateway.denied-paths` (404 from outside), like `token/service`.
- The cookie value is never logged.

## Consequences
- Positive: an XSS bug can no longer exfiltrate a refresh token; reload keeps the session; logout really ends it.
- Negative: the gateway knows the browser-visible path and must be configured with it; a deployment under another prefix changes `GATEWAY_SESSION_COOKIE_PATH`. The access token in memory is lost on reload and re-fetched from the cookie (one extra request on load).
