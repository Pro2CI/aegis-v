# AEGIS-V

Android application scaffold for the supplied AEGIS-V Kotlin/Jetpack Compose source.

## Build

GitHub Actions builds a debug APK on pushes to `main` and pull requests. Download it from the workflow run's **Artifacts** section.

Build toolchain:
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin 2.2.10
- JDK 17
- Android compile SDK 36

The app pins sing-box Android libbox 1.14.4 from JitPack.

## VPN runtime status

The Android service now delegates TUN creation to the sing-box `PlatformInterface.openTun` callback and starts the libbox `CommandServer`. The host bridge is isolated in `SingBoxAndroidBridge.kt`; the Compose UI does not claim a connection merely because a node was selected.

**This is an integration build, not yet a device-verified VPN release.** The bridge currently provides the TUN/protect path and conservative defaults for optional platform callbacks. It still requires CI compilation, runtime validation on a physical Android device, and testing with a valid VLESS/Reality, Trojan, Hysteria2, or Shadowsocks profile before it can be relied on for traffic protection. If libbox startup fails, the service must stop and close the TUN.

The free public subscription sources are third-party sources. Their availability, safety, and node ownership are not guaranteed.

## Licensing

The bundled sing-box libbox dependency is GPL-3.0-or-later. Any distributed build containing it must comply with the corresponding source and license requirements. Review the upstream libbox license before publishing APKs.
