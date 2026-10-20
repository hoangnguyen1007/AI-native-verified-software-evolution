"""Bounded loopback transport for authored Hyper-V fixtures, never an admission driver."""
import select
import socket
import threading
import time


class BoundedTunnel:
    def __init__(self, port, limit=8, idle_seconds=15, connector=None):
        if not 1 <= port <= 65535 or limit < 1 or idle_seconds <= 0:
            raise ValueError('INVALID_TUNNEL_POLICY')
        self.port = port
        self.idle_seconds = idle_seconds
        self.connector = connector or socket.create_connection
        self.slots = threading.BoundedSemaphore(limit)
        self.lock = threading.Lock()
        self.closed = False
        self.channels = set()

    def forward(self, channel, origin=None, server=None):
        with self.lock:
            if self.closed or not self.slots.acquire(blocking=False):
                channel.close()
                return
            self.channels.add(channel)
        try:
            threading.Thread(target=self._pump, args=(channel,), daemon=True).start()
        except BaseException:
            with self.lock:
                self.channels.discard(channel)
            channel.close()
            self.slots.release()
            raise

    def _pump(self, channel):
        local = None
        try:
            local = self.connector(('127.0.0.1', self.port), timeout=5)
            local.settimeout(5)
            channel.settimeout(5)
            last = time.monotonic()
            while time.monotonic() - last < self.idle_seconds:
                with self.lock:
                    if self.closed:
                        return
                ready, _, _ = select.select([local, channel], [], [], min(1, self.idle_seconds))
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (channel if source is local else local).sendall(data)
                    last = time.monotonic()
        except (OSError, EOFError, ValueError):
            # Bounded fixture transport failures are observed by the native build/controller.
            pass
        finally:
            if local is not None:
                local.close()
            channel.close()
            with self.lock:
                self.channels.discard(channel)
            self.slots.release()

    def close(self):
        with self.lock:
            self.closed = True
            channels = tuple(self.channels)
        for channel in channels:
            channel.close()
