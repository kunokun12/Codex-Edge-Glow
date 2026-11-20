# Native Click Benchmark Helper

Isolated proof-of-concept Android accessibility service. Does not change Edge Glow, Android MCP, or the Route Benchmark app.

**App ID:** `dev.androidroute.nativeclick`
**Target:** `dev.androidroute.benchmark`
**Only permitted action:** click the unique, enabled, visible `Open Guide` node using `AccessibilityNodeInfo.performAction(ACTION_CLICK)`.

## Build

GitHub Actions workflow: `.github/workflows/native-click-helper.yml`.
It builds a **debug APK** and uploads the artifact `native-click-helper-debug-apk`. It does **not** install or enable anything on the phone.

## Permission boundary

The manifest-exported `ClickReceiver` requires Android's privileged `android.permission.DUMP` from the sender. Ordinary third-party applications do not hold this permission; authorized ADB shell does. The app exposes no arbitrary selector, package, shell, or coordinate action. This prototype trusts the device's ADB shell security boundary; it does not implement additional cryptographic authentication. This must be reviewed before generalizing beyond a controlled benchmark.

Only after the user explicitly installs the debug APK and manually enables its accessibility service, an authorized local ADB shell can submit a fixed request:

```sh
adb shell am broadcast -n dev.androidroute.nativeclick/.ClickReceiver -a dev.androidroute.nativeclick.CLICK_OPEN_GUIDE --es request_id bench_001_unique
adb shell run-as dev.androidroute.nativeclick cat files/last-result.json
```

Use a **fresh request ID per operation**, never reuse one after an uncertain result. `run-as` works because this is a debuggable development APK. The app stores at most 256 request IDs and fails closed when the journal is full.

The service must be manually enabled in Android Accessibility settings, and the Route Benchmark screen must already be in the foreground. Edge Glow may remain installed; test whether the manufacturer allows both accessibility services simultaneously.

## Interpretation

`DISPATCH_ACCEPTED_UNVERIFIED` means Android accepted the node's ACTION_CLICK request, **not** that Guide Screen was reached. Verify Guide Screen independently. `DISPATCH_REJECTED_UNCERTAIN` and `EXECUTION_UNCERTAIN` must **never** be retried automatically. No coordinate fallback exists.

Do not use this proof of concept for other apps, purchases, user accounts, or unattended control. Do not use its development interface for general-purpose automation.
