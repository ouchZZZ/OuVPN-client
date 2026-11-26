# Direct Android TUN handoff

The Android app establishes the VPN with `VpnService.Builder`. It keeps its original TUN descriptor and passes a duplicate to the bundled sing-box subprocess with `SCM_RIGHTS`. Both ends check the app UID using Unix peer credentials; the filesystem socket lives in a new directory under the app's private cache. The Unix socket carries only the descriptor and health commands, never application packets. There is no TCP or SOCKS listener in the node-only configuration. The engine uses gVisor on the descriptor, and Android manages the interface addresses and routes.

The engine acknowledges that the TUN stack started, then checks HTTPS through the selected outbound in the same process. This is not an Android DNS/IPv6 or per-app exclusion test. Each switch stops the old engine before handing the descriptor to its replacement, while the app holds the original interface open.

## Build and Linux test

Go 1.26.10 and Python 3 are required. No NDK is needed by this Go build. The build verifies the module checksum and commit of sing-box v1.14.4 and applies the handoff to a temporary copy. The hook and the modifications inserted into sing-box by `build.py` are available under GPL-3.0-or-later; see the [upstream license](https://github.com/SagerNet/sing-box/blob/v1.14.4/LICENSE). The Android APK uses the output of the Gradle `prepareSingBox` task.

To test without root on Linux/amd64, use a scratch output path:

```sh
python3 native/direct-tun/build.py "$TMPDIR/ouvpn-engine" --linux-test
python3 native/direct-tun/test_handoff.py "$TMPDIR/ouvpn-engine" -v
```

The fixture transfers a datagram socket as a simulated TUN and sends a real IPv4 UDP packet in both directions through the engine. It also verifies that the engine rejects zero or multiple descriptors. It does not exercise Android's `LocalSocket`, actual `VpnService` routing, or a real subscription node.
