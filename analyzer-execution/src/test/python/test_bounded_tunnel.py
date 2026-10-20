import socket
import threading
import time
import unittest
from bounded_tunnel import BoundedTunnel


class TunnelTest(unittest.TestCase):
    def test_bidirectional_bytes_and_peer_disconnect(self):
        local, local_peer = socket.socketpair()
        channel, peer = socket.socketpair()
        tunnel = BoundedTunnel(1234, connector=lambda *a, **k: local)
        try:
            peer.settimeout(1)
            local_peer.settimeout(1)
            tunnel.forward(channel)
            peer.sendall(b'guest request')
            self.assertEqual(b'guest request',local_peer.recv(32))
            local_peer.sendall(b'controller response')
            self.assertEqual(b'controller response',peer.recv(32))
            local_peer.close()
            self.assertEqual(b'',peer.recv(1))
        finally:
            tunnel.close()
            peer.close()
            local_peer.close()

    def test_overload_closes_without_starting_more_connections(self):
        entered = threading.Event()
        release = threading.Event()
        def connector(*args, **kwargs):
            entered.set()
            release.wait(2)
            raise OSError('fixture unavailable')
        tunnel = BoundedTunnel(1234, limit=1, connector=connector)
        first, peer = socket.socketpair()
        second, other = socket.socketpair()
        try:
            tunnel.forward(first)
            self.assertTrue(entered.wait(1))
            tunnel.forward(second)
            self.assertEqual(-1, second.fileno())
            tunnel.close()
            self.assertEqual(-1, first.fileno())
        finally:
            release.set()
            peer.close()
            other.close()
            tunnel.close()

    def test_idle_connection_is_reaped_and_slot_reusable(self):
        local, local_peer = socket.socketpair()
        tunnel = BoundedTunnel(1234, limit=1, idle_seconds=0.05, connector=lambda *a, **k: local)
        channel, peer = socket.socketpair()
        try:
            tunnel.forward(channel)
            deadline = time.monotonic() + 2
            while channel.fileno() != -1 and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual(-1, channel.fileno())
            self.assertTrue(tunnel.slots.acquire(timeout=1))
            tunnel.slots.release()
            tunnel.close()
            next_channel, next_peer = socket.socketpair()
            try:
                tunnel.forward(next_channel)
                self.assertEqual(-1, next_channel.fileno())
            finally:
                next_peer.close()
        finally:
            tunnel.close()
            peer.close()
            local_peer.close()


if __name__ == '__main__':
    unittest.main()
