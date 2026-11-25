#!/usr/bin/env python3
"""Build a pinned sing-box executable with app-private Android TUN FD handoff."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

VERSION = "v1.14.4"
SUM = "h1:KV6J8v1zMUu7BZA93nD9HGMmQnNC46VTuYaKEHkP+gA="
COMMIT = "c005f35300ce76427dc285481ec5695f5b179cd2"
MODULE = "github.com/sagernet/sing-box"
GO_VERSION = "go1.26.10"


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError("Pinned sing-box source differs from expected context")
    return text.replace(before, after, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--go", default="go")
    parser.add_argument("--linux-test", action="store_true", help="Build a Linux/amd64 engine for unprivileged FD handoff tests")
    args = parser.parse_args()
    go = shutil.which(args.go)
    if not go:
        parser.error("Go 1.26.10 was not found")
    env = {**os.environ, "GOTOOLCHAIN": "local", "GOWORK": "off", "GOFLAGS": "", "GOSUMDB": "sum.golang.org"}
    toolchain = subprocess.check_output([go, "version"], env=env, text=True).split()
    if toolchain[2] != GO_VERSION:
        parser.error(f"Expected {GO_VERSION}, found {toolchain[2]}")
    info = json.loads(subprocess.check_output([go, "mod", "download", "-json", f"{MODULE}@{VERSION}"], env=env, text=True))
    if info.get("Sum") != SUM or info.get("Origin", {}).get("Hash") != COMMIT:
        raise ValueError("sing-box source digest or pinned commit did not match")

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="singbox-src-", dir=output.parent) as temp:
        work = Path(temp)
        with zipfile.ZipFile(info["Zip"]) as archive:
            archive.extractall(work)
        root = work / f"{MODULE}@{VERSION}"
        inbound = root / "protocol/tun/inbound.go"
        s = inbound.read_text(encoding="utf-8")
        s = replace_once(s, "type Inbound struct {", "type Inbound struct {\n\tandroidHandoff net.Conn")
        s = replace_once(s, "if C.IsAndroid && t.platformInterface == nil {", 'if C.IsAndroid && t.platformInterface == nil && os.Getenv("OUVPN_TUN_SOCKET") == "" {')
        s = replace_once(s, "\t\tmonitor.Start(\"open interface\")", """\t\tif socket := os.Getenv("OUVPN_TUN_SOCKET"); socket != "" {
			fd, handoff, handoffErr := receiveAndroidTun(socket)
			if handoffErr != nil {
				return handoffErr
			}
			scope.Add(handoff.Close)
			t.androidHandoff = handoff
			tunOptions.FileDescriptor = fd
			// Android has already installed the routes and set the MTU.
			tunOptions.GSO = false
			t.tunOptions.GSO = false
		}
		monitor.Start("open interface")""")
        s = replace_once(s, "\t\tt.routeAddressSet = nil\n\t\tt.routeExcludeAddressSet = nil\n\t}", """\t\tt.routeAddressSet = nil
		t.routeExcludeAddressSet = nil
		if t.androidHandoff != nil {
			if _, err := t.androidHandoff.Write([]byte{'R'}); err != nil {
				return err
			}
			go func() {
				serveAndroidHealth(t.ctx, t.androidHandoff)
				// A detached engine must not keep forwarding on its TUN FD.
				os.Exit(0)
			}()
		}
	}""")
        inbound.write_text(s, encoding="utf-8")
        hook = Path(__file__).with_name("receive_android.go").read_text(encoding="utf-8")
        if args.linux_test:
            hook = replace_once(hook, "//go:build android", "//go:build linux")
        inbound.with_name("ouvpn_receive.go").write_text(hook, encoding="utf-8")
        env.update(GOOS="linux" if args.linux_test else "android", GOARCH="amd64" if args.linux_test else "arm64", CGO_ENABLED="0")
        executable = work / "sing-box"
        subprocess.run([go, "build", "-mod=readonly", "-trimpath", "-buildvcs=false", "-tags",
                        "with_gvisor,with_quic,with_wireguard,with_utls", "-ldflags", f"-s -w -X {MODULE}/constant.Version=1.14.4",
                        "-o", str(executable), "./cmd/sing-box"], cwd=root, env=env, check=True)
        executable.replace(output)


if __name__ == "__main__":
    main()
