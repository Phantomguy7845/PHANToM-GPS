#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const DEFAULT_PREFIX = "PHANTOM-FULL";
const DEFAULT_COUNT = 1;
const DEFAULT_MAX_DEVICES = 1;
const TOKEN_REFRESH_GRACE_MS = 60_000;

function printUsage() {
  console.log(`Create activation code documents in Firestore.

Usage:
  npm run activation:create -- [options]

Options:
  --project <projectId>       Firebase project id. Defaults to ../.firebaserc
  --code <activationCode>     Exact activation code to create
  --count <number>            Number of codes to generate when --code is omitted
  --prefix <prefix>           Prefix for generated codes (default: ${DEFAULT_PREFIX})
  --max-devices <number>      Value for activationCodes.maxDevices (default: ${DEFAULT_MAX_DEVICES})
  --note <text>               Optional note saved in Firestore
  --overwrite                 Replace an existing code document
  --json                      Print machine-readable JSON output
  --help                      Show this message

Examples:
  npm run activation:create -- --count 3 --note "July batch"
  npm run activation:create -- --code PHANTOM-FULL-VIP-001 --overwrite
`);
}

function parseArgs(argv) {
  const options = {
    count: DEFAULT_COUNT,
    prefix: DEFAULT_PREFIX,
    maxDevices: DEFAULT_MAX_DEVICES,
    note: "",
    overwrite: false,
    json: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case "--project":
        options.project = requireValue(arg, argv[++i]);
        break;
      case "--code":
        options.code = normalizeCode(requireValue(arg, argv[++i]));
        break;
      case "--count":
        options.count = parsePositiveInt(arg, argv[++i]);
        break;
      case "--prefix":
        options.prefix = normalizePrefix(requireValue(arg, argv[++i]));
        break;
      case "--max-devices":
        options.maxDevices = parsePositiveInt(arg, argv[++i]);
        break;
      case "--note":
        options.note = requireValue(arg, argv[++i]);
        break;
      case "--overwrite":
        options.overwrite = true;
        break;
      case "--json":
        options.json = true;
        break;
      case "--help":
      case "-h":
        options.help = true;
        break;
      default:
        throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (options.code && options.count !== DEFAULT_COUNT) {
    throw new Error("--code cannot be combined with --count");
  }

  if (!options.code && !options.prefix) {
    throw new Error("--prefix cannot be empty when generating codes");
  }

  return options;
}

function requireValue(flag, value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${flag} requires a value`);
  }
  return value.trim();
}

function parsePositiveInt(flag, value) {
  const parsed = Number.parseInt(requireValue(flag, value), 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${flag} must be a positive integer`);
  }
  return parsed;
}

function normalizePrefix(value) {
  return value
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9-]+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");
}

function normalizeCode(value) {
  const normalized = normalizePrefix(value);
  if (!normalized) {
    throw new Error("Activation code cannot be empty");
  }
  return normalized;
}

function resolveProjectId(explicitProject) {
  if (explicitProject) {
    return explicitProject.trim();
  }

  const firebaseDir = path.resolve(__dirname, "..", "..");
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

function readFirebaseToolsConfig() {
  const configPath = resolveFirebaseToolsConfigPath();
  const raw = fs.readFileSync(configPath, "utf8");
  return {
    configPath,
    config: JSON.parse(raw),
  };
}

function readAccessTokenFromConfig() {
  const { configPath, config } = readFirebaseToolsConfig();
  const accessToken = config && config.tokens && config.tokens.access_token;
  const expiresAt = config && config.tokens && config.tokens.expires_at;

  if (accessToken && Number.isFinite(expiresAt) && expiresAt > Date.now() + TOKEN_REFRESH_GRACE_MS) {
    return accessToken;
  }

  refreshFirebaseCliToken(configPath);

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

function refreshFirebaseCliToken(configPath) {
  const npxBinary = process.platform === "win32" ? "npx.cmd" : "npx";
  try {
    execFileSync(
      npxBinary,
      ["firebase-tools", "projects:list", "--json"],
      {
        stdio: "ignore",
        cwd: path.dirname(path.dirname(configPath)),
      }
    );
  } catch (error) {
    throw new Error(
      "Firebase CLI token refresh failed. Run `firebase login --reauth` and try again."
    );
  }
}

function buildCode(prefix) {
  const now = new Date();
  const yyyymmdd = [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, "0"),
    String(now.getDate()).padStart(2, "0"),
  ].join("");
  const random = Math.random().toString(36).slice(2, 8).toUpperCase();
  return `${prefix}-${yyyymmdd}-${random}`;
}

async function getExistingDocument(docUrl, accessToken) {
  const response = await fetch(docUrl, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(`Failed to read document: ${response.status} ${errorBody}`);
  }
  return response.json();
}

async function writeActivationCode(docUrl, accessToken, { maxDevices, note }) {
  const body = {
    fields: {
      status: { stringValue: "unused" },
      maxDevices: { integerValue: String(maxDevices) },
      boundDeviceId: { nullValue: null },
      licenseId: { nullValue: null },
      usedAt: { nullValue: null },
      createdAt: { timestampValue: new Date().toISOString() },
      note: { stringValue: note || "" },
    },
  };

  const response = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(`Failed to create activation code: ${response.status} ${errorBody}`);
  }

  return response.json();
}

function activationDocUrl(projectId, code) {
  const encodedCode = encodeURIComponent(code);
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/activationCodes/${encodedCode}`;
}

async function createOneCode(projectId, accessToken, options, preferredCode) {
  const maxAttempts = preferredCode ? 1 : 20;
  let attempt = 0;

  while (attempt < maxAttempts) {
    attempt += 1;
    const code = preferredCode || buildCode(options.prefix);
    const docUrl = activationDocUrl(projectId, code);
    const existing = await getExistingDocument(docUrl, accessToken);
    if (existing && !options.overwrite) {
      if (preferredCode) {
        throw new Error(`Activation code already exists: ${code}. Use --overwrite to replace it.`);
      }
      continue;
    }

    const document = await writeActivationCode(docUrl, accessToken, options);
    return {
      code,
      document,
      overwritten: Boolean(existing),
    };
  }

  throw new Error("Unable to generate a unique activation code after multiple attempts.");
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printUsage();
    return;
  }

  const projectId = resolveProjectId(options.project);
  const accessToken = readAccessTokenFromConfig();
  const requestedCount = options.code ? 1 : options.count;
  const created = [];

  for (let i = 0; i < requestedCount; i += 1) {
    const result = await createOneCode(
      projectId,
      accessToken,
      options,
      i === 0 ? options.code : null
    );
    created.push(result);
  }

  if (options.json) {
    console.log(JSON.stringify({
      projectId,
      created: created.map((item) => ({
        code: item.code,
        overwritten: item.overwritten,
        documentName: item.document.name,
      })),
    }, null, 2));
    return;
  }

  console.log(`Created ${created.length} activation code(s) in project ${projectId}:`);
  for (const item of created) {
    const suffix = item.overwritten ? " (overwritten)" : "";
    console.log(`- ${item.code}${suffix}`);
  }
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
