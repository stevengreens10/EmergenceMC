"""Generates the mod's 16x16 item icons as PNGs (pure Python, no dependencies).

Run: python -I tools/gen_textures.py
"""
import math
import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "emergence", "textures", "item")


def png(path, px):
    h, w = len(px), len(px[0])
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *c) for c in row) for row in px)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


def hexc(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


def shade(c, f):
    return (min(255, int(c[0] * f)), min(255, int(c[1] * f)), min(255, int(c[2] * f)), c[3])


class Canvas:
    def __init__(self):
        self.p = [[(0, 0, 0, 0) for _ in range(16)] for _ in range(16)]

    def set(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            self.p[y][x] = c

    def rect(self, x0, y0, x1, y1, c, border=None):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                edge = x in (x0, x1) or y in (y0, y1)
                self.set(x, y, border if (border and edge) else c)

    def line(self, x0, y0, x1, y1, c, w=1):
        n = max(abs(x1 - x0), abs(y1 - y0)) * 2 + 1
        for i in range(n + 1):
            t = i / n
            x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            for dx in range(w):
                for dy in range(w):
                    self.set(int(round(x)) + dx, int(round(y)) + dy, c)

    def disk(self, cx, cy, r, c, r_in=0.0, c_in=None):
        for y in range(16):
            for x in range(16):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if d <= r:
                    self.set(x, y, c_in if (c_in and d < r_in) else (c if d >= r_in else (0, 0, 0, 0)))


STEEL, DARK, COPPER = hexc(0x8A9096), hexc(0x3C4044), hexc(0xC07A3A)


def icon_block():
    c = Canvas()
    c.rect(2, 2, 13, 13, hexc(0x9AA0A6), hexc(0x5C6268))
    c.rect(3, 3, 12, 4, hexc(0xB8BEC4))
    return c


def icon_shaft():
    c = Canvas()
    c.line(2, 13, 13, 2, STEEL, 2)
    c.line(3, 13, 13, 3, shade(STEEL, 0.6), 1)
    return c


def icon_bearing():
    c = Canvas()
    c.disk(8, 8, 7, hexc(0x9AA0A6), 4, None)
    c.disk(8, 8, 4, hexc(0x60666C), 2.2, None)
    for k in range(8):
        a = k * math.pi / 4
        c.set(int(8 + 5.5 * math.cos(a)), int(8 + 5.5 * math.sin(a)), hexc(0xE0E4E8))
    return c


def icon_gear():
    c = Canvas()
    for k in range(10):
        a = k * 2 * math.pi / 10
        for r in (6.0, 6.8, 7.4):
            c.set(int(8 + r * math.cos(a)), int(8 + r * math.sin(a)), hexc(0x7C8288))
    c.disk(8, 8, 6, hexc(0x9AA0A6), 2, None)
    c.disk(8, 8, 2, DARK)
    return c


def icon_crank():
    c = Canvas()
    c.disk(4, 11, 2.5, STEEL)
    c.line(4, 11, 12, 4, STEEL, 2)
    c.rect(11, 1, 13, 5, hexc(0x6B4423), hexc(0x4A2E17))
    return c


def icon_magnet():
    c = Canvas()
    c.rect(3, 2, 12, 7, hexc(0xC0392B), hexc(0x7B241C))
    c.rect(3, 8, 12, 13, hexc(0x2E5FA8), hexc(0x1B3A66))
    c.set(7, 4, hexc(0xFFFFFF)); c.set(8, 4, hexc(0xFFFFFF)); c.set(7, 5, hexc(0xFFFFFF))
    c.set(8, 10, hexc(0xFFFFFF)); c.set(7, 11, hexc(0xFFFFFF))
    return c


def icon_coil():
    c = Canvas()
    c.rect(4, 1, 11, 14, hexc(0x3A2A1A))
    for y in range(2, 14):
        col = COPPER if y % 2 == 0 else shade(COPPER, 0.7)
        c.rect(3, y, 12, y, col)
    c.line(3, 2, 0, 0, COPPER)
    c.line(12, 13, 15, 15, COPPER)
    return c


def icon_wire(col=COPPER):
    c = Canvas()
    for x in range(16):
        y = int(8 + 3 * math.sin(x / 15 * 2 * math.pi))
        c.set(x, y, col)
        c.set(x, y + 1, shade(col, 0.7))
    return c


def icon_insulated_wire():
    c = Canvas()
    for x in range(2, 14):
        y = int(8 + 3 * math.sin(x / 15 * 2 * math.pi))
        for d in (-1, 0, 1):
            c.set(x, y + d, hexc(0x2F6FB0) if d < 1 else hexc(0x1D4A7A))
    for x in (0, 1, 14, 15):
        y = int(8 + 3 * math.sin(x / 15 * 2 * math.pi))
        c.set(x, y, COPPER)
    return c


def icon_resistor():
    c = Canvas()
    c.line(0, 8, 15, 8, hexc(0xB0B0B0))
    c.rect(4, 6, 11, 10, hexc(0xD8C49A), hexc(0xA8946A))
    for x, col in ((5, 0x8B4513), (7, 0x000000), (9, 0xD4AF37)):
        c.rect(x, 6, x, 10, hexc(col))
    return c


def icon_capacitor():
    c = Canvas()
    c.line(6, 12, 6, 15, hexc(0xB0B0B0))
    c.line(9, 12, 9, 15, hexc(0xB0B0B0))
    c.rect(4, 2, 11, 12, hexc(0x2B5DA8), hexc(0x1B3A66))
    c.rect(10, 2, 11, 12, hexc(0x9DB7E0))
    return c


def icon_diode():
    c = Canvas()
    c.line(0, 8, 15, 8, hexc(0xB0B0B0))
    c.rect(4, 6, 11, 10, hexc(0x1E1E1E), hexc(0x0A0A0A))
    c.rect(9, 6, 10, 10, hexc(0xD0D0D0))
    return c


def icon_switch():
    c = Canvas()
    c.rect(2, 10, 13, 13, hexc(0x5A3A22), hexc(0x3A2614))
    c.line(5, 10, 10, 3, hexc(0xC8A951), 2)
    c.disk(10.5, 3.5, 1.6, hexc(0xA33F3F))
    return c


def icon_brush():
    c = Canvas()
    c.rect(5, 1, 10, 9, hexc(0x2D2D30), hexc(0x151517))
    c.rect(6, 10, 9, 14, hexc(0x9AA0A6), hexc(0x5C6268))
    c.line(7, 10, 7, 14, COPPER)
    return c


def icon_link():
    c = Canvas()
    c.line(3, 12, 12, 3, STEEL, 3)
    c.disk(3.5, 12.5, 2.4, hexc(0xB8BEC4), 1.0, DARK)
    c.disk(12.5, 3.5, 2.4, hexc(0xB8BEC4), 1.0, DARK)
    return c


def icon_battery():
    c = Canvas()
    c.rect(2, 5, 13, 13, hexc(0x2A2C2E), hexc(0x101112))
    for x in (4, 7, 10):
        c.rect(x, 4, x + 1, 4, hexc(0x4A4D50))
    c.rect(3, 2, 4, 4, hexc(0xC03028))
    c.rect(11, 2, 12, 4, hexc(0x505050))
    c.rect(5, 8, 10, 9, hexc(0xE0C030))
    return c


def icon_multimeter():
    c = Canvas()
    c.rect(3, 1, 12, 14, hexc(0xE8B92E), hexc(0x9C7A15))
    c.rect(4, 2, 11, 6, hexc(0x9DB59A), hexc(0x4A5A48))
    for x in (5, 7, 9):
        c.rect(x, 3, x, 5, hexc(0x203020))
    c.disk(7.5, 10.5, 2.4, hexc(0x303030))
    c.set(7, 9, hexc(0xFFFFFF))
    return c


ICONS = {
    "block": icon_block, "shaft": icon_shaft, "bearing": icon_bearing, "gear": icon_gear, "crank": icon_crank,
    "magnet": icon_magnet, "coil": icon_coil, "wire": icon_wire, "insulated_wire": icon_insulated_wire,
    "resistor": icon_resistor, "capacitor": icon_capacitor, "diode": icon_diode, "switch": icon_switch,
    "brush": icon_brush, "multimeter": icon_multimeter, "link": icon_link, "battery": icon_battery,
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, fn in ICONS.items():
        png(os.path.join(OUT, name + ".png"), fn().p)
    print("wrote", len(ICONS), "icons to", os.path.normpath(OUT))
