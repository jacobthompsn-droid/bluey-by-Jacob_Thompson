"""Pairing and the encrypted channel. A byte-for-byte port of Shared/SecurePairing.swift.

- QR text: "bluey-pair:" + base64(JSON {"version":1,"service":...,"secret":base64(32 bytes)}).
  This app adds an optional "port"; Swift's decoder ignores keys it doesn't know.
- Proof: HMAC-SHA256(key=secret, "bluey-pair-v1" + challenge).
- Keys: HKDF-SHA256(secret, salt=challenge, info="bluey-v1-mac-to-phone" / "bluey-v1-phone-to-mac").
  The laptop plays the Mac's part, so the "mac" wording stays.
- Each line: base64(nonce(12) + ciphertext + tag(16)) of JSON {"sequence": n, "packet": {...}},
  the CryptoKit `AES.GCM.SealedBox.combined` layout. Sequences must arrive in order.
"""
import base64
import hashlib
import hmac
import json
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

PREFIX = "bluey-pair:"


class ChannelError(Exception):
    pass


@dataclass(frozen=True)
class PairingRecord:
    service: str
    secret: bytes
    port: int | None = None
    version: int = 1

    @staticmethod
    def generate(service, port=None):
        return PairingRecord(service=service, secret=os.urandom(32), port=port)

    @property
    def identifier(self):
        """Public fingerprint for discovery (the TXT record `bluey-id`)."""
        return hashlib.sha256(self.secret).digest()[:12].hex()

    def to_json(self):
        fields = {"version": self.version, "service": self.service,
                  "secret": base64.b64encode(self.secret).decode()}
        if self.port is not None:
            fields["port"] = self.port
        return fields

    @property
    def qr_text(self):
        data = json.dumps(self.to_json(), separators=(",", ":")).encode()
        return PREFIX + base64.b64encode(data).decode()

    @staticmethod
    def from_json(fields):
        if not isinstance(fields, dict) or fields.get("version") != 1:
            return None
        service, secret, port = fields.get("service"), fields.get("secret"), fields.get("port")
        if not isinstance(service, str) or not service or not isinstance(secret, str):
            return None
        try:
            key = base64.b64decode(secret, validate=True)
        except ValueError:
            return None
        if len(key) != 32:
            return None
        if port is not None and (not isinstance(port, int) or not 0 < port < 65536):
            return None
        return PairingRecord(service=service, secret=key, port=port)

    @staticmethod
    def parse(text):
        if not isinstance(text, str) or not text.startswith(PREFIX) or len(text) >= 2048:
            return None
        try:
            fields = json.loads(base64.b64decode(text[len(PREFIX):], validate=True))
        except ValueError:
            return None
        return PairingRecord.from_json(fields)


def proof(secret, challenge):
    return hmac.new(secret, b"bluey-pair-v1" + challenge, hashlib.sha256).digest()


def verifies(candidate, secret, challenge):
    return hmac.compare_digest(candidate, proof(secret, challenge))


def direction_key(secret, challenge, direction):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=challenge,
                info=("bluey-v1-" + direction).encode()).derive(secret)


class SecureChannel:
    def __init__(self, secret, challenge, server):
        self._send = AESGCM(direction_key(secret, challenge, "mac-to-phone" if server else "phone-to-mac"))
        self._receive = AESGCM(direction_key(secret, challenge, "phone-to-mac" if server else "mac-to-phone"))
        self._sent = 0
        self._received = 0

    def seal(self, packet, nonce=None):
        """Returns one line (without the newline). `nonce` is only for test vectors."""
        nonce = nonce if nonce is not None else os.urandom(12)
        data = json.dumps({"sequence": self._sent, "packet": packet}, separators=(",", ":")).encode()
        sealed = nonce + self._send.encrypt(nonce, data, None)
        self._sent += 1
        return base64.b64encode(sealed)

    def open(self, line):
        try:
            sealed = base64.b64decode(line.strip(), validate=True)
            if len(sealed) < 12 + 16:
                raise ValueError("too short")
            record = json.loads(self._receive.decrypt(sealed[:12], sealed[12:], None))
        except Exception as error:  # bad base64, failed authentication or bad JSON
            raise ChannelError("invalid record") from error
        if not isinstance(record, dict) or record.get("sequence") != self._received \
                or not isinstance(record.get("packet"), dict):
            raise ChannelError("out of order or malformed record")
        self._received += 1
        return record["packet"]
