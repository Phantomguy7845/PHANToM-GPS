const crypto = require("crypto");
const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

const LICENSE_CHECK_SECONDS = 21600;
const DEFAULT_ACTIVATION_CODE_PREFIX = "PHANTOM-FULL";
const TEMP_ACTIVATION_CODE_PREFIX = "PHANTOM-TEMP";
const TEMP_ACTIVATION_CODE_TTL_MS = 60 * 1000;

function pickNextCheckSeconds() {
  return LICENSE_CHECK_SECONDS;
}

function serverTimestamp() {
  return admin.firestore.FieldValue.serverTimestamp();
}

function timestampFromMillis(value) {
  return admin.firestore.Timestamp.fromMillis(value);
}

function asTrimmedString(value) {
  return typeof value === "string" ? value.trim() : "";
}

function sha256Hex(value) {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function randomSecret() {
  return crypto.randomBytes(32).toString("base64url");
}

function normalizeActivationCodePart(value) {
  return asTrimmedString(value)
    .toUpperCase()
    .replace(/[^A-Z0-9-]+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");
}

function createRandomActivationCode(prefix = DEFAULT_ACTIVATION_CODE_PREFIX) {
  const normalizedPrefix = normalizeActivationCodePart(prefix) || DEFAULT_ACTIVATION_CODE_PREFIX;
  const yyyymmdd = new Date().toISOString().slice(0, 10).replace(/-/g, "");
  const randomPart = crypto.randomBytes(4)
    .toString("base64url")
    .replace(/[^A-Za-z0-9]/g, "")
    .toUpperCase()
    .slice(0, 6)
    .padEnd(6, "X");
  return `${normalizedPrefix}-${yyyymmdd}-${randomPart}`;
}

function parsePositiveInteger(value, defaultValue, fieldName) {
  if (value === undefined || value === null || value === "") {
    return defaultValue;
  }
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      `${fieldName} must be a positive integer`,
      { code: `${fieldName.toUpperCase()}_INVALID` }
    );
  }
  return parsed;
}

function parseBoolean(value, defaultValue, fieldName) {
  if (value === undefined || value === null || value === "") {
    return defaultValue;
  }
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();
    if (normalized === "true") {
      return true;
    }
    if (normalized === "false") {
      return false;
    }
  }
  throw new functions.https.HttpsError(
    "invalid-argument",
    `${fieldName} must be a boolean`,
    { code: `${fieldName.toUpperCase()}_INVALID` }
  );
}

function buildActivationCodeFields(existingActivation, maxDevices, note) {
  return {
    status: "unused",
    maxDevices,
    boundDeviceId: null,
    licenseId: null,
    usedAt: null,
    createdAt: (existingActivation && existingActivation.createdAt) || serverTimestamp(),
    note: note !== undefined ? note : (existingActivation && existingActivation.note) || "",
    ephemeral: false,
    installerId: null,
    expiresAt: null,
    revokedAt: null,
    revokeReason: null,
  };
}

function buildTemporaryActivationCodeFields(installerId, expiresAt) {
  return {
    ...buildActivationCodeFields(null, 1, "Temporary installer activation code"),
    ephemeral: true,
    installerId,
    expiresAt,
  };
}

function revokeLicenseFields(revokeReason) {
  return {
    status: "revoked",
    revokedAt: serverTimestamp(),
    revokeReason,
  };
}

function throwCode(status, code) {
  throw new functions.https.HttpsError(status, code, { code });
}

function requireString(value, name, code = "invalid-argument") {
  const normalized = asTrimmedString(value);
  if (!normalized) {
    throw new functions.https.HttpsError(code, `${name} required`, { code: `${name.toUpperCase()}_REQUIRED` });
  }
  return normalized;
}

function requireAdmin(context) {
  if (!context.auth || context.auth.token.admin !== true) {
    throwCode("permission-denied", "ADMIN_REQUIRED");
  }
}

function timestampToMillis(value) {
  if (!value) {
    return 0;
  }
  if (typeof value.toMillis === "function") {
    return value.toMillis();
  }
  if (typeof value._seconds === "number") {
    return (value._seconds * 1000) + Math.floor((value._nanoseconds || 0) / 1000000);
  }
  if (typeof value.seconds === "number") {
    return (value.seconds * 1000) + Math.floor((value.nanoseconds || 0) / 1000000);
  }
  return 0;
}

function isExpiredEphemeralActivation(activation, nowMillis = Date.now()) {
  if (!activation || activation.ephemeral !== true) {
    return false;
  }
  const expiresAtMillis = timestampToMillis(activation.expiresAt);
  return expiresAtMillis > 0 && expiresAtMillis <= nowMillis;
}

function getActivationStatus(activation) {
  const boundDeviceId = asTrimmedString(activation && activation.boundDeviceId);
  const licenseId = asTrimmedString(activation && activation.licenseId);
  return (activation && activation.status) || (boundDeviceId || licenseId ? "used" : "unused");
}

async function cleanupExpiredInstallerSessionsBatch(limit = 100) {
  const now = timestampFromMillis(Date.now());
  const sessionsSnap = await db.collection("installerSessions")
    .where("expiresAt", "<=", now)
    .limit(limit)
    .get();

  if (sessionsSnap.empty) {
    return 0;
  }

  const batch = db.batch();
  sessionsSnap.docs.forEach((sessionDoc) => {
    const session = sessionDoc.data() || {};
    const activationCode = asTrimmedString(session.currentActivationCode);
    if (activationCode) {
      batch.delete(db.collection("activationCodes").doc(activationCode));
    }
    batch.delete(sessionDoc.ref);
  });
  await batch.commit();
  return sessionsSnap.size;
}

async function cleanupExpiredInstallerSessions() {
  let removed = 0;
  for (let i = 0; i < 5; i += 1) {
    const count = await cleanupExpiredInstallerSessionsBatch(100);
    removed += count;
    if (count < 100) {
      break;
    }
  }
  return removed;
}

async function issueTemporaryActivationCode(installerId) {
  const sessionRef = db.collection("installerSessions").doc(installerId);
  const nowMillis = Date.now();
  const expiresAtMillis = Date.now() + TEMP_ACTIVATION_CODE_TTL_MS;
  const expiresAt = timestampFromMillis(expiresAtMillis);
  let response = null;

  await db.runTransaction(async (tx) => {
    const sessionSnap = await tx.get(sessionRef);
    if (sessionSnap.exists) {
      const session = sessionSnap.data() || {};
      const previousCode = asTrimmedString(session.currentActivationCode);
      if (previousCode) {
        const previousRef = db.collection("activationCodes").doc(previousCode);
        const previousSnap = await tx.get(previousRef);
        if (previousSnap.exists) {
          const previousActivation = previousSnap.data() || {};
          const previousStatus = getActivationStatus(previousActivation);
          if (
            previousActivation.ephemeral === true &&
            previousStatus === "unused" &&
            !isExpiredEphemeralActivation(previousActivation, nowMillis)
          ) {
            response = {
              ok: true,
              activationCode: previousCode,
              expiresAtMillis: timestampToMillis(previousActivation.expiresAt),
              expiresInSeconds: Math.max(
                0,
                Math.floor((timestampToMillis(previousActivation.expiresAt) - nowMillis) / 1000)
              ),
            };
            return;
          }

          if (previousActivation.ephemeral === true && previousStatus === "unused") {
            tx.delete(previousRef);
          }
        }
      }
    }

    for (let attempt = 0; attempt < 20 && !response; attempt += 1) {
      const activationCode = createRandomActivationCode(TEMP_ACTIVATION_CODE_PREFIX);
      const activationRef = db.collection("activationCodes").doc(activationCode);
      const activationSnap = await tx.get(activationRef);
      if (activationSnap.exists) {
        continue;
      }

      tx.set(activationRef, buildTemporaryActivationCodeFields(installerId, expiresAt), { merge: true });
      tx.set(sessionRef, {
        currentActivationCode: activationCode,
        expiresAt,
        updatedAt: serverTimestamp(),
      }, { merge: true });

      response = {
        ok: true,
        activationCode,
        expiresAtMillis,
        expiresInSeconds: Math.floor(TEMP_ACTIVATION_CODE_TTL_MS / 1000),
      };
    }

    if (!response) {
      throw new Error("UNABLE_TO_GENERATE_UNIQUE_TEMP_CODE");
    }
  });

  return response;
}

exports.createActivationCode = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    requireAdmin(context);

    const rawRequestedCode = asTrimmedString(data && data.activationCode);
    const requestedCode = rawRequestedCode ? normalizeActivationCodePart(rawRequestedCode) : "";
    if (rawRequestedCode && !requestedCode) {
      throwCode("invalid-argument", "ACTIVATION_CODE_INVALID");
    }

    const prefix = normalizeActivationCodePart(data && data.prefix) || DEFAULT_ACTIVATION_CODE_PREFIX;
    const maxDevices = parsePositiveInteger(data && data.maxDevices, 1, "maxDevices");
    const count = parsePositiveInteger(data && data.count, 1, "count");
    const note = asTrimmedString(data && data.note);
    const overwrite = parseBoolean(data && data.overwrite, false, "overwrite");

    if (requestedCode && count !== 1) {
      throwCode("invalid-argument", "COUNT_NOT_ALLOWED_WITH_ACTIVATION_CODE");
    }

    const targetCount = requestedCode ? 1 : count;
    const created = [];

    for (let i = 0; i < targetCount; i += 1) {
      let candidateCode = requestedCode;
      let createdResult = null;

      for (let attempt = 0; attempt < 20 && !createdResult; attempt += 1) {
        if (!candidateCode) {
          candidateCode = createRandomActivationCode(prefix);
        }

        const activationRef = db.collection("activationCodes").doc(candidateCode);

        createdResult = await db.runTransaction(async (tx) => {
          const activationSnap = await tx.get(activationRef);
          const existingActivation = activationSnap.exists ? (activationSnap.data() || {}) : null;

          if (existingActivation) {
            const existingLicenseId = asTrimmedString(existingActivation.licenseId);
            const existingStatus = existingActivation.status || (existingLicenseId ? "used" : "unused");

            if (!overwrite) {
              return null;
            }

            if (existingStatus === "used" || existingLicenseId) {
              throwCode("failed-precondition", "ACTIVATION_CODE_IN_USE");
            }
          }

          tx.set(
            activationRef,
            buildActivationCodeFields(existingActivation, maxDevices, note),
            { merge: true }
          );

          return {
            activationCode: candidateCode,
            overwritten: Boolean(existingActivation),
          };
        });

        if (requestedCode && !createdResult) {
          throwCode("already-exists", "ACTIVATION_CODE_ALREADY_EXISTS");
        }

        if (!createdResult) {
          candidateCode = "";
        }
      }

      if (!createdResult) {
        throwCode("already-exists", "UNABLE_TO_GENERATE_UNIQUE_ACTIVATION_CODE");
      }

      created.push(createdResult);
    }

    if (requestedCode) {
      return {
        ok: true,
        activationCode: created[0].activationCode,
        overwritten: created[0].overwritten,
      };
    }

    return {
      ok: true,
      created,
    };
  });

exports.issueTemporaryActivationCode = functions
  .region("asia-southeast1")
  .https.onRequest(async (req, res) => {
    res.set("Cache-Control", "no-store");

    if (req.method !== "POST") {
      res.status(405).json({
        ok: false,
        code: "METHOD_NOT_ALLOWED",
      });
      return;
    }

    const installerId = asTrimmedString(req.body && req.body.installerId);
    if (!installerId || installerId.length > 128) {
      res.status(400).json({
        ok: false,
        code: "INSTALLER_ID_REQUIRED",
      });
      return;
    }

    try {
      await cleanupExpiredInstallerSessions();
      const result = await issueTemporaryActivationCode(installerId);
      res.status(200).json(result);
    } catch (error) {
      console.error("issueTemporaryActivationCode failed", error);
      res.status(500).json({
        ok: false,
        code: "TEMP_CODE_CREATE_FAILED",
      });
    }
  });

exports.cleanupExpiredInstallerActivationCodes = functions
  .region("asia-southeast1")
  .pubsub.schedule("* * * * *")
  .timeZone("Asia/Bangkok")
  .onRun(async () => {
    const removed = await cleanupExpiredInstallerSessions();
    if (removed > 0) {
      console.log(`Removed ${removed} expired installer activation sessions`);
    }
    return null;
  });

exports.switchSessionAndRevokeOthers = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    if (!context.auth) {
      throw new functions.https.HttpsError("unauthenticated", "Login required");
    }
    const uid = context.auth.uid;

    const deviceId = data && typeof data.deviceId === "string" ? data.deviceId.trim() : "";
    if (!deviceId) {
      throw new functions.https.HttpsError("invalid-argument", "deviceId required");
    }

    const userRef = db.collection("users").doc(uid);

    const newVersion = await db.runTransaction(async (tx) => {
      const snap = await tx.get(userRef);
      const cur = snap.exists ? (snap.data().sessionVersion || 0) : 0;
      const next = cur + 1;

      tx.set(
        userRef,
        {
          sessionVersion: next,
          activeDeviceId: deviceId,
          requiredOnline: true,
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );

      return next;
    });

    await admin.auth().revokeRefreshTokens(uid);

    return {
      ok: true,
      sessionVersion: newVersion,
      nextCheckSeconds: pickNextCheckSeconds(),
    };
  });

exports.checkSession = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    if (!context.auth) {
      throw new functions.https.HttpsError("unauthenticated", "Login required");
    }
    const uid = context.auth.uid;

    const deviceId = data && typeof data.deviceId === "string" ? data.deviceId.trim() : "";
    const clientSessionVersion = data ? data.clientSessionVersion : null;

    if (!deviceId) {
      throw new functions.https.HttpsError("invalid-argument", "deviceId required");
    }
    if (typeof clientSessionVersion !== "number") {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "clientSessionVersion required (number)"
      );
    }

    const snap = await db.collection("users").doc(uid).get();
    if (!snap.exists) {
      throw new functions.https.HttpsError("permission-denied", "NO_PROFILE");
    }

    const u = snap.data() || {};
    const serverVersion = u.sessionVersion || 0;
    const activeDeviceId = u.activeDeviceId || "";

    if (deviceId !== activeDeviceId || clientSessionVersion !== serverVersion) {
      throw new functions.https.HttpsError("permission-denied", "SESSION_REPLACED", {
        code: "SESSION_REPLACED",
        serverVersion,
      });
    }

    return {
      ok: true,
      serverVersion,
      nextCheckSeconds: pickNextCheckSeconds(),
    };
  });

exports.activateDevice = functions
  .region("asia-southeast1")
  .https.onCall(async (data) => {
    const activationCode = normalizeActivationCodePart(
      requireString(data && data.activationCode, "activationCode")
    );
    if (!activationCode) {
      throwCode("invalid-argument", "ACTIVATION_CODE_INVALID");
    }
    const deviceId = requireString(data && data.deviceId, "deviceId");
    const appVersion = requireString(data && data.appVersion, "appVersion");
    const packageName = requireString(data && data.packageName, "packageName");

    const activationRef = db.collection("activationCodes").doc(activationCode);
    let response = null;

    await db.runTransaction(async (tx) => {
      const activationSnap = await tx.get(activationRef);
      if (!activationSnap.exists) {
        throwCode("not-found", "ACTIVATION_CODE_NOT_FOUND");
      }

      const activation = activationSnap.data() || {};
      const boundDeviceId = asTrimmedString(activation.boundDeviceId);
      const currentLicenseId = asTrimmedString(activation.licenseId);
      const installerId = asTrimmedString(activation.installerId);
      const status = getActivationStatus(activation);

      if (status === "unused" && isExpiredEphemeralActivation(activation)) {
        tx.delete(activationRef);
        if (installerId) {
          tx.delete(db.collection("installerSessions").doc(installerId));
        }
        throwCode("deadline-exceeded", "ACTIVATION_CODE_EXPIRED");
      }

      if (status === "revoked") {
        throwCode("permission-denied", "ACTIVATION_CODE_REVOKED");
      }

      if (status === "used" && boundDeviceId && boundDeviceId !== deviceId) {
        throwCode("permission-denied", "CODE_ALREADY_USED");
      }

      const licenseSecret = randomSecret();
      const licenseSecretHash = sha256Hex(licenseSecret);
      let licenseId = currentLicenseId;
      let licenseRef = licenseId
        ? db.collection("licenses").doc(licenseId)
        : db.collection("licenses").doc();

      if (!licenseId) {
        licenseId = licenseRef.id;
      }

      if (status === "used" && currentLicenseId) {
        const existingLicenseSnap = await tx.get(licenseRef);
        if (!existingLicenseSnap.exists) {
          tx.set(licenseRef, {
            status: "active",
            activationCode,
            deviceId,
            packageName,
            appVersionAtActivation: appVersion,
            licenseSecretHash,
            boundAt: serverTimestamp(),
            lastCheckAt: serverTimestamp(),
            revokedAt: null,
            revokeReason: null,
          });
        } else {
          const existingLicense = existingLicenseSnap.data() || {};
          if ((existingLicense.status || "active") === "revoked") {
            throwCode("permission-denied", "LICENSE_REVOKED");
          }
          tx.set(
            licenseRef,
            {
              status: "active",
              activationCode,
              deviceId,
              packageName,
              appVersionAtActivation: existingLicense.appVersionAtActivation || appVersion,
              licenseSecretHash,
              lastCheckAt: serverTimestamp(),
              revokedAt: null,
              revokeReason: null,
            },
            { merge: true }
          );
        }
      } else {
        tx.set(licenseRef, {
          status: "active",
          activationCode,
          deviceId,
          packageName,
          appVersionAtActivation: appVersion,
          licenseSecretHash,
          boundAt: serverTimestamp(),
          lastCheckAt: serverTimestamp(),
          revokedAt: null,
          revokeReason: null,
        });
      }

      tx.set(
        activationRef,
        {
          status: "used",
          maxDevices: activation.maxDevices || 1,
          boundDeviceId: deviceId,
          licenseId,
          createdAt: activation.createdAt || serverTimestamp(),
          usedAt: serverTimestamp(),
          note: activation.note || "",
        },
        { merge: true }
      );
      if (installerId) {
        tx.delete(db.collection("installerSessions").doc(installerId));
      }

      response = {
        ok: true,
        licenseId,
        licenseSecret,
        nextCheckSeconds: LICENSE_CHECK_SECONDS,
      };
    });

    return response;
  });

exports.checkLicense = functions
  .region("asia-southeast1")
  .https.onCall(async (data) => {
    const licenseId = requireString(data && data.licenseId, "licenseId");
    const licenseSecret = requireString(data && data.licenseSecret, "licenseSecret");
    const deviceId = requireString(data && data.deviceId, "deviceId");
    const appVersion = requireString(data && data.appVersion, "appVersion");
    const packageName = requireString(data && data.packageName, "packageName");

    const licenseRef = db.collection("licenses").doc(licenseId);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(licenseRef);
      if (!snap.exists) {
        throwCode("permission-denied", "LICENSE_NOT_FOUND");
      }

      const license = snap.data() || {};
      if ((license.status || "active") !== "active") {
        if (license.status === "revoked") {
          throwCode("permission-denied", "LICENSE_REVOKED");
        }
        throwCode("permission-denied", "INVALID_LICENSE");
      }

      if (asTrimmedString(license.deviceId) !== deviceId) {
        throwCode("permission-denied", "DEVICE_MISMATCH");
      }

      const storedPackageName = asTrimmedString(license.packageName);
      if (storedPackageName && storedPackageName !== packageName) {
        throwCode("permission-denied", "PACKAGE_MISMATCH");
      }

      if (asTrimmedString(license.licenseSecretHash) !== sha256Hex(licenseSecret)) {
        throwCode("permission-denied", "INVALID_LICENSE");
      }

      tx.set(
        licenseRef,
        {
          lastCheckAt: serverTimestamp(),
          lastAppVersion: appVersion,
        },
        { merge: true }
      );
    });

    return {
      ok: true,
      nextCheckSeconds: LICENSE_CHECK_SECONDS,
    };
  });

exports.revokeLicense = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    requireAdmin(context);
    const licenseId = requireString(data && data.licenseId, "licenseId");
    const revokeReason = asTrimmedString(data && data.revokeReason) || "MANUAL_REVOKE";

    await db.collection("licenses").doc(licenseId).set(
      {
        status: "revoked",
        revokedAt: serverTimestamp(),
        revokeReason,
      },
      { merge: true }
    );

    return { ok: true };
  });

exports.revokeActivationCode = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    requireAdmin(context);
    const activationCode = normalizeActivationCodePart(
      requireString(data && data.activationCode, "activationCode")
    );
    if (!activationCode) {
      throwCode("invalid-argument", "ACTIVATION_CODE_INVALID");
    }

    const revokeReason = asTrimmedString(data && data.revokeReason) || "ACTIVATION_CODE_REVOKED";
    const revokeLinkedLicense = parseBoolean(
      data && data.revokeLinkedLicense,
      true,
      "revokeLinkedLicense"
    );
    const activationRef = db.collection("activationCodes").doc(activationCode);
    let response = null;

    await db.runTransaction(async (tx) => {
      const activationSnap = await tx.get(activationRef);
      if (!activationSnap.exists) {
        throwCode("not-found", "ACTIVATION_CODE_NOT_FOUND");
      }

      const activation = activationSnap.data() || {};
      const licenseId = asTrimmedString(activation.licenseId);

      if (revokeLinkedLicense && licenseId) {
        tx.set(
          db.collection("licenses").doc(licenseId),
          revokeLicenseFields(revokeReason),
          { merge: true }
        );
      }

      tx.set(
        activationRef,
        {
          status: "revoked",
          revokedAt: serverTimestamp(),
          revokeReason,
        },
        { merge: true }
      );

      response = {
        ok: true,
        activationCode,
        licenseId: licenseId || null,
        licenseRevoked: Boolean(revokeLinkedLicense && licenseId),
      };
    });

    return response;
  });

exports.resetActivationCode = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    requireAdmin(context);
    const activationCode = normalizeActivationCodePart(
      requireString(data && data.activationCode, "activationCode")
    );
    if (!activationCode) {
      throwCode("invalid-argument", "ACTIVATION_CODE_INVALID");
    }
    const resetReason = asTrimmedString(data && data.resetReason) || "RESET_ACTIVATION_CODE";
    const activationRef = db.collection("activationCodes").doc(activationCode);
    let response = null;

    await db.runTransaction(async (tx) => {
      const activationSnap = await tx.get(activationRef);
      if (!activationSnap.exists) {
        throwCode("not-found", "ACTIVATION_CODE_NOT_FOUND");
      }

      const activation = activationSnap.data() || {};
      const licenseId = asTrimmedString(activation.licenseId);
      if (licenseId) {
        tx.set(
          db.collection("licenses").doc(licenseId),
          revokeLicenseFields(resetReason),
          { merge: true }
        );
      }

      tx.set(
        activationRef,
        {
          status: "unused",
          boundDeviceId: null,
          licenseId: null,
          usedAt: null,
          ephemeral: false,
          installerId: null,
          expiresAt: null,
          resetAt: serverTimestamp(),
          revokedAt: null,
          revokeReason: null,
        },
        { merge: true }
      );

      response = {
        ok: true,
        activationCode,
        previousLicenseId: licenseId || null,
      };
    });

    return response;
  });
