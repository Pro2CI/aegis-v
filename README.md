# AEGIS-V

Android application scaffold for the supplied AEGIS-V Kotlin/Jetpack Compose source.

## Build

GitHub Actions builds a debug APK on pushes to `main` and pull requests. Download it from the workflow run's **Artifacts** section.

For a local build, use JDK 17 and Gradle 8.9:

```bash
gradle :app:assembleDebug
```

## Important VPN status

The supplied source creates an Android `VpnService` TUN interface, but its Packet-Storm fallback only opened a local TCP listening socket; it did not forward packets. That false fallback has now been removed. The service fails closed and tears down the TUN interface unless a real engine start is confirmed. This prevents the app from reporting a false VPN connection or silently black-holing device traffic.

**Runtime VPN is still not implemented.** The reflective sing-box call is not a complete Android libbox integration: the required `PlatformInterface` adapter, TUN file-descriptor handoff, lifecycle callbacks, and config validation are still missing. Do not rely on this build for traffic protection until that native integration is completed and tested on a device with a valid server profile.

The public subscription URLs in the source are third-party sources and their availability, safety, and node ownership are not guaranteed.
