#!/usr/bin/env node

const {
  getDocument,
  getStringField,
  normalizeActivationCode,
  patchDocument,
  readAccessTokenFromConfig,
  resolveProjectId,
  stringField,
  timestampNowField,
} = require("./lib/firebase-rest-admin");

function printUsage() {
  console.log(`Revoke an activation code in Firestore.

Usage:
  npm run activation:revoke -- --code <activationCode> [options]

Options:
  --project <projectId>          Firebase project id. Defaults to ../.firebaserc
  --code <activationCode>        Activation code to revoke
  --reason <text>                Revoke reason (default: ACTIVATION_CODE_REVOKED)
  --skip-license-revoke          Do not revoke the linked license document
  --json                         Print machine-readable JSON output
  --help                         Show this message
`);
}

function parseArgs(argv) {
  const options = {
    reason: "ACTIVATION_CODE_REVOKED",
    revokeLinkedLicense: true,
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
      case "--skip-license-revoke":
        options.revokeLinkedLicense = false;
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
  if (options.revokeLinkedLicense && licenseId) {
    await patchDocument(projectId, ["licenses", licenseId], accessToken, {
      status: stringField("revoked"),
      revokedAt: timestampNowField(),
      revokeReason: stringField(options.reason),
    });
  }

  const updated = await patchDocument(projectId, activationPath, accessToken, {
    status: stringField("revoked"),
    revokedAt: timestampNowField(),
    revokeReason: stringField(options.reason),
  });

  const output = {
    projectId,
    activationCode: options.code,
    licenseId: licenseId || null,
    licenseRevoked: Boolean(options.revokeLinkedLicense && licenseId),
    documentName: updated.name,
  };

  if (options.json) {
    console.log(JSON.stringify(output, null, 2));
    return;
  }

  console.log(`Revoked activation code ${options.code}`);
  if (licenseId) {
    console.log(`- linked license: ${licenseId}`);
    console.log(`- license revoked: ${options.revokeLinkedLicense ? "yes" : "no"}`);
  }
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
