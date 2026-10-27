import asyncio
import base64
import json

import pytest

from bluey_laptop.link import PhoneClient, PhoneServer
from bluey_laptop.protocol import face
from bluey_laptop.secure import PairingRecord


def run(coroutine):
    return asyncio.run(asyncio.wait_for(coroutine, 15))


async def started(record, **kwargs):
    server = PhoneServer(record, "Galaxy Book", port=0, **kwargs)
    await server.start()
    return server


def test_pair_hello_and_face_stream():
    async def scenario():
        record = PairingRecord.generate("Galaxy Book")
        names, packets = [], []
        server = await started(record, on_phones_changed=names.append, on_packet=packets.append)
        server.send_face(face(0.25, -0.5), now=0)
        client = await PhoneClient.connect(record, port=server.port, name="Galaxy S")
        assert await client.receive() == {"hello": "Galaxy Book"}
        assert await client.receive() == {"face": face(0.25, -0.5)}
        assert server.send_face(face(0.5, -0.5), now=0.1)
        assert await client.receive() == {"face": face(0.5, -0.5)}
        await asyncio.sleep(0.1)
        assert names[-1] == ["Galaxy S"] and packets == [{"hello": "Galaxy S"}]
        client.close()
        await asyncio.sleep(0.1)
        assert names[-1] == []
        await server.stop()
    run(scenario())


def test_face_updates_skip_tiny_changes_but_resend_every_second():
    async def scenario():
        server = await started(PairingRecord.generate("x"))
        assert server.send_face(face(0, -0.5), now=10)
        assert not server.send_face(face(0.001, -0.5), now=10.5)
        assert server.send_face(face(0.001, -0.5), now=11.1)
        assert server.send_face(face(0.001, -0.5, "happy"), now=11.2)
        await server.stop()
    run(scenario())


def test_wrong_secret_is_refused():
    async def scenario():
        server = await started(PairingRecord.generate("x"))
        with pytest.raises(ConnectionError, match="refused the pairing code"):
            await PhoneClient.connect(PairingRecord.generate("x"), port=server.port, timeout=3)
        assert server.phone_names == []
        await server.stop()
    run(scenario())


def test_silent_and_garbage_peers_are_dropped():
    async def scenario():
        server = await started(PairingRecord.generate("x"), auth_timeout=0.3)
        reader, writer = await asyncio.open_connection("127.0.0.1", server.port)
        assert "challenge" in json.loads(await reader.readline())
        assert await asyncio.wait_for(reader.read(), 3) == b""  # silent: closed after the timeout
        reader, writer = await asyncio.open_connection("127.0.0.1", server.port)
        await reader.readline()
        writer.write(b'{"proof":"' + base64.b64encode(bytes(32)) + b'"}\n')
        assert await asyncio.wait_for(reader.read(), 3) == b""  # bad proof: closed
        reader, writer = await asyncio.open_connection("127.0.0.1", server.port)
        await reader.readline()
        writer.write(b"garbage\n")
        assert await asyncio.wait_for(reader.read(), 3) == b""
        await server.stop()
    run(scenario())


def test_phone_tampering_after_pairing_drops_it():
    async def scenario():
        record = PairingRecord.generate("x")
        names = []
        server = await started(record, on_phones_changed=names.append)
        client = await PhoneClient.connect(record, port=server.port)
        await client.receive()
        client.writer.write(b"AAAA\n")
        await asyncio.sleep(0.2)
        assert names[-1] == []
        await server.stop()
    run(scenario())
