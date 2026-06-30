#!/usr/bin/env node

const {
  getDocument,
  getStringField,
  normalizeActivationCode,
  nullField,
  patchDocument,
  readAccessTokenFromConfig,
  resolveProjectId,
  stringField,
  timestampNowField,
} = require("./lib/firebase-rest-admin");

function printUsage() {
  console.log(`Reset an activation code back to unused in Firestore.

Usage:
  npm run activation:reset -- --code <activationCode> [options]

Options:
  --project <projectId>          Firebase project id. Defaults to ../.firebaserc
  --code <activationCode>        Activation code to reset
  --reason <text>                Revoke reason for the linked license (default: RESET_ACTIVATION_CODE)
  --json                         Print machine-readable JSON output
  --help                         Show this message
`);
}

function parseArgs(argv) {
  const options = {
    reason: "RESET_ACTIVATION_CODE",
    json: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case "--project":
        options.project = requireValue(arg, argv[++i]);
        break;
      case "--code":
        options.code = normalizeActivationCode(requireValue(arg, argv[++i]));
        break;
      case "--reason":
        options.reason = requireValue(arg, argv[++i]);
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

  if (!options.help && !options.code) {
    throw new Error("--code is required");
  }

  return options;
}

function requireValue(flag, value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${flag} requires a value`);
  }
  return value.trim();
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printUsage();
    return;
  }

  const projectId = resolveProjectId(options.project);
  const accessToken = readAccessTokenFromConfig();
  const activationPath = ["activationCodes", options.code];
  const activation = await getDocument(projectId, activationPath, accessToken);
  if (!activation) {
    throw new Error(`Activation code not found: ${options.code}`);
  }

  const licenseId = getStringField(activation, "licenseId");
  if (licenseId) {
    await patchDocument(projectId, ["licenses", licenseId], accessToken, {
      status: stringField("revoked"),
      revokedAt: timestampNowField(),
      revokeReason: stringField(options.reason),
    });
  }

  const updated = await patchDocument(projectId, activationPath, accessToken, {
    status: stringField("unused"),
    boundDeviceId: nullField(),
    licenseId: nullField(),
    usedAt: nullField(),
    resetAt: timestampNowField(),
    revokedAt: nullField(),
    revokeReason: nullField(),
  });

  const output = {
    projectId,
    activationCode: options.code,
    previousLicenseId: licenseId || null,
    documentName: updated.name,
  };

  if (options.json) {
    console.log(JSON.stringify(output, null, 2));
    return;
  }

  console.log(`Reset activation code ${options.code} back to unused`);
  if (licenseId) {
    console.log(`- revoked previous license: ${licenseId}`);
  }
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
