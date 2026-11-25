# Third-party notices

This repository contains the Android client source and its Gradle wrapper. It does not redistribute the sing-box executable or a prebuilt APK. Building the Android package fetches the dependencies below, and the resulting APK embeds sing-box and the WireGuard tunnel library. Each component keeps its own license.

| Component | Version | License and upstream |
|---|---|---|
| sing-box | 1.14.4 | GPL-3.0-or-later. [Upstream notice and pinned source](https://github.com/SagerNet/sing-box/tree/v1.14.4#license), [license text](https://github.com/SagerNet/sing-box/blob/v1.14.4/LICENSE), [original release and source archives](https://github.com/SagerNet/sing-box/releases/tag/v1.14.4). The APK builds the ARM64 executable from verified upstream source plus the [GPL-3.0-or-later TUN handoff changes](native/direct-tun/) and executes it as a separate process. |
| WireGuard Android `tunnel` | 1.0.20260102 | Apache-2.0 per the [artifact metadata](https://repo.maven.apache.org/maven2/com/wireguard/android/tunnel/1.0.20260102/tunnel-1.0.20260102.pom). [Upstream Android source](https://git.zx2c4.com/wireguard-android/), [source artifact](https://repo.maven.apache.org/maven2/com/wireguard/android/tunnel/1.0.20260102/tunnel-1.0.20260102-sources.jar). |
| Gradle Wrapper | 8.2.1 | Apache-2.0. [Pinned upstream source](https://github.com/gradle/gradle/tree/v8.2.1), [license text](https://github.com/gradle/gradle/blob/v8.2.1/LICENSE), [local license and notices](licenses/Gradle-8.2.1-LICENSE.txt). |


This list highlights the bundled executable and build tooling; it is not a complete inventory of all direct or transitive Android dependencies. If distributing an APK, review its complete dependency inventory, retain copyright and license notices, and satisfy all applicable source distribution requirements, including [GPLv3 section 6](https://www.gnu.org/licenses/gpl-3.0.html#section6) for the included sing-box executable. This file does not prescribe a license for the ouVPN client application source.
