# Codex Companion v2 — Native Action Bridge

This APK upgrades the previous Edge Glow application **in place** using the existing application ID `com.codexedge.glow`. It contains the same four edge-only glow windows, foreground notification, lease service, screen-accessibility snapshot, Unicode input bridge, and **one** accessibility service, plus a new exact-target native-click receiver.

## Native click contract (ADB-only)

- Android package: `com.codexedge.glow`
- Receiver: `com.codexedge.glow/.NativeActionReceiver`
- Action: `com.codexedge.glow.NATIVE_CLICK`
- Permission: `android.permission.DUMP` (ordinary apps cannot send)
- Extras: `request_id`, `target_package`, `selector_type`, `selector_value`
- Selector types: `text`, `description`, `resourceId`; matches must be exact and unique.
- Result: `adb shell run-as com.codexedge.glow cat files/native-action-result.json`
- Report: `DISPATCH_ACCEPTED_UNVERIFIED`, `DISPATCH_REJECTED`, `DISPATCH_UNCERTAIN`, `REFUSED`.
- Result fields: `schema=2`, `requestId`, `operation=NATIVE_CLICK`, `status`, `targetPackage`, `selectorType`, `coordinateTapUsed=false`, `destinationVerified=false`, `reason`.
- MCP **must** independently validate destination, not blindly trust performAction.
- At-most-once request journal: journal write precedes ACTION_CLICK; never retry uncertain transport or helper results.
- An active v1.2-compatible `SessionLease` heartbeat and connected `ScreenAccessService` are required.
- The target package must be explicitly allowed in the app's **Native actions** settings; `dev.androidroute.benchmark` is the only default entry. Avoid authorizing banking/payment or other sensitive apps.
- `target_package` must match the active accessibility root.
- No coordinates, gestures, global actions, arbitrary commands, or shell path arguments are accepted.
- Calls must originate from user-authorized MCP sessions. Treat STOP as cancellation; confirm active state before each new action.

Example fixed benchmark click (only after beginning an MCP automation session and verifying unique Benchmark Home):

```bash
adb shell am broadcast \
  -n com.codexedge.glow/.NativeActionReceiver \
  -a com.codexedge.glow.NATIVE_CLICK \
  --es request_id mcp-test-123456789 \
  --es target_package dev.androidroute.benchmark \
  --es selector_type text \
  --es selector_value 'Open Guide'
adb shell run-as com.codexedge.glow cat files/native-action-result.json
```

## Migration

1. Install/debug-upgrade Codex Companion v2 while preserving installed Native Click Benchmark Helper for fallback. GitHub Actions debug builds may use different signing certificates; if Android rejects an update, **do not uninstall** without considering settings loss.
2. Confirm accessibility service `com.codexedge.glow/.ScreenAccessService` remains enabled/bound. Confirm overlay and notification permissions.
3. On the MCP side, add an **opt-in** v2 action path; keep the previous helper's v0.1 fixed benchmark path for rollback.
4. Verify UI, state, and notification before one controlled v2 benchmark click.
5. Do not disable old helper until v2 is confirmed end-to-end and the MCP rollback path works.

This version intentionally does not self-certify clicks or provide automated sensitive-action approvals; those must be enforced by the orchestrating MCP and user confirmation where appropriate.
