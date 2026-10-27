import base64
import json

import pytest

from bluey_laptop.secure import ChannelError, PairingRecord, SecureChannel, direction_key, proof, verifies

SECRET = bytes(range(32))
CHALLENGE = bytes(range(32, 64))


def test_qr_round_trip_and_swift_shape():
    record = PairingRecord(service="Galaxy Book", secret=SECRET, port=47474)
    text = record.qr_text
    assert text.startswith("bluey-pair:")
    fields = json.loads(base64.b64decode(text[len("bluey-pair:"):]))
    # The fields Swift's PairingRecord decodes, plus the optional port.
    assert fields == {"version": 1, "service": "Galaxy Book", "secret": base64.b64encode(SECRET).decode(), "port": 47474}
    assert PairingRecord.parse(text) == record


@pytest.mark.parametrize("text", [
    "", "hello", "bluey-pair:", "bluey-pair:!!!",
    "bluey-pair:" + base64.b64encode(b'{"version":2,"service":"x","secret":"' +
                                     base64.b64encode(SECRET) + b'"}').decode(),
    "bluey-pair:" + base64.b64encode(b'{"version":1,"service":"x","secret":"c2hvcnQ="}').decode(),
    "bluey-pair:" + base64.b64encode(b'{"version":1,"service":"","secret":"' +
                                     base64.b64encode(SECRET) + b'"}').decode(),
    "bluey-pair:" + "A" * 3000,
])
def test_parse_rejects_bad_codes(text):
    assert PairingRecord.parse(text) is None


def test_identifier_is_public_fingerprint():
    assert PairingRecord("x", SECRET).identifier == "630dcd2966c4336691125448"


def test_known_vectors():
    # Pinned so the Kotlin port (android/app/src/test) can check the exact same bytes.
    assert proof(SECRET, CHALLENGE).hex() == "9efef3c1769e7faaccb1814ab9271f718b1fac238d1181313c19dd5eb7583447"
    assert direction_key(SECRET, CHALLENGE, "mac-to-phone").hex() == \
        "02ddbdd5dbe21874370bcc91343f6f5fd07482af2a72d762eece136788e296b6"
    assert direction_key(SECRET, CHALLENGE, "phone-to-mac").hex() == \
        "a94673ce0dcc9300b617d16d0d64a131cb4d7bb3f5ceabae9c0aef6de836fc67"
    laptop = SecureChannel(SECRET, CHALLENGE, server=True)
    assert laptop.seal({"command": "paired"}, nonce=bytes(12)) == \
        b"AAAAAAAAAAAAAAAAg2F35d+QFO2LAVvhH3ude+KCKcJZyyvQ1dWaoE7FSXFoSMeVWxX0wmo/EOCdXKAlSs7W1GAPaRhqG5vJ"


def test_proof_checks():
    assert verifies(proof(SECRET, CHALLENGE), SECRET, CHALLENGE)
    assert not verifies(proof(SECRET, CHALLENGE), bytes(32), CHALLENGE)
    assert not verifies(proof(SECRET, CHALLENGE), SECRET, bytes(32))


def test_channel_round_trip_both_directions():
    laptop = SecureChannel(SECRET, CHALLENGE, server=True)
    phone = SecureChannel(SECRET, CHALLENGE, server=False)
    for i in range(3):
        assert phone.open(laptop.seal({"face": {"gazeX": i / 10, "gazeY": -0.5, "mood": "happy", "talk": 0}})) \
            == {"face": {"gazeX": i / 10, "gazeY": -0.5, "mood": "happy", "talk": 0}}
        assert laptop.open(phone.seal({"hello": "Galaxy"})) == {"hello": "Galaxy"}


def test_channel_rejects_replay_tamper_reorder_and_reflection():
    laptop = SecureChannel(SECRET, CHALLENGE, server=True)
    phone = SecureChannel(SECRET, CHALLENGE, server=False)
    first, second = laptop.seal({"command": "a"}), laptop.seal({"command": "b"})
    with pytest.raises(ChannelError):
        phone.open(second)  # out of order
    phone = SecureChannel(SECRET, CHALLENGE, server=False)
    phone.open(first)
    with pytest.raises(ChannelError):
        phone.open(first)  # replay
    tampered = bytearray(base64.b64decode(second))
    tampered[-1] ^= 1
    with pytest.raises(ChannelError):
        phone.open(base64.b64encode(bytes(tampered)))
    with pytest.raises(ChannelError):
        laptop.open(laptop.seal({"command": "x"}))  # own messages don't decrypt (direction keys)
    with pytest.raises(ChannelError):
        phone.open(b"not base64 !!")
