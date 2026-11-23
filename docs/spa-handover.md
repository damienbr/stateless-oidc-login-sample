# itsme in a single page application: the guide for the SPA developer

This page is for the developer of the frontend. It lists the calls the SPA makes, in order, with their requests and answers. You do not need to know OpenID Connect: the backend does all of it. The reasoning and the attacks are in the README, [Hand-over](../README.md#hand-over). The demo SPA, `src/main/resources/static/index.html`, does everything below in about 150 lines of plain JavaScript.

## The idea in five sentences

itsme sends the browser back to the **backend**, not to the SPA, and that request cannot answer with JSON. So the backend sends the browser back to the SPA with a **one-time code** in the URL fragment (`#...`); the SPA posts that code to the backend and gets a token as JSON. To prove it is the same browser that started the flow, the SPA keeps a secret **browser code verifier**, sends only its hash (the **browser code challenge**) at the start, and the verifier itself with the code. The **login** and the **confirmation** of an operation both work this way. For a confirmation, the backend executes the operation as soon as the customer has confirmed in itsme, before the SPA redeems anything; the SPA then gets a **new token** and the reference of the record to reload.

The application uses **no cookie**. After the login, the SPA calls the API with `Authorization: Bearer <token>`.

## Browser code verifier and browser code challenge

> Not to be confused with PKCE's `code_verifier` and `code_challenge`. Those are between the backend and itsme, and the SPA never sees them. The browser pair is between the SPA and the backend.

Before each login and each confirmation, generate a new verifier, keep it in `sessionStorage`, and compute its challenge:

```js
const base64url = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function newBrowserCodeChallenge() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));     // 256 random bits: the secret
  sessionStorage.setItem('browser-code-verifier', verifier);
  return base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));   // its hash
}
```

- The **challenge** is sent at the start. It proves nothing by itself.
- The **verifier** stays in the browser until the end of the flow, then is sent once with the code.
- Why `sessionStorage`: the itsme flow comes back in the tab that started it, and each tab has its own `sessionStorage`. Flows started in two tabs at the same time keep their own verifier; with `localStorage` the second would overwrite the first, and both would fail. Read the verifier once at the end of the flow, then remove it.
- `crypto.subtle` works on `https://` pages and on `http://localhost`.

## Login: three steps

### 1. Start: a form POST

The browser must leave the SPA and go to itsme, so the start is a real form submission, not `fetch`. The hash takes a moment to compute: stop the submission, compute, then submit.

```html
<form method="post" action="/oauth2/authorization/itsme" id="login-form">
  <input type="hidden" name="browser_code_challenge" id="login-challenge">
  <button type="submit">Log in with itsme</button>
</form>
<script>
  document.getElementById('login-form').addEventListener('submit', async event => {
    event.preventDefault();
    document.getElementById('login-challenge').value = await newBrowserCodeChallenge();
    event.target.submit();          // submit() does not fire this listener again
  });
</script>
```

### 2. Back from itsme

The browser comes back to the SPA, on one of these two addresses:

| Address | Meaning |
|---|---|
| `/#handover=<code>` | Success: redeem the code (step 3) |
| `/login-error?error=<code>` | Failure: show the message for that code (README: "Error codes"). Never display the raw parameter |

As soon as the page loads, read the code and remove it from the address bar:

```js
const fragment = new URLSearchParams(location.hash.slice(1));
history.replaceState(null, '', location.pathname + location.search);
```

### 3. Redeem the code

```http
POST /itsme/login/handover
Content-Type: application/json

{"handover": "<code>", "browserCodeVerifier": "<the verifier from sessionStorage>"}
```

| Answer | Meaning |
|---|---|
| `200 {"accessToken": "...", "tokenType": "Bearer", "expiresIn": 900, "customerId": "C-1001", "displayName": "Alex Martin"}` | Logged in. Keep the token (the demo keeps it in `sessionStorage`) |
| `400 {"error": "invalid_handover"}` | Too late (more than 60 seconds), already used, or another browser. Ask the customer to log in again |

The code works **once**, even when the answer is an error.

## Confirmation of an operation: three steps, then reload

The customer is logged in: the start call sends `Authorization: Bearer <token>`.

Each kind of operation has its own start endpoint, and all of them end the same way (README: "Several operations, one callback"). The record may not exist yet: an order is created by its confirmation. Do not create anything before: send what the customer chose, and the backend keeps it until itsme has confirmed.

### 1. Start

```http
POST /api/orders/confirm
Authorization: Bearer <token>
Content-Type: application/json

{"productCode": "P-100", "browserCodeChallenge": "<a new challenge>"}
```

To accept an offer, which already exists:

```http
POST /api/offers/F-3001/accept
Authorization: Bearer <token>
Content-Type: application/json

{"browserCodeChallenge": "<a new challenge>"}
```

You send what the customer chose, never a text, a description or an amount: the backend reads the price, persists the data of the operation for the logged-in customer, and builds the text shown in the itsme app.

| Answer | What to do |
|---|---|
| `200 {"redirect": "https://..."}` | `location.assign(redirect)`: the customer goes to itsme. The verifier is already in `sessionStorage` |
| `400 {"error": "operation_not_found"}` | The product does not exist, or the offer does not exist, is another customer's, or is no longer open |
| `400 {"error": "invalid_request"}` | No valid `browserCodeChallenge` in the body |
| `400 {"error": "<code>"}` | Show the message for that code |

### 2. Back from itsme

When the customer confirms in the itsme app, the backend checks itsme's answer and **executes the operation at once**: the order is created, the offer is accepted. This does not wait for step 3.

| Address | Meaning |
|---|---|
| `/#confirmation=<code>` | Confirmed in itsme and executed: redeem the code (step 3) |
| `/confirmation-error?error=<code>` | Refused or failed: show the message for that code. Nothing was changed |

Read and remove the fragment, as for the login.

### 3. Redeem the code: a new token

```http
POST /itsme/confirmation/handover
Content-Type: application/json

{"handover": "<code>", "browserCodeVerifier": "<the verifier from sessionStorage>"}
```

No `Authorization` header is needed: the token of the login may have expired while the customer was in the itsme app.

| Answer | Meaning |
|---|---|
| `200 {"accessToken": "...", "tokenType": "Bearer", "expiresIn": 900, "customerId": "C-1001", "displayName": "Alex Martin", "operationType": "order", "operationReference": "O-3F9A61C27B04D5E8"}` | Replace the token you hold with this one, then reload the record (below) |
| `400 {"error": "invalid_handover"}` | Too late (more than 60 seconds), already used, or another browser |

The code works **once**, even when the answer is an error.

A refused redemption does **not** mean that nothing happened: the operation was executed in step 2. Do not tell the customer to confirm again. Say that the page could not be refreshed, and let them log in again to see the record.

### 4. Show the result: reload the record from the server

Leaving for itsme unloads the SPA: everything held in JavaScript memory is gone when the customer comes back, on `/`. Do not try to keep the screen's state in the browser. Rebuild it from the server, with `operationType` and `operationReference` from step 3:

| `operationType` | Reload with |
|---|---|
| `order` | `GET /api/orders/{operationReference}` |
| `offer` | `GET /api/offers/{operationReference}` |

```http
GET /api/orders/O-3F9A61C27B04D5E8
Authorization: Bearer <the new token>
```

`200 {"orderId": "O-3F9A61C27B04D5E8", "description": "Smart thermostat", "amount": 149.00, "status": "CONFIRMED"}`, or `404` if it is not this customer's.

Route to the record's own page from there if the application has one (for example `/orders/O-3F9A61C27B04D5E8`).

Why not keep the order in `localStorage` before leaving:

- **The server's copy is the one that counts.** The text shown in itsme is built from the data the backend stored, and the order is created from that data. A browser copy can differ, and it does not have the order's id, which only exists after the confirmation.
- **The customer can edit it.** The server never trusts it, but a screen rebuilt from it would show edited data as confirmed.
- **Any script on the page can read it, and it stays** after logout, across tabs, for the next person on a shared computer.
- **It may not be there.** Another tab, another browser, cleared storage.

The browser code verifier is different: random, single use, useless after 60 seconds, and it carries no business data.

## Timers

| What | Duration | Starts | If exceeded |
|---|---|---|---|
| Approving in the itsme app | 5 minutes | the start request | callback → `confirmation_not_found` (login: `authorization_request_not_found`) |
| Redeeming the one-time code | 60 seconds | the callback | `invalid_handover` |
| Demo token | 15 minutes | the login | `401` |

The 60 seconds only cover the page load after the callback: redeem the code as soon as the page starts. Never retry with the same code. When the 5 minutes are exceeded, nothing is executed: offer to start again. When the 60 seconds are exceeded after a confirmation, the operation is already executed: the customer logs in again to see it.

## Entry points: what handles each request

The login's first two requests are not handled by one of our controllers: Spring Security's `oauth2Login()` answers them with two filters, and our code plugs into those filters. Our own controllers start at the redemption.

| Request | Handled by | Our code involved |
|---|---|---|
| `POST /oauth2/authorization/itsme` | Spring's `OAuth2AuthorizationRequestRedirectFilter` | `ItsmeAuthorizationRequestResolver` (step 1: `ItsmeHandover.begin`), `JpaAuthorizationRequestRepository` |
| `GET /login/oauth2/code/itsme` (from itsme) | Spring's `OAuth2LoginAuthenticationFilter` | `ItsmeOidcUserService`, then `ItsmeLoginSuccessHandler` (step 2: `ItsmeHandover.complete`) or `ItsmeLoginFailureHandler` |
| `POST /itsme/login/handover` | `ItsmeLoginHandoverController` | step 3: `ItsmeHandover.redeem`, then `LoginHandover` |
| `POST /api/orders/confirm`, `POST /api/offers/{id}/accept` | `DemoConfirmationController` | `ConfirmableOperationDispatcher.prepare` (the data is persisted in `pending_operation`), then `ItsmeConfirmationService.start` (step 1: `ItsmeHandover.begin`) |
| `GET /itsme/confirmation/callback` (from itsme) | `ItsmeConfirmationCallbackController` | `ItsmeConfirmationService.complete` (step 2): the checks, then `ConfirmedOperationExecutor` (`ConfirmableOperationDispatcher.execute`: the data is reloaded, then the business logic), then `ItsmeHandover.complete` |
| `POST /itsme/confirmation/handover` | `ItsmeConfirmationHandoverController` | step 3: `ItsmeHandover.redeem`, then `ConfirmationHandover` |
| `GET /api/orders/{id}`, `GET /api/offers/{id}` | `DemoController` | the record, for this customer only |

## Do and do not

- **Do** keep the browser code verifier in the browser until step 3; send only the challenge at the start.
- **Do** remove the fragment from the address bar as soon as you have read the code.
- **Do** write server data with `textContent`, never with `innerHTML`: a script injected into the page could read the token and the verifier.
- **Do not** put the verifier, the token or any customer data in a URL.
- **Do not** keep the order, offer or any business data in `localStorage` or `sessionStorage` across the round trip: reload it by id.
- **Do not** retry a redemption that failed: the code is gone. After a login, start the login again. After a confirmation, do not start the confirmation again: it is executed.
- **Do not** create the order, or anything else, before the confirmation: send the customer's choice to the start endpoint, and show the record the API returns afterwards.
- **Do** replace the token with the one the confirmation returns.
- **Do not** display the `error` parameter from the URL: anyone can change it. Map each code to a fixed message.

## Trying it

Run the application with the `local` profile (README: "Run the login in a browser"), open <http://localhost:8080>, and watch the network tab of the browser's developer tools: the form POST, the redirects, then the AJAX redemption of the login; for a confirmation, the start call, the redirects, the AJAX redemption, then the reload of the record. No request or answer carries a cookie.
