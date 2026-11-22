# Production authority enrollment and rotation procedure

**Current state: production authorization unavailable.** `app/src/main/assets/fastpath-authorities-v1.json` is an APK-owned trust asset with `keysetVersion: 1` and `anchors: []`. The verifier does not accept incoming public keys. No production key was supplied or generated during this work.

## Current cryptographic behavior

The implementation verifies 3072-bit RSA authority keys with RSA-PSS/SHA-256, MGF1-SHA-256, 32-byte salt, trailer field 1, canonical signing bytes, strict signature encoding, route fingerprint recomputation, issue/expiry checks, and monotonically increasing epochs inside a SQLite transaction. Registry transaction commit is required before a success acknowledgement. The exact Android API 26 provider and parameter initialization are not verified on an API 26 runtime.

The APK asset parser currently accepts keyset format version 1 and epoch acceptance ranges on individual anchors. M1 describes a root-signed keyset in a higher-version APK with overlap and retirement. This branch does not implement verification of a root signature over a rotated keyset. Packaging a changed asset into a release-signed APK alone is not evidence that the M1 root-signed rotation ceremony has been implemented or approved.

## Enrollment (owner-controlled; not performed)

1. The protocol/security owner selects the authority identity, exact `authorityKeyId`, public-key fingerprint, key custody policy, validity start epoch, and retirement policy. The production private key remains in the owner's offline signing system. This repository, developer workstation, CI, APK, and Android device receive no private signing key.
2. The owner provides the approved RSA-3072 public key in X.509 SubjectPublicKeyInfo DER form and an independently verifiable provenance statement. Review its SHA-256 fingerprint out of band against the owner's record.
3. A reviewed source change adds only the public DER key, base64 encoded, to the APK-owned keyset. Incoming messages cannot enroll, replace, or rotate a key. The test-only `AuthorityTrustV1.forTests` constructor must remain out of production call paths.
4. Before release, verify the keyset closed schema, key size, key ID uniqueness, epoch bounds, canonical signature vectors, invalid/tampered signatures, exact expiry boundaries, restart/replay behavior, and rollback gate. Run tests against the exact artifact and signer.
5. The owner reviews the source commit, key fingerprint, signed build provenance, artifact digest, release channel, and rollout/rollback plan. No approval is implied by a successful build.

## Rotation (requires a separately approved implementation)

- M1 calls for a root-signed keyset and an APK update with an overlap period and retirement epoch. This code currently only parses epoch ranges from the pinned asset; it does not validate a root signature over a keyset or enforce a keyset-version chain.
- A rotation implementation must verify the root signature using an already trusted root, strictly advance keyset version, reject rollback/replay, define overlap/retirement boundaries, and prevent a new anchor from self-authorizing. Old/new keys may overlap only within the reviewed epoch interval.
- If the root is compromised, the current proposal has no safe in-band recovery. Recovery requires a separately reviewed root replacement procedure and cannot be implemented by trusting a request field.
- A failed/unknown keyset update, missing root, invalid signature, unsupported crypto provider, storage failure, or unavailable anti-rollback anchor must fail closed: no production promotion authorization and no execution.

## Required owner approvals

- [ ] Production authority identity and public key provenance/fingerprint.
- [ ] M1-compliant root-signed keyset and rotation design/implementation.
- [ ] Exact cryptographic provider compatibility on API 26 and supported Android versions.
- [ ] Release signer, source commit, and artifact provenance.
- [ ] Durable anti-rollback policy and recovery procedure.

No production key, private key, or production key enrollment was included in this candidate change.
