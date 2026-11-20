# Codex Edge Glow
A touch-through animated Android screen border. Android 8+, Kotlin, no third-party runtime dependencies.

## Build
Upload repository to GitHub, run Actions > Build Android APK > Run workflow. Download `codex-edge-glow-debug-apk` artifact, extract APK and install. Grant Display over other apps in the app.

## ADB controls
```
adb shell am broadcast -a com.codexedge.glow.START -n com.codexedge.glow/.GlowReceiver
adb shell am broadcast -a com.codexedge.glow.PING -n com.codexedge.glow/.GlowReceiver
adb shell am broadcast -a com.codexedge.glow.STOP -n com.codexedge.glow/.GlowReceiver
```
Send PING every 30 seconds during an automation session. Glow automatically stops 2 minutes after last START/PING, or immediately on STOP. Android may kill background services under power management. If no glow appears, launch app and check overlay permission. Protected screens may suppress overlays.

## Safety
The broadcast receiver is exported so ADB can reach it. It only accepts three actions and does not provide arbitrary code execution or data access. This is a visual indicator, not a security boundary.
