# itsme in a single page application: the guide for the SPA developer

This page is for the developer of the frontend. It lists the calls the SPA makes, in order, with their requests and answers. You do not need to know OpenID Connect: the backend does all of it. The reasoning and the attacks are in the README, [Hand-over](../README.md#hand-over). The demo SPA, `src/main/resources/static/index.html`, does everything below in about 150 lines of plain JavaScript.

## The idea in four sentences

itsme sends the browser back to the **backend**, not to the SPA, and that request cannot answer with JSON. So the backend sends the browser back to the SPA with a **one-time code** in the URL fragment (`#...`). The SPA then posts that code to the backend and gets the real answer as JSON. To prove it is the same browser that started, the SPA keeps a secret **browser code verifier**, sends only its hash (the **browser code challenge**) at the start, and the verifier itself with the code.

The application uses **no cookie**. After the login, the SPA calls the API with `Authorization: Bearer <token>`.

## Browser code verifier and browser code challenge

> Not to be confused with PKCE's `code_verifier` and `code_challenge`. Those are between the backend and itsme, and the SPA never sees them. The browser pair is between the SPA and the backend.

Before each itsme flow (login or confirmation), generate a new verifier, keep it in `localStorage`, and compute its challenge:

```js
const base64url = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function newBrowserCodeChallenge() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));     // 256 random bits: the secret
  localStorage.setItem('browser-code-verifier', verifier);
  return base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));   // its hash
}
```

- The **challenge** is sent at the start. It proves nothing by itself.
- The **verifier** stays in the browser until the end of the flow, then is sent once with the code.
- Why `localStorage`: the itsme app may bring the customer back in a new tab of the same browser. Read the verifier once at the end of the flow, then remove it.
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

{"handover": "<code>", "browserCodeVerifier": "<the verifier from localStorage>"}
```

| Answer | Meaning |
|---|---|
| `200 {"accessToken": "...", "tokenType": "Bearer", "expiresIn": 900, "customerId": "C-1001", "displayName": "Alex Martin"}` | Logged in. Keep the token (the demo keeps it in `sessionStorage`) |
| `400 {"error": "invalid_handover"}` | Too late (more than 60 seconds), already used, or another browser. Ask the customer to log in again |

The code works **once**, even when the answer is an error.

## Confirmation of an operation: three steps

The customer is logged in: every call sends `Authorization: Bearer <token>`.

Each kind of operation has its own start endpoint, and all of them end with the same redemption (README: "Several operations, one callback"). The record to confirm (the order, the offer) already exists on the server; its id is in the start URL.

### 1. Start

```http
POST /api/orders/O-2001/confirm          (or POST /api/offers/F-3001/accept)
Authorization: Bearer <token>
Content-Type: application/json

{"browserCodeChallenge": "<a new browser code challenge>"}
```

You send no text and no amount: the backend reads the record for the logged-in customer and builds the text shown in the itsme app.

| Answer | What to do |
|---|---|
| `200 {"redirect": "https://..."}` | `location.assign(redirect)`: the customer goes to itsme. Keep the token where it survives this page change (the demo uses `sessionStorage`) |
| `400 {"error": "operation_not_found"}` | The record does not exist, is another customer's, or is no longer open |
| `400 {"error": "<code>"}` | Show the message for that code |

### 2. Back from itsme

| Address | Meaning |
|---|---|
| `/#confirmation=<code>` | itsme approved it, and the backend checked the answer. **Nothing has been executed yet**: redeem the code (step 3) |
| `/confirmation-error?error=<code>` | Refused or failed: show the message for that code. Nothing was changed |

Read and remove the fragment, as for the login.

### 3. Redeem the code: this executes the operation

```http
POST /api/confirmation/redeem
Authorization: Bearer <token>
Content-Type: application/json

{"handover": "<code>", "browserCodeVerifier": "<the verifier from localStorage>"}
```

| Answer | Meaning |
|---|---|
| `200 {"operationType": "order", "operationReference": "O-2001"}` | Confirmed and executed. Reload that record (below) |
| `400 {"error": "invalid_handover"}` | Too late, already used, or another browser. Nothing was changed |
| `400 {"error": "wrong_customer"}` | Started by another customer. Nothing was changed |
| `400 {"error": "operation_not_found"}` | The record changed in the meantime (for example, already confirmed). Nothing was changed |
| `401` | The token has expired. Nothing was changed |

### 4. Show the result: reload the record from the server

Leaving for itsme unloads the SPA: everything held in JavaScript memory is gone when the customer comes back, on `/`. Do not try to keep the screen's state in the browser. Rebuild it from the server:

| `operationType` | Reload with |
|---|---|
| `order` | `GET /api/orders/{operationReference}` |
| `offer` | `GET /api/offers/{operationReference}` |

```http
GET /api/orders/O-2001
Authorization: Bearer <token>
```

`200 {"orderId": "O-2001", "description": "Smart thermostat", "amount": 149.00, "status": "CONFIRMED"}`, or `404` if it is not this customer's.

Route to the record's own page from there if the application has one (for example `/orders/O-2001`). The type and reference come from the redemption's answer, so no return address is needed in a URL.

Why not keep the order in `localStorage` before leaving:

- **The server's copy is the one that counts.** The text shown in itsme is built from the stored record, and the operation is executed on it. A browser copy can differ: a status or a price changed, or the confirmation itself just changed the status.
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

The 60 seconds only cover the page load after the callback: redeem the code as soon as the page starts. In every case nothing is executed; offer to start again, never retry with the same code.

## Entry points: what handles each request

The login's first two requests are not handled by one of our controllers: Spring Security's `oauth2Login()` answers them with two filters, and our code plugs into those filters. Our own controllers start at the redemption.

| Request | Handled by | Our code involved |
|---|---|---|
| `POST /oauth2/authorization/itsme` | Spring's `OAuth2AuthorizationRequestRedirectFilter` | `ItsmeAuthorizationRequestResolver` (step 1: `ItsmeHandover.begin`), `JdbcAuthorizationRequestRepository` |
| `GET /login/oauth2/code/itsme` (from itsme) | Spring's `OAuth2LoginAuthenticationFilter` | `ItsmeOidcUserService`, then `ItsmeLoginSuccessHandler` (step 2: `ItsmeHandover.complete`) or `ItsmeLoginFailureHandler` |
| `POST /itsme/login/handover` | `ItsmeLoginHandoverController` | step 3: `ItsmeHandover.redeem`, then `LoginHandover` |
| `POST /api/orders/{id}/confirm`, `POST /api/offers/{id}/accept` | `DemoConfirmationController` | `ConfirmableOperations.prepare`, then `ItsmeConfirmationService.start` (step 1) |
| `GET /itsme/confirmation/callback` (from itsme) | `ItsmeConfirmationCallbackController` | `ItsmeConfirmationService.complete` (step 2) |
| `POST /api/confirmation/redeem` | `DemoConfirmationController` | `ItsmeConfirmationService.redeem` (step 3), then `ConfirmableOperations.execute` |
| `GET /api/orders/{id}`, `GET /api/offers/{id}` | `DemoController` | the record, for this customer only |

## Do and do not

- **Do** keep the browser code verifier in the browser until step 3; send only the challenge at the start.
- **Do** remove the fragment from the address bar as soon as you have read the code.
- **Do** write server data with `textContent`, never with `innerHTML`: a script injected into the page could read the token and the verifier.
- **Do not** put the verifier, the token or any customer data in a URL.
- **Do not** keep the order, offer or any business data in `localStorage` or `sessionStorage` across the round trip: reload it by id.
- **Do not** retry a redemption that failed: the code is gone. Start the flow again.
- **Do not** display the `error` parameter from the URL: anyone can change it. Map each code to a fixed message.

## Trying it

Run the application with the `local` profile (README: "Run the login in a browser"), open <http://localhost:8080>, and watch the network tab of the browser's developer tools: the form POST, the redirects, then the AJAX redemption. No request or answer carries a cookie.
