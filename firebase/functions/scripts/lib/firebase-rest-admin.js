const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const TOKEN_REFRESH_GRACE_MS = 60_000;

function normalizeActivationCode(value) {
  return String(value || "")
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9-]+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");
}

function resolveProjectId(explicitProject) {
  if (explicitProject && String(explicitProject).trim()) {
    return String(explicitProject).trim();
  }

  const firebaseDir = path.resolve(__dirname, "..", "..", "..");
  const firebasercPath = path.join(firebaseDir, ".firebaserc");
  if (!fs.existsSync(firebasercPath)) {
    throw new Error("Unable to resolve Firebase project. Pass --project explicitly.");
  }

  const config = JSON.parse(fs.readFileSync(firebasercPath, "utf8"));
  const projectId = config && config.projects && config.projects.default;
  if (!projectId) {
    throw new Error("No default Firebase project found in .firebaserc");
  }
  return projectId;
}

function resolveFirebaseToolsConfigPath() {
  const candidates = [
    process.env.FIREBASE_TOOLS_CONFIG,
    path.join(os.homedir(), ".config", "configstore", "firebase-tools.json"),
    process.env.APPDATA ? path.join(process.env.APPDATA, "configstore", "firebase-tools.json") : null,
  ].filter(Boolean);

  const match = candidates.find((candidate) => fs.existsSync(candidate));
  if (!match) {
    throw new Error(
      "Firebase CLI config not found. Run `firebase login` first or set FIREBASE_TOOLS_CONFIG."
    );
  }
  return match;
}

function refreshFirebaseCliToken() {
  const firebaseDir = path.resolve(__dirname, "..", "..", "..");
  const npxBinary = process.platform === "win32" ? "npx.cmd" : "npx";
  try {
    execFileSync(
      npxBinary,
      ["firebase-tools", "projects:list", "--json"],
      {
        stdio: "ignore",
        cwd: firebaseDir,
      }
    );
  } catch (_) {
    throw new Error(
      "Firebase CLI token refresh failed. Run `firebase login --reauth` and try again."
    );
  }
}

function readAccessTokenFromConfig() {
  const configPath = resolveFirebaseToolsConfigPath();
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const accessToken = config && config.tokens && config.tokens.access_token;
  const expiresAt = config && config.tokens && config.tokens.expires_at;

  if (accessToken && Number.isFinite(expiresAt) && expiresAt > Date.now() + TOKEN_REFRESH_GRACE_MS) {
    return accessToken;
  }

  refreshFirebaseCliToken();

  const refreshed = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const refreshedAccessToken = refreshed && refreshed.tokens && refreshed.tokens.access_token;
  const refreshedExpiresAt = refreshed && refreshed.tokens && refreshed.tokens.expires_at;
  if (
    refreshedAccessToken &&
    Number.isFinite(refreshedExpiresAt) &&
    refreshedExpiresAt > Date.now() + TOKEN_REFRESH_GRACE_MS
  ) {
    return refreshedAccessToken;
  }

  throw new Error("Unable to obtain a valid Firebase access token. Run `firebase login --reauth`.");
}

function buildDocumentUrl(projectId, pathSegments) {
  const encodedPath = pathSegments.map((segment) => encodeURIComponent(segment)).join("/");
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${encodedPath}`;
}

async function requestJson(url, init, allowNotFound = false) {
  const response = await fetch(url, init);
  const text = await response.text();

  if (allowNotFound && response.status === 404) {
    return null;
  }

  if (!response.ok) {
    throw new Error(`Firestore request failed: ${response.status} ${text}`);
  }

  return text ? JSON.parse(text) : {};
}

async function getDocument(projectId, pathSegments, accessToken) {
  return requestJson(
    buildDocumentUrl(projectId, pathSegments),
    {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    },
    true
  );
}

async function patchDocument(projectId, pathSegments, accessToken, fields) {
  return requestJson(buildDocumentUrl(projectId, pathSegments), {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields }),
  });
}

function getStringField(document, fieldName) {
  const field = document && document.fields && document.fields[fieldName];
  if (!field) {
    return "";
  }
  if (typeof field.stringValue === "string") {
    return field.stringValue.trim();
  }
  if (typeof field.integerValue === "string") {
    return field.integerValue.trim();
  }
  return "";
}

function stringField(value) {
  return { stringValue: String(value ?? "") };
}

function integerField(value) {
  return { integerValue: String(value) };
}

function nullField() {
  return { nullValue: null };
}

function timestampNowField() {
  return { timestampValue: new Date().toISOString() };
}

module.exports = {
  getDocument,
  getStringField,
  integerField,
  normalizeActivationCode,
  nullField,
  patchDocument,
  readAccessTokenFromConfig,
  resolveProjectId,
  stringField,
  timestampNowField,
};
