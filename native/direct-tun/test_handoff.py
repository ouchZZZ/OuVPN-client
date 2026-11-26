#!/usr/bin/env python3
"""Run the real engine against a simulated TUN FD, without root or network setup."""
import array
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest


ENGINE = str(Path(sys.argv.pop(1)).resolve())


def udp_packet(host, port, payload):
    src = socket.inet_aton("198.18.0.1")
    dst = socket.inet_aton(host)
    udp = struct.pack("!4H", 43210, port, 8 + len(payload), 0) + payload
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(udp), 1, 0, 64, 17, 0, src, dst)
    total = sum(struct.unpack("!10H", ip))
    total = (total & 0xffff) + (total >> 16)
    return ip[:10] + struct.pack("!H", (~total) & 0xffff) + ip[12:] + udp


class Handoff(unittest.TestCase):
    def exercise(self, count, *, close_control=False, probe_in_flight=False):
        with (tempfile.TemporaryDirectory(prefix="test-tun-") as root,
              socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as origin,
              socket.socket(socket.AF_INET, socket.SOCK_STREAM) as socks):
            path = str(Path(root, "s"))
            # gVisor drops 127/8 on a non-loopback NIC. Find the local egress
            # address without sending a packet, then keep the fixture on-host.
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
                route.connect(("192.0.2.1", 9))
                host = route.getsockname()[0]
            origin.bind((host, 0))
            origin.settimeout(3)
            cfg = {"log": {"level": "error"},
                   "inbounds": [{"type": "tun", "stack": "gvisor", "address": ["198.18.0.1/30"], "auto_route": False, "mtu": 1280}],
                   "outbounds": [{"type": "direct", "tag": "node"}], "route": {"final": "node"}}
            if probe_in_flight:
                socks.bind(("127.0.0.1", 0))
                socks.listen(1)
                socks.settimeout(3)
                cfg["outbounds"] = [{"type": "socks", "tag": "node", "server": "127.0.0.1",
                                     "server_port": socks.getsockname()[1]}]
            with subprocess.Popen([ENGINE, "run", "-c", "stdin"], stdin=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env=dict(os.environ, OUVPN_TUN_SOCKET=path)) as engine:
                try:
                    assert engine.stdin is not None and engine.stderr is not None
                    engine.stdin.write(json.dumps(cfg).encode())
                    engine.stdin.close()
                    deadline = time.monotonic() + 3
                    while not Path(path).is_socket() and engine.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertTrue(Path(path).is_socket(), "engine did not open the private socket")
                    left, right = socket.socketpair(type=socket.SOCK_DGRAM)
                    with left, right, socket.socket(socket.AF_UNIX) as control:
                        control.settimeout(3)
                        left.settimeout(3)
                        control.connect(path)
                        rights = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [right.fileno()] * count))] if count else []
                        control.sendmsg([b"T"], rights)
                        response = control.recv(1)
                        if count != 1:
                            self.assertEqual(response, b"")
                            self.assertNotEqual(engine.wait(timeout=3), 0)
                            return
                        self.assertEqual(response, b"R")
                        right.close()
                        if probe_in_flight:
                            control.sendall(b"Q")
                            peer, _ = socks.accept()
                            with peer:
                                peer.settimeout(3)
                                self.assertEqual(peer.recv(1), b"\x05")
                                # Withhold the SOCKS greeting response: the health probe
                                # cannot finish on its own before the exit assertion.
                                control.close()
                                self.assertEqual(engine.wait(timeout=2), 0)
                            return
                        if close_control:
                            control.close()
                            self.assertEqual(engine.wait(timeout=2), 0)
                            return
                        payload = b"ouvpn-direct-tun-test"
                        left.send(udp_packet(host, origin.getsockname()[1], payload))
                        received, peer = origin.recvfrom(1024)
                        self.assertEqual(received, payload)
                        origin.sendto(payload, peer)
                        reply = left.recv(2048)
                        self.assertEqual(reply[28:], payload)
                finally:
                    if engine.poll() is None:
                        engine.kill()
                    engine.wait(timeout=3)
                    if sys.exc_info()[0] is not None and engine.stderr is not None:
                        print(engine.stderr.read().decode(errors="replace"), file=sys.stderr)

    def test_real_udp_packet_crosses_descriptors_in_both_directions(self):
        self.exercise(1)

    def test_rejects_missing_descriptor(self):
        self.exercise(0)

    def test_rejects_multiple_descriptors(self):
        self.exercise(2)

    def test_exits_when_control_closes(self):
        self.exercise(1, close_control=True)

    def test_exits_when_control_closes_during_health_probe(self):
        self.exercise(1, probe_in_flight=True)


if __name__ == "__main__":
    unittest.main()
