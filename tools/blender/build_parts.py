"""Builds every Emergence part model in Blender, exports OBJ meshes and renders item icons.

Run inside Blender (5.x):  exec(open(r"D:/Repos/Emergence/tools/blender/build_parts.py").read())
Textures come from tools/blender/build_textures.py (run that first on a fresh checkout).

Conventions
-----------
* 1 Blender unit = 1 grid cell (one block at block scale, 1/16 block in miniature mode).
* Each part is centred on the origin with its physical axis along Blender +Z (exported as OBJ +Y).
* Each part is a collection of named objects; the Java renderer may transform named pieces individually
  (crank arm scaled to the crank radius, switch blade rotated by its state, resistor bands coloured by
  value...).
* Material names are "<texture>__<flags>": texture = PNG in textures/block/part/, flags among
  tint (multiplied by the part material's colour), glass (translucent render type),
  yellow/blue (fixed tints).
"""
import bpy, bmesh, math, os
from mathutils import Matrix

ROOT = r"D:\Repos\Emergence\src\main\resources\assets\emergence"
TEX = os.path.join(ROOT, "textures", "block", "part")
MODELS = os.path.join(ROOT, "models", "part")
ICONS = os.path.join(ROOT, "textures", "item")
ICON_SIZE = 64

# Representative tints used only for icon renders (in game the part's material colour is used).
ICON_TINT = {
    "material__tint": (0.43, 0.45, 0.47), "metal_cast__tint": (0.50, 0.52, 0.55),
    "metal_brushed__tint": (0.55, 0.57, 0.60), "metal_smooth__tint": (0.60, 0.62, 0.65),
    "plastic__yellow": (0.95, 0.75, 0.15), "pvc__blue": (0.20, 0.42, 0.72),
    "liquid__tint_glass": (0.18, 0.52, 0.87), "band__tint": (1, 1, 1),
}
BAND_ICON = [(0.45, 0.25, 0.08), (0.05, 0.05, 0.05), (0.75, 0.10, 0.08), (0.83, 0.68, 0.22)]


# ------------------------------------------------------------------ helpers
def reset_scene():
    for o in list(bpy.data.objects): bpy.data.objects.remove(o, do_unlink=True)
    for m in list(bpy.data.meshes): bpy.data.meshes.remove(m)
    for m in list(bpy.data.materials): bpy.data.materials.remove(m)
    for c in list(bpy.data.collections): bpy.data.collections.remove(c)


def mat(name, tint=None):
    key = name if tint is None else name + "#%.2f%.2f%.2f" % tint
    m = bpy.data.materials.get(key)
    if m: return m
    m = bpy.data.materials.new(key)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    tex = name.split("__")[0]
    if tex == "material": tex = "metal_brushed"  # icon stand-in; in game the part material picks it
    p = os.path.join(TEX, tex + ".png")
    out = None
    if os.path.exists(p):
        img = bpy.data.images.load(p, check_existing=True)
        n = nt.nodes.new("ShaderNodeTexImage"); n.image = img; n.interpolation = 'Closest'
        out = n.outputs[0]
        if "glass" in name:
            nt.links.new(n.outputs[1], bsdf.inputs["Alpha"])
            try: m.surface_render_method = 'BLENDED'
            except Exception: pass
    t = tint or ICON_TINT.get(name)
    if out is not None and t is not None:
        mix = nt.nodes.new("ShaderNodeMix"); mix.data_type = 'RGBA'; mix.blend_type = 'MULTIPLY'
        mix.inputs[0].default_value = 1.0
        nt.links.new(out, mix.inputs[6]); mix.inputs[7].default_value = (*t, 1)
        out = mix.outputs[2]
    if out is not None: nt.links.new(out, bsdf.inputs["Base Color"])
    metal = any(k in tex for k in ("metal", "chrome", "brass", "tinned", "magnet", "wire_copper", "capacitor_top"))
    bsdf.inputs["Metallic"].default_value = 0.85 if metal else 0.0
    bsdf.inputs["Roughness"].default_value = 0.32 if metal else 0.6
    m["em_name"] = name
    return m


def collection(part):
    c = bpy.data.collections.get(part)
    if not c:
        c = bpy.data.collections.new(part); bpy.context.scene.collection.children.link(c)
    return c


def finish(obj, part, material, smooth=False, tint=None):
    bpy.context.view_layer.update()
    for c in list(obj.users_collection): c.objects.unlink(obj)
    collection(part).objects.link(obj)
    bpy.context.view_layer.objects.active = obj
    for m in list(obj.modifiers): bpy.ops.object.modifier_apply(modifier=m.name)
    obj.data.materials.clear(); obj.data.materials.append(mat(material, tint))
    me = obj.data
    me.transform(obj.matrix_world); obj.matrix_world = Matrix()
    bm = bmesh.new(); bm.from_mesh(me)
    uv = bm.loops.layers.uv.verify()
    for f in bm.faces:
        n = f.normal; ax = max(range(3), key=lambda i: abs(n[i]))
        for l in f.loops:
            co = l.vert.co
            if ax == 0: u, v = co.y, co.z
            elif ax == 1: u, v = co.x, co.z
            else: u, v = co.x, co.y
            l[uv].uv = (u + 0.5, v + 0.5)
    bm.to_mesh(me); bm.free()
    for p in me.polygons: p.use_smooth = smooth
    return obj


def cyl(r, depth, loc=(0, 0, 0), verts=24, rot=None, name="c"):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=r, depth=depth, location=loc)
    o = bpy.context.active_object; o.name = name
    if rot: o.rotation_euler = rot
    return o


def box(size, loc=(0, 0, 0), name="b", bevel=0.0, rot=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o = bpy.context.active_object; o.name = name; o.scale = size
    if rot: o.rotation_euler = rot
    bpy.ops.object.transform_apply(scale=True, rotation=False, location=False)
    if bevel > 0: bevel_mod(o, bevel)
    return o


def torus(R, r, loc=(0, 0, 0), name="t", rot=None, seg=24):
    bpy.ops.mesh.primitive_torus_add(major_radius=R, minor_radius=r, major_segments=seg, minor_segments=8, location=loc)
    o = bpy.context.active_object; o.name = name
    if rot: o.rotation_euler = rot
    return o


def sphere(r, loc=(0, 0, 0), name="s"):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=r, segments=10, ring_count=6, location=loc)
    o = bpy.context.active_object; o.name = name; return o


def bevel_mod(o, w, seg=2):
    m = o.modifiers.new("bev", "BEVEL"); m.width = w; m.segments = seg; m.limit_method = 'ANGLE'


def boolean(o, cutter, op="DIFFERENCE"):
    bpy.context.view_layer.update()
    bpy.context.view_layer.objects.active = o
    for m in list(o.modifiers): bpy.ops.object.modifier_apply(modifier=m.name)
    m = o.modifiers.new("bool", "BOOLEAN"); m.operation = op; m.object = cutter
    bpy.ops.object.modifier_apply(modifier=m.name)
    bpy.data.objects.remove(cutter, do_unlink=True)


# ------------------------------------------------------------------ parts
def build_all():
    # PLATE / structural block: texture chosen from the part's material at runtime
    finish(box((1, 1, 1), name="plate", bevel=0.035), "plate", "material__tint")

    # MAGNET: nickel-plated body, painted north (+Z) and south caps
    finish(box((0.8, 0.8, 0.8), name="magnet_body", bevel=0.04), "magnet", "magnet_body")
    finish(box((0.74, 0.74, 0.03), loc=(0, 0, 0.406), name="magnet_n", bevel=0.01), "magnet", "magnet_north")
    finish(box((0.74, 0.74, 0.03), loc=(0, 0, -0.406), name="magnet_s", bevel=0.01), "magnet", "magnet_south")

    # BEARING: flange unit, bore along Z
    fl = box((0.96, 0.96, 0.18), name="bearing_flange", bevel=0.03); boolean(fl, cyl(0.33, 0.5, name="cut"))
    finish(fl, "bearing", "metal_cast__tint")
    boss = cyl(0.34, 0.42, verts=32, name="bearing_housing"); boolean(boss, cyl(0.25, 0.6, name="cut")); bevel_mod(boss, 0.03)
    finish(boss, "bearing", "metal_cast__tint", smooth=True)
    finish(torus(0.235, 0.022, name="bearing_outer_race", seg=32), "bearing", "chrome", smooth=True)
    inner = cyl(0.16, 0.16, verts=32, name="bearing_inner_race"); boolean(inner, cyl(0.11, 0.3, name="cut"))
    finish(inner, "bearing", "chrome", smooth=True)
    for k in range(10):
        a = 2 * math.pi * k / 10
        finish(sphere(0.04, loc=(0.2 * math.cos(a), 0.2 * math.sin(a), 0), name="bearing_ball"), "bearing", "chrome", smooth=True)
    for sx in (-1, 1):
        for sy in (-1, 1):
            b = cyl(0.06, 0.08, loc=(0.36 * sx, 0.36 * sy, 0.12), verts=6, name="bearing_bolt"); bevel_mod(b, 0.01)
            finish(b, "bearing", "metal_smooth")

    # COIL: bobbin flanges, enamelled winding, leads brought out sideways (−X bottom, +X top)
    for z in (-0.43, 0.43):
        f = cyl(0.47, 0.06, loc=(0, 0, z), verts=32, name="coil_flange"); boolean(f, cyl(0.17, 0.2, loc=(0, 0, z), name="cut"))
        bevel_mod(f, 0.012); finish(f, "coil", "bobbin")
    core = cyl(0.17, 0.92, verts=24, name="coil_core"); boolean(core, cyl(0.12, 1.0, name="cut")); finish(core, "coil", "bobbin")
    finish(cyl(0.43, 0.8, verts=40, name="coil_winding"), "coil", "copper_winding", smooth=True)
    for sgn, z in ((-1, -0.33), (1, 0.33)):
        finish(cyl(0.025, 0.12, loc=(sgn * 0.48, 0, z), rot=(0, math.pi / 2, 0), verts=8, name="coil_lead"), "coil", "tinned")
        finish(box((0.05, 0.14, 0.1), loc=(sgn * 0.47, 0, z), name="coil_lug", bevel=0.01), "coil", "brass")

    # CRANK: hub (Z axis), unit arm along +X scaled at runtime, grip translated to the arm end
    hub = cyl(0.2, 0.3, verts=24, name="crank_hub"); bevel_mod(hub, 0.03); finish(hub, "crank", "metal_cast__tint", smooth=True)
    # boss reaching down to the shaft end it is keyed onto
    finish(cyl(0.11, 0.42, loc=(0, 0, -0.29), verts=16, name="crank_boss"), "crank", "metal_cast__tint", smooth=True)
    finish(box((1.0, 0.16, 0.1), loc=(0.5, 0, 0.1), name="crank_arm", bevel=0.03), "crank", "metal_cast__tint")
    grip = cyl(0.07, 0.5, loc=(1.0, 0, 0.42), verts=16, name="crank_grip"); bevel_mod(grip, 0.025)
    finish(grip, "crank", "oak", smooth=True)
    finish(sphere(0.09, loc=(1.0, 0, 0.68), name="crank_knob"), "crank", "oak", smooth=True)
    finish(cyl(0.035, 0.22, loc=(1.0, 0, 0.2), verts=12, name="crank_pin"), "crank", "metal_smooth")

    # RESISTOR: axial, four colour-code bands (tinted at runtime from the resistance)
    body = cyl(0.13, 0.5, verts=24, name="resistor_body"); bevel_mod(body, 0.05, 3)
    finish(body, "resistor", "resistor_body", smooth=True)
    for k, z in enumerate((-0.15, -0.07, 0.01, 0.15)):
        finish(cyl(0.137, 0.045, loc=(0, 0, z), verts=24, name="resistor_band%d" % (k + 1)), "resistor", "band__tint",
               smooth=True, tint=BAND_ICON[k] if ICON_MODE else None)
    for z in (-0.37, 0.37):
        finish(cyl(0.022, 0.26, loc=(0, 0, z), verts=8, name="resistor_lead"), "resistor", "tinned")

    # CAPACITOR: axial electrolytic
    can = cyl(0.27, 0.62, verts=32, name="cap_can"); bevel_mod(can, 0.03); finish(can, "capacitor", "capacitor_sleeve", smooth=True)
    finish(cyl(0.25, 0.02, loc=(0, 0, 0.315), verts=32, name="cap_top"), "capacitor", "capacitor_top")
    finish(cyl(0.24, 0.04, loc=(0, 0, -0.325), verts=32, name="cap_bung"), "capacitor", "rubber")
    for z in (-0.42, 0.42):
        finish(cyl(0.022, 0.18, loc=(0, 0, z), verts=8, name="cap_lead"), "capacitor", "tinned")

    # DIODE: black epoxy body, silver cathode band at +Z
    d = cyl(0.11, 0.38, verts=20, name="diode_body"); bevel_mod(d, 0.04, 3); finish(d, "diode", "epoxy", smooth=True)
    finish(cyl(0.115, 0.07, loc=(0, 0, 0.13), verts=20, name="diode_band"), "diode", "tinned", smooth=True)
    for z in (-0.33, 0.33):
        finish(cyl(0.02, 0.3, loc=(0, 0, z), verts=8, name="diode_lead"), "diode", "tinned")

    # SWITCH: knife switch on a ceramic base (base towards −Y); the blade hinges at z = −0.3
    finish(box((0.42, 0.08, 0.9), loc=(0, -0.42, 0), name="switch_base", bevel=0.02), "switch", "ceramic")
    for z in (-0.3, 0.32):
        for x in (-0.06, 0.06):
            finish(box((0.02, 0.2, 0.08), loc=(x, -0.3, z), name="switch_clip"), "switch", "brass")
    finish(box((0.06, 0.03, 0.66), loc=(0, -0.24, 0.02), name="switch_blade", bevel=0.008), "switch", "brass")
    h = cyl(0.045, 0.2, loc=(0, -0.12, 0.33), rot=(math.pi / 2, 0, 0), verts=12, name="switch_handle"); bevel_mod(h, 0.02)
    finish(h, "switch", "epoxy", smooth=True)
    for z in (-0.47, 0.47):
        # terminal straps rise to the middle of the cell, where wires meet it
        finish(box((0.1, 0.38, 0.06), loc=(0, -0.18, z), name="switch_terminal", bevel=0.01), "switch", "brass")

    # BRUSH: contact face at +Z
    hold = box((0.46, 0.46, 0.5), loc=(0, 0, -0.2), name="brush_holder", bevel=0.02)
    boolean(hold, box((0.34, 0.34, 0.6), loc=(0, 0, 0.0), name="cut")); finish(hold, "brush", "brass")
    finish(box((0.32, 0.32, 0.62), loc=(0, 0, 0.17), name="brush_carbon", bevel=0.015), "brush", "graphite")
    finish(torus(0.1, 0.018, loc=(0, 0, -0.42), name="brush_spring", seg=16), "brush", "metal_smooth", smooth=True)
    finish(cyl(0.03, 0.42, loc=(0.2, 0, -0.1), rot=(0, 0.6, 0), verts=8, name="brush_shunt"), "brush", "wire_copper")
    finish(box((0.9, 0.12, 0.12), loc=(0, 0, -0.44), name="brush_mount", bevel=0.02), "brush", "brass")

    # ELECTROLYTE: glass jar with the solution inside
    jar = box((0.94, 0.94, 0.94), name="jar_glass", bevel=0.05)
    boolean(jar, box((0.86, 0.86, 0.94), loc=(0, 0, 0.05), name="cut")); finish(jar, "electrolyte", "glass__glass")
    finish(box((0.855, 0.855, 0.72), loc=(0, 0, -0.07), name="jar_liquid", bevel=0.01), "electrolyte", "liquid__tint_glass")

    # GEAR (icon only; in game gears are generated from their tooth count and pitch radius)
    z, rp, add, th = 16, 0.4, 0.06, 0.18
    g = cyl(rp - add, th, verts=z * 4, name="gear_body")
    bm = bmesh.new(); bm.from_mesh(g.data)
    for k in range(z):
        a = 2 * math.pi * k / z
        res = bmesh.ops.create_cube(bm, size=1); vs = res["verts"]
        for v in vs:  # tapered involute-like tooth: narrower at the tip
            tip = v.co.y > 0
            v.co.x *= 0.10 if tip else 0.16
            v.co.y = (add * 1.1 if tip else -add * 1.0)
            v.co.z *= th
        bmesh.ops.rotate(bm, verts=vs, cent=(0, 0, 0), matrix=Matrix.Rotation(a - math.pi / 2, 3, 'Z'))
        bmesh.ops.translate(bm, verts=vs, vec=(rp * math.cos(a), rp * math.sin(a), 0))
    bm.to_mesh(g.data); bm.free()
    for k in range(6):
        a = 2 * math.pi * k / 6
        boolean(g, cyl(0.065, 0.5, loc=(0.2 * math.cos(a), 0.2 * math.sin(a), 0), verts=16, name="cut"))
    boolean(g, cyl(0.06, 0.5, name="cut"))
    finish(g, "gear", "metal_brushed__tint")
    hub = cyl(0.13, 0.3, verts=24, name="gear_hub"); boolean(hub, cyl(0.06, 0.5, name="cut"))
    finish(hub, "gear", "metal_cast__tint", smooth=True)

    # SHAFT, WIRE, INSULATED WIRE (icons; generated in game)
    s = cyl(0.12, 1.0, verts=24, name="shaft_body"); bevel_mod(s, 0.02); finish(s, "shaft", "metal_smooth__tint", smooth=True)
    finish(box((0.05, 0.03, 0.4), loc=(0.12, 0, 0.1), name="shaft_key"), "shaft", "metal_cast__tint")
    finish(cyl(0.05, 0.9, verts=12, rot=(0, math.pi / 2, 0), name="wire_a"), "wire", "wire_copper", smooth=True)
    finish(cyl(0.05, 0.5, loc=(0.45, 0, 0.25), verts=12, name="wire_b"), "wire", "wire_copper", smooth=True)
    finish(cyl(0.08, 0.8, verts=12, rot=(0, math.pi / 2, 0), name="iw_jacket"), "insulated_wire", "pvc__blue", smooth=True)
    for x in (-0.47, 0.47):
        finish(cyl(0.045, 0.14, loc=(x, 0, 0), verts=10, rot=(0, math.pi / 2, 0), name="iw_end"), "insulated_wire", "wire_copper", smooth=True)

    # TANK: riveted pressure vessel with a manway and a Bourdon pressure gauge on its front (-Y) face; the
    # needle pivots about the gauge axis at runtime (0 bar gauge at -135 deg, 10 bar at +135 deg)
    body = box((0.92, 0.92, 0.92), name="tank_body", bevel=0.09)
    finish(body, "tank", "metal_cast__tint", smooth=False)
    for z in (-0.25, 0.25):
        finish(box((0.935, 0.935, 0.035), loc=(0, 0, z), name="tank_seam", bevel=0.012), "tank", "metal_brushed__tint")
    for k in range(8):
        a = 2 * math.pi * k / 8
        finish(sphere(0.022, loc=(0.36 * math.cos(a) * 1.0, 0.36 * math.sin(a), 0.465), name="tank_rivet"), "tank", "metal_smooth", smooth=True)
    man = cyl(0.17, 0.06, loc=(0, 0, 0.475), verts=24, name="tank_manway"); bevel_mod(man, 0.01)
    finish(man, "tank", "metal_brushed__tint", smooth=True)
    finish(cyl(0.11, 0.05, loc=(0, 0, 0.51), verts=6, name="tank_manway_nut"), "tank", "brass")
    g = cyl(0.15, 0.05, loc=(0.2, -0.47, 0.2), rot=(math.pi / 2, 0, 0), verts=28, name="tank_gauge_rim"); bevel_mod(g, 0.01)
    finish(g, "tank", "brass", smooth=True)
    finish(cyl(0.13, 0.012, loc=(0.2, -0.497, 0.2), rot=(math.pi / 2, 0, 0), verts=28, name="tank_gauge_face"), "tank", "ceramic")
    finish(cyl(0.02, 0.012, loc=(0.2, -0.505, 0.2), rot=(math.pi / 2, 0, 0), verts=10, name="tank_gauge_hub"), "tank", "epoxy")
    finish(box((0.012, 0.006, 0.11), loc=(0.2, -0.507, 0.25), name="tank_needle"), "tank", "magnet_north")
    for x in (-0.3, 0.3):
        finish(box((0.12, 0.12, 0.06), loc=(x, 0.3, -0.48), name="tank_foot", bevel=0.01), "tank", "dark_paint")

    # VALVE: globe valve along Z, bonnet and handwheel up (+Y); stem and wheel rise when open (runtime)
    glob_ = sphere(0.22, loc=(0, 0, 0), name="valve_globe"); glob_.scale = (1.0, 0.9, 1.15)
    finish(glob_, "valve", "brass", smooth=True)
    for z in (-0.44, 0.44):
        fl = cyl(0.19, 0.07, loc=(0, 0, z), verts=24, name="valve_flange"); bevel_mod(fl, 0.01)
        finish(fl, "valve", "brass", smooth=True)
        for k in range(4):
            a = 2 * math.pi * (k + 0.5) / 4
            finish(cyl(0.025, 0.09, loc=(0.14 * math.cos(a), 0.14 * math.sin(a), z), verts=6, name="valve_bolt"), "valve", "metal_smooth")
    for z in (-0.29, 0.29):
        finish(cyl(0.12, 0.2, loc=(0, 0, z), verts=20, name="valve_neck"), "valve", "brass", smooth=True)
    bon = cyl(0.1, 0.16, loc=(0, 0.26, 0), rot=(math.pi / 2, 0, 0), verts=16, name="valve_bonnet"); bevel_mod(bon, 0.015)
    finish(bon, "valve", "brass", smooth=True)
    finish(cyl(0.13, 0.04, loc=(0, 0.34, 0), rot=(math.pi / 2, 0, 0), verts=6, name="valve_packing_nut"), "valve", "metal_brushed")
    finish(cyl(0.025, 0.2, loc=(0, 0.42, 0), rot=(math.pi / 2, 0, 0), verts=10, name="valve_stem"), "valve", "chrome", smooth=True)
    finish(torus(0.17, 0.025, loc=(0, 0.5, 0), rot=(math.pi / 2, 0, 0), name="valve_wheel", seg=24), "valve", "magnet_north", smooth=True)
    for k in range(3):
        a = 2 * math.pi * k / 3
        finish(box((0.34, 0.025, 0.025), loc=(0, 0.5, 0), rot=(0, a, 0), name="valve_wheel_spoke"), "valve", "magnet_north")
    finish(cyl(0.04, 0.05, loc=(0, 0.5, 0), rot=(math.pi / 2, 0, 0), verts=10, name="valve_wheel_hub"), "valve", "magnet_north")

    # PIPE and ROTOR (icons only; generated in game from bore and blade parameters)
    tube = cyl(0.2, 1.0, verts=28, rot=(0, math.pi / 2, 0), name="pipe_tube"); boolean(tube, cyl(0.16, 1.2, rot=(0, math.pi / 2, 0), name="cut"))
    finish(tube, "pipe", "metal_smooth__tint", smooth=True)
    for x in (-0.45, 0.45):
        fl = cyl(0.27, 0.08, loc=(x, 0, 0), rot=(0, math.pi / 2, 0), verts=28, name="pipe_flange"); boolean(fl, cyl(0.16, 0.2, loc=(x, 0, 0), rot=(0, math.pi / 2, 0), name="cut"))
        finish(fl, "pipe", "metal_smooth__tint", smooth=True)
    hub = cyl(0.12, 0.3, verts=20, name="rotor_hub"); bevel_mod(hub, 0.03)
    finish(hub, "rotor", "metal_brushed__tint", smooth=True)
    for k in range(7):
        a = 2 * math.pi * k / 7
        bl = box((0.34, 0.1, 0.015), loc=(0.27 * math.cos(a), 0.27 * math.sin(a), 0), name="rotor_blade")
        bl.rotation_euler = (0, 0, a)
        bl.rotation_mode = 'XYZ'
        bpy.context.view_layer.update()
        bl.rotation_euler.rotate_axis('X', math.radians(45))
        finish(bl, "rotor", "metal_brushed__tint")
    ring = torus(0.45, 0.025, name="rotor_shroud", seg=32)
    finish(ring, "rotor", "metal_brushed__tint", smooth=True)

    # TOOLS
    finish(box((0.6, 0.18, 0.95), name="mm_body", bevel=0.06), "multimeter", "plastic__yellow")
    finish(box((0.44, 0.02, 0.26), loc=(0, -0.1, 0.25), name="mm_lcd", bevel=0.01), "multimeter", "silicon")
    dial = cyl(0.13, 0.04, loc=(0, -0.1, -0.12), rot=(math.pi / 2, 0, 0), verts=24, name="mm_dial"); bevel_mod(dial, 0.01)
    finish(dial, "multimeter", "epoxy", smooth=True)
    for x, mt in ((-0.16, "epoxy"), (0.16, "magnet_north")):
        finish(cyl(0.04, 0.04, loc=(x, -0.1, -0.36), rot=(math.pi / 2, 0, 0), verts=12, name="mm_jack"), "multimeter", mt)
    for s in (-1, 1):
        finish(box((0.06, 0.03, 0.95), loc=(s * 0.07, 0, 0), rot=(0, s * 0.12, 0), name="tw_arm", bevel=0.01),
               "precision_tweezers", "metal_brushed")


# ------------------------------------------------------------------ export
RUNTIME_MODELS = ["tank", "valve", "plate", "magnet", "bearing", "coil", "crank", "resistor", "capacitor", "diode", "switch",
                  "brush", "electrolyte"]


def export_models():
    os.makedirs(MODELS, exist_ok=True)
    for name in RUNTIME_MODELS:
        bpy.ops.object.select_all(action='DESELECT')
        for o in bpy.data.collections[name].objects: o.select_set(True)
        # material names in the OBJ must be the convention names, not icon-tinted copies
        bpy.ops.wm.obj_export(filepath=os.path.join(MODELS, name + ".obj"), export_selected_objects=True,
                              forward_axis='NEGATIVE_Z', up_axis='Y', export_materials=False, export_uv=True,
                              export_normals=True, apply_modifiers=True, export_triangulated_mesh=False,
                              export_material_groups=False, export_object_groups=False)
        # write usemtl lines ourselves (exporter without materials omits them): rewrite per object
        fix_usemtl(os.path.join(MODELS, name + ".obj"), name)


def fix_usemtl(path, coll):
    """Insert 'usemtl <convention name>' after each object line, from the object's material."""
    mats = {o.name: o.data.materials[0]["em_name"] for o in bpy.data.collections[coll].objects}
    out = []
    for line in open(path):
        out.append(line)
        if line.startswith("o "):
            obj = line[2:].strip()
            out.append("usemtl %s\n" % mats.get(obj, "metal_smooth"))
    open(path, "w").write("".join(out))


# ------------------------------------------------------------------ icons
ICON_PARTS = {"plate": "block", "magnet": "magnet", "bearing": "bearing", "coil": "coil", "crank": "crank",
              "resistor": "resistor", "capacitor": "capacitor", "diode": "diode", "switch": "switch",
              "brush": "brush", "electrolyte": "electrolyte", "gear": "gear", "shaft": "shaft", "wire": "wire",
              "insulated_wire": "insulated_wire", "multimeter": "multimeter", "precision_tweezers": "precision_tweezers",
              "tank": "tank", "valve": "valve", "pipe": "pipe", "rotor": "rotor"}


def render_icons():
    os.makedirs(ICONS, exist_ok=True)
    sc = bpy.context.scene
    sc.render.resolution_x = sc.render.resolution_y = ICON_SIZE
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = 'PNG'
    sc.render.image_settings.color_mode = 'RGBA'
    world = sc.world or bpy.data.worlds.new("w"); sc.world = world
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == "BACKGROUND")
    bg.inputs[0].default_value = (0.8, 0.82, 0.86, 1); bg.inputs[1].default_value = 0.9
    cam_data = bpy.data.cameras.new("icon_cam"); cam_data.type = 'ORTHO'; cam_data.ortho_scale = 1.55
    cam = bpy.data.objects.new("icon_cam", cam_data); sc.collection.objects.link(cam); sc.camera = cam
    sun_data = bpy.data.lights.new("icon_sun", 'SUN'); sun_data.energy = 3.5
    sun = bpy.data.objects.new("icon_sun", sun_data); sc.collection.objects.link(sun)
    sun.rotation_euler = (math.radians(50), math.radians(10), math.radians(30))
    d = (1.0, -1.0, 0.85)
    L = math.sqrt(sum(c * c for c in d))
    cam.location = tuple(5 * c / L for c in d)
    direction = -cam.location.__class__(cam.location) if False else None
    import mathutils
    cam.rotation_euler = (mathutils.Vector((0, 0, 0)) - mathutils.Vector(cam.location)).to_track_quat('-Z', 'Y').to_euler()
    for coll, item in ICON_PARTS.items():
        for c in bpy.context.scene.collection.children:
            c.hide_render = (c.name != coll)
        # frame the part: fit its projected bounds (camera space) with a small margin
        inv = cam.matrix_world.inverted()
        pts = [inv @ (o.matrix_world @ v.co) for o in bpy.data.collections[coll].objects for v in o.data.vertices]
        xs = [p.x for p in pts]; ys = [p.y for p in pts]
        cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        cam_data.ortho_scale = max(max(xs) - min(xs), max(ys) - min(ys)) * 1.12
        cam_data.shift_x = cx / cam_data.ortho_scale
        cam_data.shift_y = cy / cam_data.ortho_scale
        sc.render.filepath = os.path.join(ICONS, item + ".png")
        bpy.ops.render.render(write_still=True)
    for c in bpy.context.scene.collection.children: c.hide_render = False


ICON_MODE = False


def run(icons=True):
    global ICON_MODE
    reset_scene()
    ICON_MODE = False
    build_all()
    export_models()
    if icons:
        reset_scene()
        ICON_MODE = True
        build_all()
        render_icons()


run()
print("Emergence part models and icons built.")
