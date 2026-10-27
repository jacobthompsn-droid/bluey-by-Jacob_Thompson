"""Newline-delimited JSON over TCP, with the same handshake as Shared/GooglyLink.swift.

The laptop is the server (the Mac's role): it sends a challenge, checks the phone's proof,
then everything is sealed with SecureChannel. Unauthenticated peers are dropped after 8 s.
`PhoneClient` is the phone's side, used by the tests and `python -m bluey_laptop.fake_phone`.
"""
import asyncio
import base64
import json
import math
import os
import time

from .protocol import DEFAULT_PORT, MAX_LINE, packet
from .secure import ChannelError, SecureChannel, proof, verifies

AUTH_TIMEOUT = 8.0
# A phone that stops reading gets dropped instead of growing memory forever.
MAX_WRITE_BUFFER = 1024 * 1024


def _json_line(fields):
    return json.dumps(fields, separators=(",", ":")).encode() + b"\n"


class _Phone:
    def __init__(self, writer, channel):
        self.writer = writer
        self.channel = channel
        self.name = None

    def send(self, fields):
        if self.writer.is_closing():
            return
        if self.writer.transport.get_write_buffer_size() > MAX_WRITE_BUFFER:
            self.writer.close()
            return
        self.writer.write(self.channel.seal(fields) + b"\n")


class PhoneServer:
    """Runs on an asyncio loop. Call every method from that loop's thread."""

    def __init__(self, record, name, host="127.0.0.1", port=DEFAULT_PORT,
                 on_phones_changed=None, on_packet=None, auth_timeout=AUTH_TIMEOUT):
        self.record = record
        self.name = name
        self.host = host
        self.port = port
        self.on_phones_changed = on_phones_changed or (lambda names: None)
        self.on_packet = on_packet or (lambda fields: None)
        self.auth_timeout = auth_timeout
        self._phones = set()
        self._server = None
        self._last_face = None
        self._last_face_at = -math.inf

    @property
    def phone_names(self):
        return [phone.name or "Phone" for phone in self._phones]

    async def start(self):
        self._server = await asyncio.start_server(self._handle, self.host, self.port, limit=MAX_LINE)
        # Port 0 means "pick one" (tests); report the real one.
        self.port = self._server.sockets[0].getsockname()[1]

    async def stop(self):
        if self._server:
            self._server.close()
        for phone in list(self._phones):
            phone.writer.close()
        if self._server:
            await self._server.wait_closed()

    def broadcast(self, fields):
        for phone in list(self._phones):
            phone.send(fields)

    def send_face(self, face, now=None):
        """Sends the face to every phone, skipping changes too small to see, but still
        resending at least once a second so the phone knows the laptop is driving."""
        now = time.monotonic() if now is None else now
        last = self._last_face
        if last and now - self._last_face_at < 1 and last["mood"] == face["mood"] \
                and abs(last["gazeX"] - face["gazeX"]) < 0.004 \
                and abs(last["gazeY"] - face["gazeY"]) < 0.004 \
                and abs(last["talk"] - face["talk"]) < 0.02:
            return False
        self._last_face = dict(face)
        self._last_face_at = now
        self.broadcast(packet(face=face))
        return True

    async def _handle(self, reader, writer):
        phone = None
        try:
            secret = self.record.secret
            challenge = os.urandom(32)
            writer.write(_json_line({"challenge": base64.b64encode(challenge).decode()}))
            await writer.drain()
            line = await asyncio.wait_for(reader.readline(), self.auth_timeout)
            fields = json.loads(line)
            candidate = base64.b64decode(fields["proof"], validate=True)
            if not verifies(candidate, secret, challenge):
                return
            phone = _Phone(writer, SecureChannel(secret, challenge, server=True))
            phone.send(packet(command="paired"))
            self._phones.add(phone)
            phone.send(packet(hello=self.name))
            if self._last_face:
                phone.send(packet(face=self._last_face))
            self.on_phones_changed(self.phone_names)
            while line := await reader.readline():
                fields = phone.channel.open(line)
                if isinstance(fields.get("hello"), str):
                    phone.name = fields["hello"]
                    self.on_phones_changed(self.phone_names)
                self.on_packet(fields)
        except (asyncio.TimeoutError, ValueError, KeyError, TypeError, ChannelError,
                ConnectionError, asyncio.LimitOverrunError, asyncio.IncompleteReadError):
            pass  # bad or silent peer: drop it, like the Swift side does
        finally:
            if phone in self._phones:
                self._phones.discard(phone)
                self.on_phones_changed(self.phone_names)
            writer.close()


class PhoneClient:
    """The phone's half of the handshake (a stand-in for the Android app)."""

    def __init__(self, reader, writer, channel):
        self.reader = reader
        self.writer = writer
        self.channel = channel

    @staticmethod
    async def connect(record, host="127.0.0.1", port=None, name="Test phone", timeout=AUTH_TIMEOUT):
        port = port or record.port or DEFAULT_PORT
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=MAX_LINE), timeout)
        try:
            line = await asyncio.wait_for(reader.readline(), timeout)
            if not line:
                raise ConnectionError("The connection closed before the laptop said hello. "
                                      "If you're on USB, is the laptop app running?")
            challenge = base64.b64decode(json.loads(line)["challenge"], validate=True)
            if len(challenge) != 32:
                raise ChannelError("bad challenge")
            writer.write(_json_line({"proof": base64.b64encode(proof(record.secret, challenge)).decode()}))
            await writer.drain()
            channel = SecureChannel(record.secret, challenge, server=False)
            line = await asyncio.wait_for(reader.readline(), timeout)
            if not line:
                raise ConnectionError("The laptop refused the pairing code. Pair again.")
            if channel.open(line).get("command") != "paired":
                raise ChannelError("expected 'paired'")
        except BaseException:
            writer.close()
            raise
        client = PhoneClient(reader, writer, channel)
        client.send(packet(hello=name))
        return client

    def send(self, fields):
        self.writer.write(self.channel.seal(fields) + b"\n")

    async def receive(self, timeout=AUTH_TIMEOUT):
        line = await asyncio.wait_for(self.reader.readline(), timeout)
        if not line:
            raise ConnectionError("closed")
        return self.channel.open(line)

    def close(self):
        self.writer.close()
