"""Draws the engineer's goggles' textures: the item icon (16 x 16) and the worn helmet layer (64 x 32)."""
from PIL import Image

BRASS, BRASS_DARK, BRASS_LIGHT = (184, 146, 74, 255), (122, 92, 40, 255), (232, 200, 120, 255)
STRAP, STRAP_DARK = (92, 58, 34, 255), (64, 38, 22, 255)
GLASS, GLASS_DARK, GLINT = (96, 196, 210, 255), (40, 120, 140, 255), (220, 250, 255, 255)

# the item: two brass-rimmed lenses on a leather strap
icon = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
px = icon.load()
for x in range(16):
    for y in (7, 8):
        px[x, y] = STRAP if y == 7 else STRAP_DARK
for cx in (4, 11):
    for x in range(cx - 3, cx + 4):
        for y in range(4, 12):
            dx, dy = x - cx, y - 7.5
            r = (dx * dx + dy * dy) ** 0.5
            if 0 <= x < 16 and r <= 3.6:
                if r > 2.4:
                    px[x, y] = BRASS_LIGHT if dy < 0 else (BRASS if dy < 2 else BRASS_DARK)
                else:
                    px[x, y] = GLASS if dy < 1 else GLASS_DARK
    px[cx - 1, 6] = GLINT
    px[cx, 6] = GLINT
# the bridge between the lenses
px[7, 7] = BRASS
px[8, 7] = BRASS
icon.save('src/main/resources/assets/emergence/textures/item/engineers_goggles.png')

# the worn layer: the strap round the head at eye level, the lenses on the front face
layer = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
lp = layer.load()
for x in range(0, 32):          # right, front, left and back faces of the head (rows 8..16)
    lp[x, 11] = STRAP
    lp[x, 12] = STRAP_DARK
for x0 in (9, 13):              # the lenses: 3 x 3 glass in a brass rim, on the front face (8..16, 8..16)
    for x in range(x0 - 1, x0 + 3):
        for y in range(10, 14):
            rim = x in (x0 - 1, x0 + 2) or y in (10, 13)
            lp[x, y] = (BRASS_LIGHT if y == 10 else BRASS_DARK if y == 13 else BRASS) if rim else GLASS
    lp[x0, 11] = GLINT
layer.save('src/main/resources/assets/emergence/textures/models/armor/engineers_goggles_layer_1.png')
print('ok')
