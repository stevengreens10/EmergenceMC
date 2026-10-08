"""Generates the procedural 32x32 part textures (textures/block/part/*.png) used by the part models.

Run inside Blender (uses bpy only to write PNGs; the patterns are numpy):
    exec(open(r"D:/Repos/Emergence/tools/blender/build_textures.py").read())
Neutral metal/plastic textures are greyscale-ish: the renderer tints them with the part material's colour.
"""
import bpy, numpy as np, os
OUT = r"D:\Repos\Emergence\src\main\resources\assets\emergence\textures\block\part"
os.makedirs(OUT, exist_ok=True)
N = 32
rng = np.random.default_rng(7)

def noise(scale=1.0, octaves=3, seed=0):
    r = np.random.default_rng(seed)
    acc = np.zeros((N, N)); amp = 1.0; tot = 0
    for o in range(octaves):
        n = 2 ** (o + 2)
        g = r.random((n + 1, n + 1))
        xs = np.linspace(0, n, N, endpoint=False)
        i = xs.astype(int); f = xs - i; f = f * f * (3 - 2 * f)
        a = g[np.ix_(i, i)]; b = g[np.ix_(i, i + 1)]; c = g[np.ix_(i + 1, i)]; d = g[np.ix_(i + 1, i + 1)]
        fx = f[None, :]; fy = f[:, None]
        v = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        acc += v * amp; tot += amp; amp *= 0.5
    return acc / tot

def save(name, rgb, alpha=None):
    rgb = np.clip(rgb, 0, 1)
    a = np.ones((N, N)) if alpha is None else np.clip(alpha, 0, 1)
    px = np.dstack([rgb, a])[::-1]  # Blender images are bottom-up
    img = bpy.data.images.get(name) or bpy.data.images.new(name, N, N, alpha=True)
    img.scale(N, N)
    img.pixels = px.astype(np.float32).ravel()
    img.filepath_raw = os.path.join(OUT, name + ".png")
    img.file_format = 'PNG'
    img.save()

def col(c): return np.array(c, float)[None, None, :]
yy, xx = np.mgrid[0:N, 0:N]

# brushed metal (neutral grey, tinted per material at runtime)
streak = np.repeat(np.random.default_rng(1).random((N, 1)), N, axis=1)
streak = 0.6 * streak + 0.4 * np.roll(np.repeat(np.random.default_rng(2).random((N, 1)), N, axis=1), 1, 0)
base = 0.78 + 0.10 * (streak - 0.5) + 0.05 * (noise(seed=3) - 0.5)
edge = np.minimum.reduce([xx, yy, N - 1 - xx, N - 1 - yy])
bevel = np.where(edge == 0, 0.82, np.where(edge == 1, 1.08, 1.0))
save("metal_brushed", np.dstack([base * bevel] * 3))
# cast / rough metal
rough = 0.70 + 0.16 * (noise(octaves=4, seed=4) - 0.5) + 0.06 * (rng.random((N, N)) - 0.5)
save("metal_cast", np.dstack([rough * bevel] * 3))
# plain smooth (for small parts)
save("metal_smooth", np.dstack([(0.85 + 0.04 * (noise(seed=5) - 0.5)) * np.ones((N, N))] * 3))
# copper winding: horizontal enamelled turns with highlights
turn = (yy % 3)
w = np.where(turn == 0, 0.55, np.where(turn == 1, 1.0, 0.8))
w = w * (0.92 + 0.08 * noise(seed=6))
copper = col([0.80, 0.42, 0.18]) * w[..., None] + col([0.25, 0.12, 0.05]) * (turn == 0)[..., None] * 0.2
save("copper_winding", copper)
# phenolic bobbin flange
save("bobbin", col([0.22, 0.14, 0.09]) * (0.9 + 0.15 * noise(seed=7))[..., None])
# nickel-plated NdFeB with painted pole bands
nickel = (0.80 + 0.06 * (noise(seed=8) - 0.5)) * bevel
save("magnet_body", np.dstack([nickel, nickel, nickel * 1.02]))
pole = 0.9 + 0.1 * noise(seed=9)
save("magnet_north", col([0.78, 0.12, 0.10]) * pole[..., None] * bevel[..., None])
save("magnet_south", col([0.12, 0.28, 0.72]) * pole[..., None] * bevel[..., None])
# graphite brush
g = 0.16 + 0.08 * noise(octaves=4, seed=10) + 0.04 * rng.random((N, N))
save("graphite", np.dstack([g, g, g * 1.05]))
# brass
br = (0.85 + 0.08 * (streak - 0.5)) * bevel
save("brass", col([0.83, 0.66, 0.30]) * br[..., None])
# resistor ceramic body (beige, slightly mottled) and a white band (tinted per colour code)
save("resistor_body", col([0.84, 0.74, 0.55]) * (0.92 + 0.1 * noise(seed=11))[..., None])
save("band", np.ones((N, N, 3)) * (0.95 + 0.05 * noise(seed=12))[..., None])
# capacitor sleeve: PVC with a light polarity stripe on one side
sleeve = col([0.13, 0.27, 0.55]) * (0.9 + 0.1 * noise(seed=13))[..., None]
stripe = (xx > 22) & (xx < 28)
sleeve[stripe] = [0.78, 0.82, 0.88]
save("capacitor_sleeve", sleeve)
cap = (0.80 + 0.05 * noise(seed=14)) * np.ones((N, N))
vent = (np.abs(xx - yy) < 1) | (np.abs(xx + yy - N + 1) < 1)
cap[vent] = 0.45
save("capacitor_top", np.dstack([cap] * 3))
# black epoxy (diode, IC bodies)
save("epoxy", np.dstack([0.09 + 0.04 * noise(seed=15)] * 3))
# tinned lead wire / solder
save("tinned", np.dstack([0.78 + 0.08 * noise(seed=16)] * 3))
# bare copper wire (twisted strands)
strands = 0.82 + 0.18 * np.sin((xx + yy) * 1.2)
save("wire_copper", col([0.84, 0.47, 0.22]) * strands[..., None] * (0.95 + 0.05 * noise(seed=17))[..., None])
# PVC insulation (neutral, tinted)
save("pvc", np.dstack([0.88 + 0.06 * noise(seed=18)] * 3))
# glass (light, with a highlight streak); alpha used by translucent rendering
gl = np.dstack([np.full((N, N), 0.86), np.full((N, N), 0.95), np.full((N, N), 0.98)])
hi = (np.abs(xx - 8 - yy * 0.25) < 1.5)
gl[hi] = [1, 1, 1]
alpha = np.where(hi, 0.75, 0.28); alpha[edge == 0] = 0.7
save("glass", gl, alpha)
# liquid (neutral, tinted by solution colour), with gentle caustic mottling
liq = 0.85 + 0.15 * noise(octaves=3, seed=19)
save("liquid", np.dstack([liq] * 3), np.full((N, N), 0.82))
# oak planks
plank = (yy // 8)
grain = 0.85 + 0.12 * np.sin(xx * 0.9 + noise(seed=20) * 6) + 0.05 * rng.random((N, N))
oak = col([0.62, 0.47, 0.29]) * grain[..., None] * (0.92 + 0.08 * (plank % 2))[..., None]
oak[(yy % 8) == 0] *= 0.6
save("oak", oak)
# granite
gr = 0.55 + 0.2 * noise(octaves=4, seed=21)
spk = rng.random((N, N))
granite = np.dstack([gr, gr * 0.93, gr * 0.9])
granite[spk > 0.93] = [0.15, 0.13, 0.13]; granite[spk < 0.05] = [0.85, 0.80, 0.78]
save("granite", granite)
# ceramic / alumina
save("ceramic", np.dstack([0.93 + 0.04 * noise(seed=22)] * 3) * bevel[..., None])
# moulded plastic (neutral, tinted)
save("plastic", np.dstack([0.88 + 0.05 * noise(seed=23)] * 3) * bevel[..., None])
# rubber
save("rubber", np.dstack([0.12 + 0.05 * noise(octaves=4, seed=24)] * 3))
# carbon fibre weave
weave = np.where(((xx // 4) + (yy // 4)) % 2 == 0, 0.2 + 0.1 * np.sin(yy * 1.6), 0.2 + 0.1 * np.sin(xx * 1.6))
save("carbon_fibre", np.dstack([weave, weave, weave * 1.1]))
# silicon wafer (dark blue-grey with a die grid)
si = 0.30 + 0.05 * noise(seed=25)
wafer = np.dstack([si * 0.8, si * 0.85, si * 1.2]); wafer[(xx % 8 == 0) | (yy % 8 == 0)] *= 0.6
save("silicon", wafer)
# chrome bearing balls / races
ch = 0.6 + 0.4 * np.clip(1 - ((xx - 10) ** 2 + (yy - 10) ** 2) / 200.0, 0, 1)
save("chrome", np.dstack([ch, ch, ch * 1.03]))
# hazard/label plate for instruments
save("dark_paint", np.dstack([0.18 + 0.03 * noise(seed=26)] * 3) * bevel[..., None])
print(sorted(os.listdir(OUT)))
