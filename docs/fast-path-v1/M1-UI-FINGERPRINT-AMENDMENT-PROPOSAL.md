# M1 amendment proposal: Android UI snapshot fingerprints

**Status: PROPOSAL — NOT APPROVED.** This document does not amend or reinterpret `PROTOCOL.md` or `protocol.schema.json`. The candidate implementation is isolated from promotion, receiver responses, registry lookup, and production execution. An M1 owner must approve the contract and vectors before any route may rely on it.

## Why an amendment is needed

M1 defines the signed route fingerprint (`tessera.route-canonical/1`) and names `stateFingerprint` values in route state constraints. It also defines the bounded schema-3 Accessibility evidence shape. It does not define how an Android UI tree becomes the `stateFingerprint`, which UI properties are stable, or what `snapshot.sha256` hashes. Those are separate digests and must not be conflated.

## Proposed identifiers

- UI state algorithm: `tessera.ui-state-candidate/2`.
- Evidence snapshot schema remains M1 schema `3`; this proposal adds no M1 message field.
- The existing route digest remains exactly `tessera.route-canonical/1` and is not changed by this proposal.

## Snapshot validation and capture

1. Capture one active Accessibility window. Record the foreground package, `windowId`, `windowIdBefore`, `windowIdAfter`, wall observation time, monotonic capture time, and the exact schema-3 traversal metadata already named by M1.
2. Require package identity to match the caller's expected package. Require all nodes to belong to the captured window. Require before/after window IDs to equal the captured ID.
3. Traverse one root using breadth-first FIFO order. Enumerate each parent's children in ascending child index. Assign contiguous node indexes in discovery order and record each node's `parentIndex` and `depth`. Reject missing children, invalid parent references, a second root, depth overflow, identity drift, or duplicate indexes. Do not sort nodes by labels or hashes.
4. Bounds: at most 4096 nodes, maximum depth 64, traversal budget at most 250 ms, canonical state input at most 1 MiB, full schema-3 snapshot JSON at most 4 MiB, and candidate fingerprint processing at most 250 ms. Candidate snapshot construction/serialization may take at most 500 ms total. Any limit breach makes the snapshot incomplete and unusable for matching.
5. Require a complete, non-truncated snapshot with no traversal error. Use elapsed realtime for age; require `0 <= nowElapsed - capturedAtElapsed <= 2500 ms`. Reject future monotonic timestamps.
6. Text, descriptions, resource IDs, and class names are bounded by the existing schema maxima. Reject unpaired UTF-16 surrogates. Normalize strings to Unicode NFC; preserve case and whitespace. Do not trim, case-fold, or apply locale-sensitive transforms.

## Null, absent, and empty values

The M1 schema permits optional node string properties but does not permit `null` for those properties. In an M1 schema-3 snapshot, an omitted optional string means “not supplied”; an empty string is a supplied empty value. The candidate's typed canonical model represents an omitted optional string as JSON `null`, and preserves `""` as an empty string. A wire property whose value is JSON `null` is schema-invalid. This mapping is explicit and does not change the M1 JSON schema.

## Proposed canonical UI-state input

Construct the following object for SHA-256. Object serialization reuses the M1 canonical JSON rules: NFC strings, Unicode-code-point key ordering, standard JSON string escaping, integer numbers only, no whitespace, and UTF-8. Arrays retain the traversal order shown below.

```json
{
  "fingerprintAlgorithm": "tessera.ui-state-candidate/2",
  "packageName": "com.example.app",
  "nodes": [
    {
      "parentIndex": -1,
      "depth": 0,
      "text": null,
      "description": null,
      "resourceId": "com.example.app:id/root",
      "className": "android.view.ViewGroup",
      "visible": true,
      "enabled": true,
      "clickable": false,
      "focusable": false,
      "selected": false,
      "checked": false,
      "password": false
    }
  ]
}
```

The lowercase state fingerprint is `hex(SHA-256(UTF-8(canonicalJson(object))))`. All listed node properties are present in the canonical object. Text/description/resource ID/class name use the mapping above; boolean values are explicit. For password nodes, canonical `text` and `description` are always `null`, even if Android supplies masked text. Node order and parent/depth values preserve structure. Duplicate-looking nodes remain separate ordered nodes.

The UI-state digest excludes bounds, transient node IDs, window IDs, snapshot UUID, timestamps, producer provenance, traversal counters, and the snapshot digest. Array position and the explicit `parentIndex`/`depth` fields remain because they encode hierarchy and order. Package name is included. Window identity, freshness, traversal completeness, and package equality are checked separately before the digest can be considered. Excluding window/timestamp values keeps equivalent screens stable across captures while the independent checks still bind evidence to the current package/window and time.

### Proposed `snapshot.sha256`

Separately, `snapshot.sha256` is proposed as the SHA-256 of canonical JSON for the complete schema-3 snapshot object with only the `sha256` member omitted. This binds raw evidence and its capture/provenance metadata; it is not the state fingerprint and is expected to change for otherwise equivalent captures. The candidate service follows this proposal. M1 presently requires a 64-hex value but does not define its input, so this meaning also needs approval.

## Markers and matching

- Required markers are exact strings associated with an explicitly named field: `text`, `description`, `resourceId`, or `className`.
- Compare NFC-normalized values exactly. Do not use substring, edit-distance, fuzzy, locale, coordinate, or selector substitution behavior.
- Count only visible, non-password nodes. A required marker must match exactly one node. Zero matches fail; multiple matches are ambiguous and fail.
- Match package and window before fingerprint/marker evaluation. Reject stale, incomplete, truncated, or ambiguous snapshots. No prior snapshot may be reused after a dispatch boundary.

## Cross-language vectors and implementation status

The candidate Kotlin and host implementations share `ui-state-candidate-v2-vectors.json`. The vector `ordered-hierarchy-unicode-and-stable-properties` has expected digest `cf5ad5e1ead738b30ad326b5cfe512e8c6912bb148e6608ec097ca72860fbf68`. It covers ordered hierarchy, NFC equivalence, and stable properties. Tests also cover volatile window/time exclusion from the state hash, changed ordering/hierarchy/state, password redaction, malformed trees, identity drift, freshness, and size limits.

The Android collector is a candidate read-only Accessibility service in a separate process. It is not enabled by the app and does not emit an M1 protocol response. A passing host/JVM vector proves only that the candidate encoders agree on the fixture; it does not approve the contract or prove Android API 26 behavior.

## Compatibility and migration

No existing M1 route, signature, route fingerprint, promotion, or stored `stateFingerprint` is rewritten. Approval would require a versioned migration decision. Existing routes must not be reinterpreted under this algorithm. Any future authority policy using this digest must identify the algorithm version in its reviewed route lifecycle and require a newly authorized route record; old signed promotions cannot silently acquire new semantics.

## Owner approval checklist

- [ ] Protocol owner approves the algorithm identifier, schema mapping, traversal order, canonical input, limits, freshness, password handling, marker semantics, and `snapshot.sha256` meaning.
- [ ] Security/privacy owner approves the collected properties, ephemeral evidence handling, and password redaction.
- [ ] Host and Android owners accept the shared vectors and add approved vectors to the canonical M1 test set.
- [ ] API 26 provider behavior and current supported API behavior are tested on actual runtimes.
- [ ] Independent observer architecture, APK provenance, and signing/build approval are completed separately.
- [ ] Approved protocol source is updated through the normal review path; this proposal is not sufficient approval.
