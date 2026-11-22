# Registry backup/restore and rollback threat model

## What the implementation protects

The route registry stores routes, authorization state, epoch, revocations, event digests, and operation receipts in SQLite transactions. It commits accepted mutations atomically and uses an app-private marker file to detect missing or malformed initialized state. It rejects equal/older epochs relative to the state currently on disk and fails closed on detected corruption or missing initialized data. The Companion manifest sets `android:allowBackup="false"`.

These properties address transaction interruption and some local corruption. They do not create a monotonic value outside the restorable app data.

## Restore attacker and residual risk

An attacker or recovery path able to restore an older but internally consistent app-data image can restore both the SQLite database and marker file. That restores an older `highest_epoch`, route records, revocation state, and receipts. The next signed epoch greater than the restored value can then be accepted even if a newer epoch had previously been seen. An old unrevoked route may reappear. SQLite integrity checks, event hash chains, an app-local marker, and a backup opt-out cannot distinguish a complete old image from the legitimate current image.

`allowBackup=false` reduces supported Android backup/transfer paths; it is not a cryptographic anti-rollback anchor against root, debug/developer access, device imaging, OEM restore paths, filesystem rollback, or an attacker who can restore the package's private files. No evidence here establishes that all Android 17/OnePlus restore paths honor this flag as an anti-rollback guarantee.

Android Keystore signing/encryption keys alone are not a monotonic counter. A key may help detect a database that was copied without the key, but a same-device old database can remain decryptable, and key invalidation can make the registry unavailable. No hardware counter, remote transparency service, TEE monotonic storage, or independently durable registry epoch is implemented.

## Required fail-closed behavior

If trusted monotonic state is unavailable or cannot be reconciled with the database, production promotions and execution must remain disabled. The app must not silently recreate trust/epoch state after corruption, accept a request-supplied epoch as an anchor, or infer that an old database is current. The current empty authority keyset prevents production promotion, and the execution gate remains false. This is not a claim that future configured anchors are protected from app-data rollback.

**Implementation limitation:** `FastPathRegistryV1` currently compares the proposed epoch only with its SQLite `highest_epoch`; there is no external rollback-anchor interface or restore-detection check. A future non-empty trust asset would therefore permit the existing per-database epoch policy, but would not make it restore-resistant. The release must remain blocked until an approved monotonic anchor is implemented and tests cover the selected restore threat model. The written fail-closed policy is a release requirement, not a claim that such an anchor already exists.

## Recovery policy proposal

Before production, the owner must select and approve one anti-rollback authority, such as a server- or hardware-backed monotonic epoch service, and define its availability/failure semantics. Recovery from a detected mismatch should freeze authorization and execution, preserve the evidence, require an authenticated owner recovery ceremony, advance the external epoch/keyset, and explicitly decide whether routes/revocations are re-provisioned. Do not overwrite or delete the only evidence of rollback without an approved procedure.

## Evidence and unclosed tests

Existing registry tests cover transaction rollback, corruption, missing initialized databases, stale/equal epochs, concurrent epoch writers, replay after restart, and revocation persistence under the simulated JVM/SQLite environment. They do not prove detection of a restored older consistent database image, device file-system durability, Android process-kill behavior during commits, cloud/device-transfer behavior, or a secure monotonic anchor. Those remain release blockers and require Android/device or external-anchor testing.
