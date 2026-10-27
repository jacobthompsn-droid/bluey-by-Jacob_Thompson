"""Pretends to be the phone: pairs with the running laptop app using the saved pairing code
and prints the face it receives. Checks the laptop side without a phone:

    python -m bluey_laptop.fake_phone
"""
import argparse
import asyncio

from . import storage
from .link import PhoneClient
from .protocol import DEFAULT_PORT


async def run(port, seconds):
    record = storage.load()
    if record is None:
        print("No pairing code yet. Start the laptop app first: python -m bluey_laptop")
        return 1
    client = await PhoneClient.connect(record, port=port, name="Fake phone")
    print("Paired and encrypted. Move your mouse; Ctrl+C to stop.")
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    try:
        while loop.time() < end:
            fields = await client.receive(timeout=5)
            if "hello" in fields:
                print(f"Laptop says hello: {fields['hello']}")
            if "face" in fields:
                face = fields["face"]
                print(f"face: x {face['gazeX']:+.2f}  y {face['gazeY']:+.2f}  {face['mood']}")
    finally:
        client.close()
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--seconds", type=float, default=30)
    args = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(run(args.port, args.seconds)))
    except KeyboardInterrupt:
        pass
    except (OSError, ConnectionError) as error:
        raise SystemExit(f"Couldn't reach the laptop app on port {args.port}: {error}")


if __name__ == "__main__":
    main()
