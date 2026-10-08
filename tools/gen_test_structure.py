"""Writes the GameTest arena structure (stone floor, open air above) as gzipped NBT.

Run: python -I tools/gen_test_structure.py
"""
import gzip
import os
import struct

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "data", "emergence", "structure")

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def s(name):
    b = name.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def payload(tag, v):
    if tag == TAG_INT:
        return struct.pack(">i", v)
    if tag == TAG_STRING:
        return s(v)
    if tag == TAG_LIST:
        etype, items = v
        return struct.pack(">bi", etype if items else TAG_END, len(items)) + b"".join(payload(etype, i) for i in items)
    if tag == TAG_COMPOUND:
        out = b""
        for k, (t, val) in v.items():
            out += struct.pack(">b", t) + s(k) + payload(t, val)
        return out + struct.pack(">b", TAG_END)
    raise ValueError(tag)


def arena(size=12):
    blocks = []
    for x in range(size):
        for z in range(size):
            blocks.append({"pos": (TAG_LIST, (TAG_INT, [x, 0, z])), "state": (TAG_INT, 0)})
    return {
        "DataVersion": (TAG_INT, 3955),
        "size": (TAG_LIST, (TAG_INT, [size, size, size])),
        "palette": (TAG_LIST, (TAG_COMPOUND, [{"Name": (TAG_STRING, "minecraft:stone")}])),
        "blocks": (TAG_LIST, (TAG_COMPOUND, blocks)),
        "entities": (TAG_LIST, (TAG_COMPOUND, [])),
    }


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    data = struct.pack(">b", TAG_COMPOUND) + s("") + payload(TAG_COMPOUND, arena())
    with gzip.open(os.path.join(OUT, "arena.nbt"), "wb") as f:
        f.write(data)
    print("wrote arena.nbt")
