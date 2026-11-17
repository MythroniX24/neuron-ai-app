/**
 * Neuron AI — Google connectors backend.
 *
 * Cloudflare Worker (no database server needed). The Android app is thin: it
 * runs Google sign-in to obtain a server auth code, and from then on every
 * Google REST call is made HERE, by this Worker. The app never holds a Google
 * access token or refresh token.
 *
 * - The auth code is exchanged here for access + refresh tokens.
 * - The refresh token is stored ENCRYPTED (AES-GCM) in Workers KV.
 * - The app only ever holds an opaque, HMAC-signed session token.
 * - All Google calls live in `/google/*` endpoints below.
 *
 * Auth model
 * ----------
 * The first connected Google account establishes the backend user
 * (`users` key = that Google subject). Additional Google accounts are linked
 * under the same user by sending the existing session token as a bearer when
 * connecting. Every account id is prefixed with its owner's user id and is
 * verified against the session — a caller can never read or act on another
 * user's account by guessing an id.
 *
 * Session tokens are HMAC-SHA256 signed with BACKEND_AUTH_SECRET and expire
 * after 30 days. They are per-user, signed claims derived after Google proves
 * the identity — not a shared secret.
 *
 * Storage
 * -------
 * Workers KV is used instead of D1 because the deployment token in use has
 * Workers Scripts + KV access but no D1 permission. Keys:
 *   user:<userId>                       -> { createdAt }
 *   acct:<userId>:<googleSub>           -> AccountRecord (JSON)
 * Listings use the `acct:<userId>:` prefix.
 */

export interface Env {
  TOKENS: KVNamespace;
  GOOGLE_WEB_CLIENT_ID: string;
  GOOGLE_WEB_CLIENT_SECRET: string;
  TOKEN_ENCRYPTION_KEY: string;
  BACKEND_AUTH_SECRET: string;
  GOOGLE_REDIRECT_URI?: string;
}

const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
const ACCESS_TOKEN_SAFETY_SECONDS = 60;
const MAX_BODY_BYTES = 512 * 1024;
const MAX_CODE_LENGTH = 8192;
const MAX_ACCOUNT_ID_LENGTH = 512;

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke";
const GOOGLE_USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function corsHeaders(): Record<string, string> {
  // Native Android clients do not need CORS. We deliberately do NOT emit a
  // wildcard Access-Control-Allow-Origin, so browsers cannot call this API.
  return {
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
  };
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...corsHeaders() },
  });
}

function apiError(code: string, message: string, status: number): Response {
  return json({ error: code, message }, status);
}

function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

function getBearer(request: Request): string | null {
  const header = request.headers.get("authorization") || "";
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

async function readJsonBody(request: Request): Promise<Record<string, unknown> | null> {
  const length = Number(request.headers.get("content-length") || "0");
  if (length > MAX_BODY_BYTES) return null;
  try {
    const text = await request.text();
    if (text.length > MAX_BODY_BYTES) return null;
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function str(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > maxLength) return null;
  return trimmed;
}

function bool(value: unknown): boolean {
  return value === true;
}

function int(value: unknown, fallback: number, min: number, max: number): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(parsed)));
}

// ---------------------------------------------------------------------------
// Encoding / crypto
// ---------------------------------------------------------------------------

function bytesToB64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function b64UrlToBytes(value: string): Uint8Array {
  const pad = value.length % 4 === 0 ? "" : "=".repeat(4 - (value.length % 4));
  const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/") + pad);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/** Standard base64 of the UTF-8 bytes of [text] (Gmail raw messages). */
function b64FromUtf8(text: string): string {
  const bytes = encoder.encode(text);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

function b64UrlDecodeToString(data: string): string {
  try {
    return decoder.decode(b64UrlToBytes(data));
  } catch {
    return "";
  }
}

async function hmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

interface SessionPayload {
  uid: string;
  aid: string;
  email?: string;
  exp: number;
}

async function issueSession(env: Env, payload: SessionPayload): Promise<string> {
  const encoded = bytesToB64Url(encoder.encode(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign(
    "HMAC",
    await hmacKey(env.BACKEND_AUTH_SECRET),
    encoder.encode(encoded),
  );
  return `v1.${encoded}.${bytesToB64Url(new Uint8Array(signature))}`;
}

async function verifySession(env: Env, token: string): Promise<SessionPayload | null> {
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== "v1") return null;
  let valid = false;
  try {
    valid = await crypto.subtle.verify(
      "HMAC",
      await hmacKey(env.BACKEND_AUTH_SECRET),
      b64UrlToBytes(parts[2]),
      encoder.encode(parts[1]),
    );
  } catch {
    return null;
  }
  if (!valid) return null;
  try {
    const payload = JSON.parse(decoder.decode(b64UrlToBytes(parts[1]))) as SessionPayload;
    if (!payload || typeof payload.uid !== "string" || typeof payload.exp !== "number") return null;
    if (payload.exp < nowSeconds()) return null;
    return payload;
  } catch {
    return null;
  }
}

async function aesKey(secret: string): Promise<CryptoKey> {
  // Hash whatever the secret is into exactly 32 bytes so the operator is not
  // forced into a particular key encoding.
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  return crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, [
    "encrypt",
    "decrypt",
  ]);
}

interface Sealed {
  iv: string;
  ct: string;
}

async function seal(env: Env, plaintext: string, aad: string): Promise<Sealed> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: encoder.encode(aad) },
    await aesKey(env.TOKEN_ENCRYPTION_KEY),
    encoder.encode(plaintext),
  );
  return { iv: bytesToB64Url(iv), ct: bytesToB64Url(new Uint8Array(ciphertext)) };
}

async function unseal(env: Env, sealed: Sealed, aad: string): Promise<string> {
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: b64UrlToBytes(sealed.iv), additionalData: encoder.encode(aad) },
    await aesKey(env.TOKEN_ENCRYPTION_KEY),
    b64UrlToBytes(sealed.ct),
  );
  return decoder.decode(plaintext);
}

// ---------------------------------------------------------------------------
// Google OAuth
// ---------------------------------------------------------------------------

interface GoogleIdentity {
  sub: string;
  email?: string;
  name?: string;
  picture?: string;
}

interface GoogleTokens {
  accessToken: string;
  refreshToken?: string;
  expiresIn: number;
  scopes: string;
  identity: GoogleIdentity;
}

interface GoogleErrorBody {
  error?: string;
  error_description?: string;
}

async function googleTokenRequest(body: URLSearchParams): Promise<Response> {
  return fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      accept: "application/json",
    },
    body,
  });
}

function decodeIdToken(idToken: string): GoogleIdentity | null {
  try {
    const parts = idToken.split(".");
    if (parts.length < 2) return null;
    const claims = JSON.parse(decoder.decode(b64UrlToBytes(parts[1]))) as Record<string, unknown>;
    const sub = typeof claims.sub === "string" ? claims.sub : null;
    if (!sub) return null;
    return {
      sub,
      email: typeof claims.email === "string" ? claims.email : undefined,
      name: typeof claims.name === "string" ? claims.name : undefined,
      picture: typeof claims.picture === "string" ? claims.picture : undefined,
    };
  } catch {
    return null;
  }
}

async function fetchUserInfo(accessToken: string): Promise<GoogleIdentity | null> {
  try {
    const res = await fetch(GOOGLE_USERINFO_URL, {
      headers: { authorization: `Bearer ${accessToken}`, accept: "application/json" },
    });
    if (!res.ok) return null;
    const data = (await res.json()) as Record<string, unknown>;
    if (typeof data.sub !== "string") return null;
    return {
      sub: data.sub,
      email: typeof data.email === "string" ? data.email : undefined,
      name: typeof data.name === "string" ? data.name : undefined,
      picture: typeof data.picture === "string" ? data.picture : undefined,
    };
  } catch {
    return null;
  }
}

async function exchangeAuthorizationCode(
  env: Env,
  code: string,
): Promise<{ tokens: GoogleTokens } | { error: string; description?: string }> {
  const body = new URLSearchParams({
    code,
    client_id: env.GOOGLE_WEB_CLIENT_ID,
    client_secret: env.GOOGLE_WEB_CLIENT_SECRET,
    grant_type: "authorization_code",
  });
  if (env.GOOGLE_REDIRECT_URI) body.set("redirect_uri", env.GOOGLE_REDIRECT_URI);

  let res: Response;
  try {
    res = await googleTokenRequest(body);
  } catch {
    return { error: "google_unreachable" };
  }
  const data = (await res.json().catch(() => ({}))) as Record<string, unknown> & GoogleErrorBody;
  if (!res.ok) {
    return { error: data.error || "exchange_failed", description: data.error_description };
  }
  const accessToken = typeof data.access_token === "string" ? data.access_token : "";
  if (!accessToken) return { error: "no_access_token" };

  const identity =
    (typeof data.id_token === "string" ? decodeIdToken(data.id_token) : null) ||
    (await fetchUserInfo(accessToken));
  if (!identity) return { error: "no_identity" };

  return {
    tokens: {
      accessToken,
      refreshToken: typeof data.refresh_token === "string" ? data.refresh_token : undefined,
      expiresIn: typeof data.expires_in === "number" ? data.expires_in : 3600,
      scopes: typeof data.scope === "string" ? data.scope : "",
      identity,
    },
  };
}

async function refreshAccessToken(
  env: Env,
  refreshToken: string,
): Promise<{ accessToken: string; expiresIn: number; scopes: string } | { error: string }> {
  const body = new URLSearchParams({
    refresh_token: refreshToken,
    client_id: env.GOOGLE_WEB_CLIENT_ID,
    client_secret: env.GOOGLE_WEB_CLIENT_SECRET,
    grant_type: "refresh_token",
  });
  let res: Response;
  try {
    res = await googleTokenRequest(body);
  } catch {
    return { error: "google_unreachable" };
  }
  const data = (await res.json().catch(() => ({}))) as Record<string, unknown> & GoogleErrorBody;
  if (!res.ok) {
    return { error: data.error || "refresh_failed" };
  }
  const accessToken = typeof data.access_token === "string" ? data.access_token : "";
  if (!accessToken) return { error: "no_access_token" };
  return {
    accessToken,
    expiresIn: typeof data.expires_in === "number" ? data.expires_in : 3600,
    scopes: typeof data.scope === "string" ? data.scope : "",
  };
}

async function revokeToken(token: string): Promise<void> {
  try {
    await fetch(`${GOOGLE_REVOKE_URL}?token=${encodeURIComponent(token)}`, { method: "POST" });
  } catch {
    // Best effort — disconnect must still delete the stored credential.
  }
}

// ---------------------------------------------------------------------------
// Rate limiting (per isolate, best effort)
// ---------------------------------------------------------------------------

const rateBuckets = new Map<string, { count: number; resetAt: number }>();

function rateLimited(key: string, limit: number, windowSeconds: number): boolean {
  const now = Date.now();
  const bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt <= now) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowSeconds * 1000 });
    return false;
  }
  bucket.count += 1;
  return bucket.count > limit;
}

function clientKey(request: Request, route: string): string {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  return `${route}:${ip}`;
}

// ---------------------------------------------------------------------------
// Storage (Workers KV)
// ---------------------------------------------------------------------------

interface AccountRecord {
  id: string;
  userId: string;
  googleSub: string;
  email?: string;
  name?: string;
  picture?: string;
  scopes: string;
  refreshIv: string;
  refreshCt: string;
  accessIv?: string;
  accessCt?: string;
  accessExpiresAt: number;
  createdAt: number;
  updatedAt: number;
}

function accountIdFor(userId: string, googleSub: string): string {
  return `${userId}:${googleSub}`;
}

function accountKey(accountId: string): string {
  return `acct:${accountId}`;
}

async function ensureUser(env: Env, userId: string): Promise<void> {
  const key = `user:${userId}`;
  const existing = await env.TOKENS.get(key);
  if (existing) return;
  await env.TOKENS.put(key, JSON.stringify({ createdAt: nowSeconds() }));
}

async function getAccount(
  env: Env,
  userId: string,
  accountId: string,
): Promise<AccountRecord | null> {
  // Account ids are "<ownerUserId>:<googleSub>"; refuse anything that is not
  // scoped to the caller's own user before touching storage.
  if (!accountId.startsWith(`${userId}:`)) return null;
  const raw = await env.TOKENS.get(accountKey(accountId));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as AccountRecord;
    if (parsed.userId !== userId || parsed.id !== accountId) return null;
    return parsed;
  } catch {
    return null;
  }
}

async function putAccount(env: Env, account: AccountRecord): Promise<void> {
  await env.TOKENS.put(accountKey(account.id), JSON.stringify(account), {
    // Keep the credential for as long as the account is connected; it is
    // deleted explicitly on disconnect.
    expirationTtl: 60 * 60 * 24 * 365 * 2,
  });
}

async function deleteAccount(env: Env, userId: string, accountId: string): Promise<void> {
  if (!accountId.startsWith(`${userId}:`)) return;
  await env.TOKENS.delete(accountKey(accountId));
}

async function listAccounts(env: Env, userId: string): Promise<AccountRecord[]> {
  const prefix = `acct:${userId}:`;
  const listed = await env.TOKENS.list({ prefix, limit: 50 });
  const records = await Promise.all(
    listed.keys.map(async (entry) => {
      const raw = await env.TOKENS.get(entry.name);
      if (!raw) return null;
      try {
        return JSON.parse(raw) as AccountRecord;
      } catch {
        return null;
      }
    }),
  );
  return records
    .filter((record): record is AccountRecord => !!record && record.userId === userId)
    .sort((a, b) => a.createdAt - b.createdAt);
}

function publicAccount(record: AccountRecord) {
  return {
    id: record.id,
    email: record.email ?? null,
    name: record.name ?? null,
    picture: record.picture ?? null,
    scopes: record.scopes ? record.scopes.split(" ").filter(Boolean) : [],
    connectedAt: record.createdAt * 1000,
  };
}

/**
 * Returns a usable Google access token for [account], refreshing (and
 * re-sealing) it when the cached one is about to expire. Returns null when the
 * grant was revoked and the user must reconnect.
 */
async function accessTokenFor(
  env: Env,
  account: AccountRecord,
): Promise<{ accessToken: string; account: AccountRecord } | { reauthRequired: true }> {
  if (
    account.accessIv &&
    account.accessCt &&
    account.accessExpiresAt > nowSeconds() + ACCESS_TOKEN_SAFETY_SECONDS
  ) {
    try {
      const accessToken = await unseal(
        env,
        { iv: account.accessIv, ct: account.accessCt },
        account.id,
      );
      return { accessToken, account };
    } catch {
      // Fall through to a refresh when the cached ciphertext is unreadable.
    }
  }

  let refreshToken: string;
  try {
    refreshToken = await unseal(
      env,
      { iv: account.refreshIv, ct: account.refreshCt },
      account.id,
    );
  } catch {
    return { reauthRequired: true };
  }

  const refreshed = await refreshAccessToken(env, refreshToken);
  if ("error" in refreshed) {
    if (refreshed.error === "invalid_grant" || refreshed.error === "unauthorized_client") {
      return { reauthRequired: true };
    }
    throw new Error("google_unreachable");
  }

  const sealed = await seal(env, refreshed.accessToken, account.id);
  const updated: AccountRecord = {
    ...account,
    accessIv: sealed.iv,
    accessCt: sealed.ct,
    accessExpiresAt: nowSeconds() + Math.max(0, refreshed.expiresIn),
    scopes: refreshed.scopes || account.scopes,
    updatedAt: nowSeconds(),
  };
  await putAccount(env, updated);
  return { accessToken: refreshed.accessToken, account: updated };
}

// ---------------------------------------------------------------------------
// Google REST helpers (all Google traffic stays here)
// ---------------------------------------------------------------------------

class GoogleApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly reauthRequired: boolean,
  ) {
    super(message);
  }
}

async function googleFetch(
  token: string,
  url: string,
  init: { method?: string; body?: BodyInit; contentType?: string; accept?: string } = {},
): Promise<Response> {
  const headers: Record<string, string> = {
    authorization: `Bearer ${token}`,
    accept: init.accept ?? "application/json",
  };
  if (init.contentType) headers["content-type"] = init.contentType;
  let res: Response;
  try {
    res = await fetch(url, { method: init.method ?? "GET", headers, body: init.body });
  } catch {
    throw new GoogleApiError(502, "Could not reach Google. Try again.", false);
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new GoogleApiError(res.status, googleErrorMessage(text, res.status), res.status === 401);
  }
  return res;
}

function googleErrorMessage(body: string, status: number): string {
  try {
    const parsed = JSON.parse(body) as {
      error?: { message?: string } | string;
      message?: string;
    };
    if (typeof parsed.error === "object" && parsed.error?.message) return parsed.error.message;
    if (typeof parsed.error === "string") return parsed.error;
    if (parsed.message) return parsed.message;
  } catch {
    // Not JSON (Drive media downloads, for example).
  }
  if (status === 401) return "Google authorization expired. Reconnect the account.";
  if (status === 403) return "Google denied this request. The account may lack the required permission.";
  if (status === 404) return "Google could not find that item.";
  return `Google API error (HTTP ${status}).`;
}

async function googleJson<T = Record<string, unknown>>(
  token: string,
  url: string,
  init: { method?: string; body?: string; contentType?: string; accept?: string } = {},
): Promise<T> {
  const res = await googleFetch(token, url, init);
  const text = await res.text();
  if (!text) return {} as T;
  return JSON.parse(text) as T;
}

async function googleText(token: string, url: string, accept: string): Promise<string> {
  const res = await googleFetch(token, url, { accept });
  return res.text();
}

interface DriveFile {
  id?: string;
  name?: string;
  mimeType?: string;
  modifiedTime?: string;
  size?: string;
  webViewLink?: string;
  parents?: string[];
}

interface GmailHeader {
  name?: string;
  value?: string;
}

interface GmailPayload {
  mimeType?: string;
  headers?: GmailHeader[];
  body?: { data?: string };
  parts?: GmailPayload[];
}

// --- Drive -----------------------------------------------------------------

async function driveList(
  token: string,
  query: string | null,
  rawQuery: boolean,
  pageSize: number,
): Promise<{ text: string; files: DriveFile[] }> {
  const q = !query
    ? "trashed = false"
    : rawQuery
      ? query
      : `name contains '${query.replace(/'/g, "\\'")}' and trashed = false`;
  const url =
    "https://www.googleapis.com/drive/v3/files" +
    `?q=${encodeURIComponent(q)}&pageSize=${pageSize}&orderBy=modifiedTime desc` +
    "&fields=files(id,name,mimeType,modifiedTime,size,webViewLink,parents)";
  const data = await googleJson<{ files?: DriveFile[] }>(token, url);
  const files = data.files ?? [];
  if (files.length === 0) return { text: "No Drive files matched.", files };
  return {
    text: files
      .map((file) => {
        const kind = file.mimeType?.split(".").pop() ?? "?";
        return `• ${file.name ?? "(unnamed)"} [${kind}] id=${file.id ?? "?"} modified=${(file.modifiedTime ?? "-").slice(0, 10)}`;
      })
      .join("\n"),
    files,
  };
}

async function driveMetadata(token: string, fileId: string): Promise<string> {
  const url =
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}` +
    "?fields=id,name,mimeType,modifiedTime,createdTime,size,webViewLink,parents,owners(displayName,emailAddress)";
  const file = await googleJson<DriveFile & { createdTime?: string; owners?: { displayName?: string }[] }>(
    token,
    url,
  );
  return [
    `Name: ${file.name ?? "?"}`,
    `Id: ${file.id ?? fileId}`,
    `Type: ${file.mimeType ?? "?"}`,
    `Modified: ${file.modifiedTime ?? "-"}`,
    `Size: ${file.size ?? "-"}`,
    `Link: ${file.webViewLink ?? "-"}`,
  ].join("\n");
}

async function driveRead(
  token: string,
  fileId: string,
  maxChars: number,
): Promise<string> {
  const meta = await googleJson<DriveFile>(
    token,
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?fields=id,name,mimeType`,
  );
  const mime = meta.mimeType ?? "";
  const name = meta.name ?? fileId;
  let content: string;
  if (mime === "application/vnd.google-apps.document") {
    content = await googleText(
      token,
      `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}/export?mimeType=text/plain`,
      "text/plain",
    );
  } else if (mime === "application/vnd.google-apps.spreadsheet") {
    content = await googleText(
      token,
      `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}/export?mimeType=text/csv`,
      "text/csv",
    );
  } else if (
    mime.startsWith("text/") ||
    mime.includes("json") ||
    mime.includes("xml") ||
    mime.includes("javascript") ||
    mime.includes("csv")
  ) {
    content = await googleText(
      token,
      `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`,
      "text/plain",
    );
  } else {
    return `File "${name}" is a binary type (${mime || "unknown"}); only metadata is available. Use google.drive metadata or open it in Google Drive.`;
  }
  const bounded =
    content.length > maxChars ? `${content.slice(0, maxChars)}\n… [truncated]` : content;
  return `FILE: ${name}\n\n${bounded}`;
}

async function driveUpload(
  token: string,
  name: string,
  content: string,
  mimeType: string,
  parentId: string | null,
): Promise<{ text: string; id?: string }> {
  const metadata: Record<string, unknown> = { name };
  if (parentId) metadata.parents = [parentId];
  const form = new FormData();
  form.append("metadata", new Blob([JSON.stringify(metadata)], { type: "application/json" }));
  form.append("file", new Blob([content], { type: mimeType || "text/plain" }), name);
  const res = await googleFetch(
    token,
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink",
    { method: "POST", body: form },
  );
  const created = (await res.json()) as DriveFile;
  return {
    text: `Uploaded "${created.name ?? name}" (id=${created.id ?? "?"}).`,
    id: created.id,
  };
}

async function driveCreateFolder(
  token: string,
  name: string,
  parentId: string | null,
): Promise<{ text: string; id?: string }> {
  const payload: Record<string, unknown> = {
    name,
    mimeType: "application/vnd.google-apps.folder",
  };
  if (parentId) payload.parents = [parentId];
  const created = await googleJson<DriveFile>(
    token,
    "https://www.googleapis.com/drive/v3/files?fields=id,name",
    { method: "POST", body: JSON.stringify(payload), contentType: "application/json" },
  );
  return { text: `Created folder "${created.name ?? name}" (id=${created.id ?? "?"}).`, id: created.id };
}

async function driveRename(token: string, fileId: string, newName: string): Promise<string> {
  const updated = await googleJson<DriveFile>(
    token,
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?fields=id,name`,
    { method: "PATCH", body: JSON.stringify({ name: newName }), contentType: "application/json" },
  );
  return `Renamed to "${updated.name ?? newName}".`;
}

async function driveMove(
  token: string,
  fileId: string,
  addParent: string,
  removeParent: string | null,
): Promise<string> {
  const params = new URLSearchParams({ addParents: addParent, fields: "id,name,parents" });
  if (removeParent) params.set("removeParents", removeParent);
  await googleJson(
    token,
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?${params.toString()}`,
    { method: "PATCH", body: "{}", contentType: "application/json" },
  );
  return `Moved file ${fileId} to folder ${addParent}.`;
}

async function driveDelete(token: string, fileId: string): Promise<string> {
  await googleFetch(token, `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}`, {
    method: "DELETE",
  });
  return `Deleted Drive file ${fileId}.`;
}

// --- Gmail -----------------------------------------------------------------

function gmailHeader(payload: GmailPayload | undefined, name: string): string | undefined {
  return payload?.headers?.find((header) => header.name?.toLowerCase() === name.toLowerCase())
    ?.value;
}

function gmailBody(payload: GmailPayload | undefined): string {
  if (!payload) return "";
  if (payload.body?.data) {
    const direct = b64UrlDecodeToString(payload.body.data);
    if (direct.trim()) return direct;
  }
  const builder: string[] = [];
  const walk = (part: GmailPayload) => {
    const mime = part.mimeType ?? "";
    if (part.body?.data && (mime.startsWith("text/plain") || mime.startsWith("text/html"))) {
      builder.push(b64UrlDecodeToString(part.body.data));
    }
    part.parts?.forEach(walk);
  };
  payload.parts?.forEach(walk);
  return builder.join("\n").trim();
}

function buildRawMessage(to: string, subject: string, body: string): string {
  const safeSubject = subject.replace(/[\r\n]/g, " ");
  const mime = [
    `To: ${to}`,
    `Subject: ${safeSubject}`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: 8bit",
    "",
    body,
  ].join("\r\n");
  return b64FromUtf8(mime).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function gmailSearch(
  token: string,
  query: string,
  maxResults: number,
): Promise<{ text: string; messages: { id: string; subject?: string; from?: string; date?: string }[] }> {
  const list = await googleJson<{ messages?: { id?: string; threadId?: string }[] }>(
    token,
    `https://gmail.googleapis.com/gmail/v1/users/me/messages?q=${encodeURIComponent(query)}&maxResults=${maxResults}`,
  );
  const ids = (list.messages ?? []).map((message) => message.id).filter((id): id is string => !!id);
  if (ids.length === 0) return { text: "No Gmail messages matched.", messages: [] };

  const detailed = await Promise.all(
    ids.map(async (id) => {
      try {
        const detail = await googleJson<{ payload?: GmailPayload }>(
          token,
          `https://gmail.googleapis.com/gmail/v1/users/me/messages/${encodeURIComponent(id)}` +
            "?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date",
        );
        return {
          id,
          subject: gmailHeader(detail.payload, "Subject"),
          from: gmailHeader(detail.payload, "From"),
          date: gmailHeader(detail.payload, "Date"),
        };
      } catch {
        return { id };
      }
    }),
  );

  return {
    text: detailed
      .map(
        (message) =>
          `• ${message.subject ?? "(no subject)"} — from ${message.from ?? "?"} (${message.date ?? "?"}) id=${message.id}`,
      )
      .join("\n"),
    messages: detailed,
  };
}

async function gmailRead(token: string, messageId: string): Promise<string> {
  const message = await googleJson<{ snippet?: string; payload?: GmailPayload }>(
    token,
    `https://gmail.googleapis.com/gmail/v1/users/me/messages/${encodeURIComponent(messageId)}?format=full`,
  );
  const body = gmailBody(message.payload).slice(0, 20_000) || message.snippet || "(no readable body)";
  return [
    `Subject: ${gmailHeader(message.payload, "Subject") ?? "(no subject)"}`,
    `From: ${gmailHeader(message.payload, "From") ?? "?"}`,
    `To: ${gmailHeader(message.payload, "To") ?? "?"}`,
    `Date: ${gmailHeader(message.payload, "Date") ?? "?"}`,
    "",
    body,
  ].join("\n");
}

async function gmailThread(token: string, threadId: string): Promise<string> {
  const thread = await googleJson<{ messages?: { payload?: GmailPayload; snippet?: string }[] }>(
    token,
    `https://gmail.googleapis.com/gmail/v1/users/me/threads/${encodeURIComponent(threadId)}?format=full`,
  );
  const messages = thread.messages ?? [];
  if (messages.length === 0) return `Thread ${threadId} has no messages.`;
  return messages
    .map((message, index) => {
      const body = gmailBody(message.payload).slice(0, 4_000) || message.snippet || "";
      return (
        `── Message ${index + 1} — ${gmailHeader(message.payload, "From") ?? "?"} (${gmailHeader(message.payload, "Date") ?? "?"})\n` +
        `Subject: ${gmailHeader(message.payload, "Subject") ?? "(no subject)"}\n${body}`
      );
    })
    .join("\n\n");
}

async function gmailDraft(
  token: string,
  to: string,
  subject: string,
  body: string,
): Promise<{ text: string; id?: string }> {
  const created = await googleJson<{ id?: string }>(
    token,
    "https://gmail.googleapis.com/gmail/v1/users/me/drafts",
    {
      method: "POST",
      contentType: "application/json",
      body: JSON.stringify({ message: { raw: buildRawMessage(to, subject, body) } }),
    },
  );
  return { text: `Draft created (id=${created.id ?? "?"}). Nothing was sent.`, id: created.id };
}

async function gmailSend(
  token: string,
  to: string,
  subject: string,
  body: string,
): Promise<{ text: string; id?: string }> {
  const sent = await googleJson<{ id?: string }>(
    token,
    "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
    {
      method: "POST",
      contentType: "application/json",
      body: JSON.stringify({ raw: buildRawMessage(to, subject, body) }),
    },
  );
  return { text: `Email sent to ${to} (id=${sent.id ?? "?"}).`, id: sent.id };
}

// --- Sheets ----------------------------------------------------------------

async function sheetsRead(token: string, spreadsheetId: string, range: string): Promise<string> {
  const data = await googleJson<{ values?: unknown[][] }>(
    token,
    `https://sheets.googleapis.com/v4/spreadsheets/${encodeURIComponent(spreadsheetId)}/values/${encodeURIComponent(range)}`,
  );
  const values = data.values ?? [];
  if (values.length === 0) return `No values in ${range}.`;
  return values.map((row) => row.map((cell) => String(cell ?? "")).join(" | ")).join("\n");
}

async function sheetsCreate(token: string, title: string): Promise<{ text: string; id?: string }> {
  const created = await googleJson<{ spreadsheetId?: string }>(
    token,
    "https://sheets.googleapis.com/v4/spreadsheets",
    {
      method: "POST",
      contentType: "application/json",
      body: JSON.stringify({ properties: { title } }),
    },
  );
  return { text: `Created spreadsheet "${title}" (id=${created.spreadsheetId ?? "?"}).`, id: created.spreadsheetId };
}

async function sheetsMetadata(token: string, spreadsheetId: string): Promise<string> {
  const data = await googleJson<{
    properties?: { title?: string };
    sheets?: {
      properties?: {
        title?: string;
        sheetId?: number;
        gridProperties?: { rowCount?: number; columnCount?: number };
      };
    }[];
  }>(
    token,
    `https://sheets.googleapis.com/v4/spreadsheets/${encodeURIComponent(spreadsheetId)}` +
      "?fields=properties.title,sheets.properties(sheetId,title,index,gridProperties(rowCount,columnCount))",
  );
  const title = data.properties?.title ?? spreadsheetId;
  const sheets = (data.sheets ?? []).map((sheet) => {
    const props = sheet.properties ?? {};
    const grid = props.gridProperties ?? {};
    return `• ${props.title ?? "?"} (sheetId=${props.sheetId ?? "?"}, ${grid.rowCount ?? "?"}x${grid.columnCount ?? "?"})`;
  });
  return `Spreadsheet: ${title}\n${sheets.join("\n")}`;
}

function parseValues(raw: unknown): unknown[][] {
  if (!Array.isArray(raw) || raw.some((row) => !Array.isArray(row))) {
    throw new GoogleApiError(
      400,
      'values must be a JSON array of rows, for example [["a","b"]].',
      false,
    );
  }
  return raw as unknown[][];
}

async function sheetsUpdate(
  token: string,
  spreadsheetId: string,
  range: string,
  values: unknown,
): Promise<string> {
  const data = await googleJson<{ updatedCells?: number }>(
    token,
    `https://sheets.googleapis.com/v4/spreadsheets/${encodeURIComponent(spreadsheetId)}` +
      `/values/${encodeURIComponent(range)}?valueInputOption=USER_ENTERED`,
    {
      method: "PUT",
      contentType: "application/json",
      body: JSON.stringify({ values: parseValues(values) }),
    },
  );
  return `Updated ${data.updatedCells ?? "?"} cells in ${range}.`;
}

async function sheetsAppend(
  token: string,
  spreadsheetId: string,
  range: string,
  values: unknown,
): Promise<string> {
  const data = await googleJson<{ updates?: { updatedCells?: number } }>(
    token,
    `https://sheets.googleapis.com/v4/spreadsheets/${encodeURIComponent(spreadsheetId)}` +
      `/values/${encodeURIComponent(range)}:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS`,
    {
      method: "POST",
      contentType: "application/json",
      body: JSON.stringify({ values: parseValues(values) }),
    },
  );
  return `Appended rows to ${range} (${data.updates?.updatedCells ?? 0} cells).`;
}

// --- Docs ------------------------------------------------------------------

interface DocsDocument {
  documentId?: string;
  title?: string;
  body?: { content?: DocsStructuralElement[] };
}

interface DocsStructuralElement {
  endIndex?: number;
  paragraph?: { elements?: { textRun?: { content?: string } }[] };
}

function docsText(doc: DocsDocument): string {
  const builder: string[] = [];
  doc.body?.content?.forEach((element) => {
    element.paragraph?.elements?.forEach((run) => {
      builder.push(run.textRun?.content ?? "");
    });
  });
  return builder.join("");
}

function docsEndIndex(doc: DocsDocument): number {
  const ends = (doc.body?.content ?? [])
    .map((element) => element.endIndex)
    .filter((value): value is number => typeof value === "number");
  const end = ends.length ? Math.max(...ends) : 1;
  return Math.max(1, end - 1);
}

async function docsCreate(token: string, title: string): Promise<{ text: string; id?: string }> {
  const created = await googleJson<DocsDocument>(token, "https://docs.googleapis.com/v1/documents", {
    method: "POST",
    contentType: "application/json",
    body: JSON.stringify({ title }),
  });
  return { text: `Created document "${title}" (id=${created.documentId ?? "?"}).`, id: created.documentId };
}

async function docsRead(token: string, documentId: string): Promise<string> {
  const doc = await googleJson<DocsDocument>(
    token,
    `https://docs.googleapis.com/v1/documents/${encodeURIComponent(documentId)}`,
  );
  return `DOCUMENT: ${doc.title ?? documentId}\n\n${docsText(doc).slice(0, 20_000)}`;
}

async function docsAppend(token: string, documentId: string, text: string): Promise<string> {
  const url = `https://docs.googleapis.com/v1/documents/${encodeURIComponent(documentId)}`;
  const doc = await googleJson<DocsDocument>(token, url);
  await googleJson(token, `${url}:batchUpdate`, {
    method: "POST",
    contentType: "application/json",
    body: JSON.stringify({
      requests: [{ insertText: { location: { index: docsEndIndex(doc) }, text: `\n${text}` } }],
    }),
  });
  return `Appended content to document ${documentId}.`;
}

async function docsReplace(
  token: string,
  documentId: string,
  find: string,
  replace: string,
): Promise<string> {
  await googleJson(
    token,
    `https://docs.googleapis.com/v1/documents/${encodeURIComponent(documentId)}:batchUpdate`,
    {
      method: "POST",
      contentType: "application/json",
      body: JSON.stringify({
        requests: [
          {
            replaceAllText: {
              containsText: { text: find, matchCase: false },
              replaceText: replace,
            },
          },
        ],
      }),
    },
  );
  return `Replaced occurrences of "${find}" in document ${documentId}.`;
}

// ---------------------------------------------------------------------------
// Route handlers — OAuth
// ---------------------------------------------------------------------------

async function handleExchange(request: Request, env: Env): Promise<Response> {
  if (rateLimited(clientKey(request, "exchange"), 30, 60)) {
    return apiError("rate_limited", "Too many requests. Try again shortly.", 429);
  }
  const body = await readJsonBody(request);
  if (!body) return apiError("bad_request", "Expected a JSON body.", 400);
  const serverAuthCode = str(body.serverAuthCode, MAX_CODE_LENGTH);
  if (!serverAuthCode) {
    return apiError("bad_request", "serverAuthCode is required.", 400);
  }

  // Optional existing session links this account under the same user.
  let ownerUserId: string | null = null;
  const bearer = getBearer(request);
  if (bearer) {
    const session = await verifySession(env, bearer);
    if (!session) return apiError("unauthorized", "Session expired. Reconnect the account.", 401);
    ownerUserId = session.uid;
  }

  const result = await exchangeAuthorizationCode(env, serverAuthCode);
  if ("error" in result) {
    return apiError(
      "exchange_failed",
      "Google rejected the authorization code. It may be expired or already used.",
      400,
    );
  }
  const tokens = result.tokens;
  const identity = tokens.identity;
  const userId = ownerUserId ?? identity.sub;
  await ensureUser(env, userId);

  const accountId = accountIdFor(userId, identity.sub);
  const existing = await getAccount(env, userId, accountId);

  // A refresh token only comes back on a consent grant. If Google did not
  // return one and we have none stored, offline access cannot be granted.
  if (!tokens.refreshToken && !existing) {
    return apiError(
      "no_refresh_token",
      "Google did not return a refresh token. Reconnect and approve offline access.",
      400,
    );
  }

  const refreshSealed = tokens.refreshToken
    ? await seal(env, tokens.refreshToken, accountId)
    : { iv: existing!.refreshIv, ct: existing!.refreshCt };
  const accessSealed = await seal(env, tokens.accessToken, accountId);
  const now = nowSeconds();

  const record: AccountRecord = {
    id: accountId,
    userId,
    googleSub: identity.sub,
    email: identity.email ?? existing?.email,
    name: identity.name ?? existing?.name,
    picture: identity.picture ?? existing?.picture,
    scopes: tokens.scopes || existing?.scopes || "",
    refreshIv: refreshSealed.iv,
    refreshCt: refreshSealed.ct,
    accessIv: accessSealed.iv,
    accessCt: accessSealed.ct,
    accessExpiresAt: now + Math.max(0, tokens.expiresIn),
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };
  await putAccount(env, record);

  const sessionToken = await issueSession(env, {
    uid: userId,
    aid: accountId,
    email: identity.email,
    exp: nowSeconds() + SESSION_TTL_SECONDS,
  });

  return json({
    sessionToken,
    expiresAt: (nowSeconds() + SESSION_TTL_SECONDS) * 1000,
    account: publicAccount(record),
  });
}

async function handleRefresh(request: Request, env: Env): Promise<Response> {
  if (rateLimited(clientKey(request, "refresh"), 120, 60)) {
    return apiError("rate_limited", "Too many requests. Try again shortly.", 429);
  }
  const bearer = getBearer(request);
  if (!bearer) return apiError("unauthorized", "Missing session token.", 401);
  const session = await verifySession(env, bearer);
  if (!session) return apiError("unauthorized", "Session expired. Reconnect the account.", 401);

  const body = await readJsonBody(request);
  if (!body) return apiError("bad_request", "Expected a JSON body.", 400);
  const accountId = str(body.accountId, MAX_ACCOUNT_ID_LENGTH);
  if (!accountId) return apiError("bad_request", "accountId is required.", 400);

  const account = await getAccount(env, session.uid, accountId);
  if (!account) return apiError("not_found", "Account not found.", 404);

  const fresh = await accessTokenFor(env, account);
  if ("reauthRequired" in fresh) {
    return apiError(
      "reauth_required",
      "Google access was revoked or expired. Reconnect the account.",
      401,
    );
  }
  return json({
    accessToken: fresh.accessToken,
    expiresAt: fresh.account.accessExpiresAt * 1000,
    scopes: fresh.account.scopes ? fresh.account.scopes.split(" ").filter(Boolean) : [],
  });
}

async function handleDisconnect(request: Request, env: Env): Promise<Response> {
  if (rateLimited(clientKey(request, "disconnect"), 30, 60)) {
    return apiError("rate_limited", "Too many requests. Try again shortly.", 429);
  }
  const bearer = getBearer(request);
  if (!bearer) return apiError("unauthorized", "Missing session token.", 401);
  const session = await verifySession(env, bearer);
  if (!session) return apiError("unauthorized", "Session expired. Reconnect the account.", 401);

  const body = await readJsonBody(request);
  if (!body) return apiError("bad_request", "Expected a JSON body.", 400);
  const accountId = str(body.accountId, MAX_ACCOUNT_ID_LENGTH);
  if (!accountId) return apiError("bad_request", "accountId is required.", 400);

  const account = await getAccount(env, session.uid, accountId);
  if (!account) return apiError("not_found", "Account not found.", 404);

  try {
    const refreshToken = await unseal(
      env,
      { iv: account.refreshIv, ct: account.refreshCt },
      account.id,
    );
    await revokeToken(refreshToken);
  } catch {
    // Even if decryption/revocation fails, the credential must go.
  }

  await deleteAccount(env, session.uid, accountId);
  return json({ ok: true });
}

async function handleAccounts(request: Request, env: Env): Promise<Response> {
  const bearer = getBearer(request);
  if (!bearer) return apiError("unauthorized", "Missing session token.", 401);
  const session = await verifySession(env, bearer);
  if (!session) return apiError("unauthorized", "Session expired. Reconnect the account.", 401);

  const accounts = await listAccounts(env, session.uid);
  return json({ accounts: accounts.map(publicAccount) });
}

// ---------------------------------------------------------------------------
// Route handlers — Google proxy (the app's only path to Google)
// ---------------------------------------------------------------------------

interface ProxyContext {
  body: Record<string, unknown>;
  account: AccountRecord;
}

async function proxyContext(request: Request, env: Env): Promise<ProxyContext | Response> {
  const bearer = getBearer(request);
  if (!bearer) return apiError("unauthorized", "Missing session token.", 401);
  const session = await verifySession(env, bearer);
  if (!session) return apiError("unauthorized", "Session expired. Reconnect the account.", 401);

  const body = await readJsonBody(request);
  if (!body) return apiError("bad_request", "Expected a JSON body.", 400);

  const accountId = str(body.accountId, MAX_ACCOUNT_ID_LENGTH) ?? session.aid;
  const account = await getAccount(env, session.uid, accountId);
  if (!account) return apiError("not_found", "Account not found.", 404);
  return { body, account };
}

const MAX_TEXT_FIELD = 200_000;

async function handleGoogleProxy(
  action: string,
  request: Request,
  env: Env,
): Promise<Response> {
  const context = await proxyContext(request, env);
  if (context instanceof Response) return context;
  const { body, account } = context;

  if (rateLimited(clientKey(request, "google"), 240, 60)) {
    return apiError("rate_limited", "Too many requests. Try again shortly.", 429);
  }

  // Destructive actions must be confirmed by the caller on every request.
  const destructive = action === "drive.delete" || action === "gmail.send";
  if (destructive && !bool(body.confirm)) {
    return apiError(
      "confirmation_required",
      "This action changes or sends something irreversible. Resend with confirm=true after the user approves.",
      400,
    );
  }

  const fresh = await accessTokenFor(env, account);
  if ("reauthRequired" in fresh) {
    return apiError(
      "reauth_required",
      "Google access was revoked or expired. Reconnect the account.",
      401,
    );
  }
  const token = fresh.accessToken;

  const require = (field: string, max = 2_000): string => {
    const value = str(body[field], max);
    if (!value) throw new GoogleApiError(400, `${field} is required.`, false);
    return value;
  };

  try {
    switch (action) {
      // ---- Drive ---------------------------------------------------------
      case "drive.list": {
        const result = await driveList(
          token,
          str(body.query, 2_000),
          bool(body.rawQuery),
          int(body.pageSize, 20, 1, 100),
        );
        return json({ text: result.text, files: result.files });
      }
      case "drive.metadata": {
        return json({ text: await driveMetadata(token, require("fileId")) });
      }
      case "drive.read": {
        return json({
          text: await driveRead(token, require("fileId"), int(body.maxChars, 20_000, 500, 100_000)),
        });
      }
      case "drive.upload": {
        const result = await driveUpload(
          token,
          require("name", 500),
          require("content", MAX_TEXT_FIELD),
          str(body.mimeType, 200) ?? "text/plain",
          str(body.parentId, MAX_ACCOUNT_ID_LENGTH),
        );
        return json(result);
      }
      case "drive.folder": {
        const result = await driveCreateFolder(
          token,
          require("name", 500),
          str(body.parentId, MAX_ACCOUNT_ID_LENGTH),
        );
        return json(result);
      }
      case "drive.rename": {
        return json({ text: await driveRename(token, require("fileId"), require("name", 500)) });
      }
      case "drive.move": {
        return json({
          text: await driveMove(
            token,
            require("fileId"),
            require("addParent"),
            str(body.removeParent, MAX_ACCOUNT_ID_LENGTH),
          ),
        });
      }
      case "drive.delete": {
        return json({ text: await driveDelete(token, require("fileId")) });
      }

      // ---- Gmail ---------------------------------------------------------
      case "gmail.search": {
        const result = await gmailSearch(
          token,
          require("query", 1_000),
          int(body.maxResults, 10, 1, 20),
        );
        return json({ text: result.text, messages: result.messages });
      }
      case "gmail.read": {
        return json({ text: await gmailRead(token, require("messageId")) });
      }
      case "gmail.thread": {
        return json({ text: await gmailThread(token, require("threadId")) });
      }
      case "gmail.draft": {
        return json(
          await gmailDraft(
            token,
            require("to", 1_000),
            str(body.subject, 500) ?? "(no subject)",
            str(body.body, MAX_TEXT_FIELD) ?? "",
          ),
        );
      }
      case "gmail.send": {
        return json(
          await gmailSend(
            token,
            require("to", 1_000),
            str(body.subject, 500) ?? "(no subject)",
            str(body.body, MAX_TEXT_FIELD) ?? "",
          ),
        );
      }

      // ---- Sheets --------------------------------------------------------
      case "sheets.read": {
        return json({
          text: await sheetsRead(token, require("spreadsheetId"), require("range", 500)),
        });
      }
      case "sheets.metadata": {
        return json({ text: await sheetsMetadata(token, require("spreadsheetId")) });
      }
      case "sheets.create": {
        return json(await sheetsCreate(token, require("title", 500)));
      }
      case "sheets.update": {
        return json({
          text: await sheetsUpdate(
            token,
            require("spreadsheetId"),
            require("range", 500),
            body.values,
          ),
        });
      }
      case "sheets.append": {
        return json({
          text: await sheetsAppend(
            token,
            require("spreadsheetId"),
            require("range", 500),
            body.values,
          ),
        });
      }

      // ---- Docs ----------------------------------------------------------
      case "docs.create": {
        return json(await docsCreate(token, require("title", 500)));
      }
      case "docs.read": {
        return json({ text: await docsRead(token, require("documentId")) });
      }
      case "docs.append": {
        return json({
          text: await docsAppend(token, require("documentId"), require("text", MAX_TEXT_FIELD)),
        });
      }
      case "docs.replace": {
        return json({
          text: await docsReplace(
            token,
            require("documentId"),
            require("find", 5_000),
            str(body.replace, MAX_TEXT_FIELD) ?? "",
          ),
        });
      }

      default:
        return apiError("not_found", "Unknown Google action.", 404);
    }
  } catch (error) {
    if (error instanceof GoogleApiError) {
      if (error.reauthRequired) {
        return apiError(
          "reauth_required",
          "Google access was revoked or expired. Reconnect the account.",
          401,
        );
      }
      const status = error.status >= 400 && error.status < 600 ? error.status : 502;
      return apiError(status === 400 ? "bad_request" : "google_error", error.message, status);
    }
    throw error;
  }
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

const GOOGLE_ACTIONS = new Set([
  "drive.list",
  "drive.metadata",
  "drive.read",
  "drive.upload",
  "drive.folder",
  "drive.rename",
  "drive.move",
  "drive.delete",
  "gmail.search",
  "gmail.read",
  "gmail.thread",
  "gmail.draft",
  "gmail.send",
  "sheets.read",
  "sheets.metadata",
  "sheets.create",
  "sheets.update",
  "sheets.append",
  "docs.create",
  "docs.read",
  "docs.append",
  "docs.replace",
]);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          ...corsHeaders(),
          "allow": "GET, POST, OPTIONS",
        },
      });
    }

    try {
      if (url.pathname === "/health" && request.method === "GET") {
        return json({ status: "ok", service: "neuron-ai-backend", time: new Date().toISOString() });
      }
      if (url.pathname === "/auth/google/exchange" && request.method === "POST") {
        return await handleExchange(request, env);
      }
      if (url.pathname === "/auth/google/refresh" && request.method === "POST") {
        return await handleRefresh(request, env);
      }
      if (url.pathname === "/auth/google/disconnect" && request.method === "POST") {
        return await handleDisconnect(request, env);
      }
      if (url.pathname === "/auth/google/accounts" && request.method === "GET") {
        return await handleAccounts(request, env);
      }

      // One endpoint per Google action: POST /google/<action> with
      // { accountId?, ...args }. The app builds these thin calls; all Google
      // REST traffic happens here.
      if (url.pathname.startsWith("/google/") && request.method === "POST") {
        const action = url.pathname.slice("/google/".length);
        if (!GOOGLE_ACTIONS.has(action)) return apiError("not_found", "Unknown Google action.", 404);
        return await handleGoogleProxy(action, request, env);
      }

      return apiError("not_found", "Unknown endpoint.", 404);
    } catch {
      return apiError("internal_error", "Unexpected server error.", 500);
    }
  },
};
