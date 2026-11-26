# AEGIS-V

Android application scaffold for the supplied AEGIS-V Kotlin/Jetpack Compose source.

## Build

GitHub Actions builds a debug APK on pushes to `main` and pull requests. Download it from the workflow run's **Artifacts** section.

For a local build, use JDK 17 and Gradle 8.9:

```bash
gradle :app:assembleDebug
```

## Important VPN status

The supplied source creates an Android `VpnService` TUN interface, but its Packet-Storm fallback only opens a local TCP listening socket; it does not forward packets. The reflective sing-box call is not a complete Android libbox integration and the source does not include the required platform adapter/configuration. Therefore, a successful APK build must not be interpreted as a functioning VPN tunnel. Do not rely on this build for traffic protection until the native sing-box integration is implemented and tested on a device with a valid server profile.

The public subscription URLs in the source are third-party sources and their availability, safety, and node ownership are not guaranteed.
