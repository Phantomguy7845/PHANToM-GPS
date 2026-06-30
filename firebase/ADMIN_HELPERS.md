# Admin Helpers

## Create activation codes

Run from:

`D:\Projects\Android\GPSSetter\PHANToM GPS\firebase\functions`

Examples:

```bash
npm run activation:create -- --count 3 --note "July batch"
```

```bash
npm run activation:create -- --code PHANTOM-FULL-VIP-001 --overwrite
```

## Revoke activation codes

```bash
npm run activation:revoke -- --code PHANTOM-FULL-VIP-001 --reason "Chargeback"
```

If you want to keep the linked license document unchanged:

```bash
npm run activation:revoke -- --code PHANTOM-FULL-VIP-001 --skip-license-revoke
```

## Reset activation codes

```bash
npm run activation:reset -- --code PHANTOM-FULL-VIP-001
```

Useful options:

- `--project phantom-gps`
- `--prefix PHANTOM-FULL`
- `--count 10`
- `--max-devices 1`
- `--note "Customer A"`
- `--json`

Notes:

- The helper uses your Firebase CLI login from `firebase login`.
- If the CLI token is stale, it will try to refresh it automatically.
- Created documents are written to `activationCodes/{activationCode}` in Firestore.
- Revoke and reset helpers will also update the linked `licenses/{licenseId}` document when applicable.

## Callable admin functions for future UI

These callable functions now exist in Firebase Functions:

- `createActivationCode`
- `revokeActivationCode`
- `resetActivationCode`
- `revokeLicense`

Important:

- These callable functions require a Firebase Auth user whose custom claims include `admin: true`.
- Your local CLI helpers do not need that claim because they write through the Firestore REST API using your Firebase CLI login.
