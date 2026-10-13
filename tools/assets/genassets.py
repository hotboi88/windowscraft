import json, os, shutil, sys
SRC = sys.argv[1] + "/assets/cozymonitors"
RES = sys.argv[2]  # src/main/resources
A = RES + "/assets/windowscraft"
D = RES + "/data/windowscraft"
ORDER = ["white", "silver", "light_gray", "ash", "gray", "charcoal", "black", "brown",
         "maroon", "red", "vermilion", "orange", "amber", "yellow", "chartreuse", "lime",
         "fern", "green", "jade", "cyan", "aqua", "light_blue", "cerulean", "blue",
         "indigo", "purple", "violet", "magenta", "orchid", "pink", "rose", "sienna"]
BASE = ["white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"]
INTER = {"silver": ("white", "light_gray"), "ash": ("light_gray", "gray"), "charcoal": ("gray", "black"),
         "sienna": ("brown", "orange"), "maroon": ("brown", "red"), "vermilion": ("red", "orange"), "amber": ("orange", "yellow"),
         "chartreuse": ("yellow", "lime"), "fern": ("lime", "green"), "jade": ("green", "cyan"), "aqua": ("cyan", "light_blue"),
         "cerulean": ("light_blue", "blue"), "indigo": ("blue", "purple"), "violet": ("purple", "magenta"),
         "orchid": ("magenta", "pink"), "rose": ("pink", "red")}
assert set(ORDER) == set(BASE) | set(INTER)

def w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=2)

def fix(s): return s.replace("cozymonitors:", "windowscraft:")

for sub in ["block", "item"]:
    os.makedirs(f"{A}/textures/{sub}", exist_ok=True)
    for f in os.listdir(f"{SRC}/textures/{sub}"):
        shutil.copy(f"{SRC}/textures/{sub}/{f}", f"{A}/textures/{sub}/{f}")

tpl = json.load(open(f"{SRC}/models/block/gaming_monitor_template.json"))
tpl["credit"] = "WindowsCraft"
w(f"{A}/models/block/gaming_monitor_template.json", tpl)
diag = json.loads(json.dumps(tpl))
for e in diag["elements"]:
    e["rotation"] = {"origin": [8, 8, 8], "axis": "y", "angle": 45}
diag.pop("display", None)
w(f"{A}/models/block/gaming_monitor_template_diagonal.json", diag)
# a joined (floating) monitor: just its panel, made facing south and drawn turned by its block
# entity's renderer (MonitorPanelRenderer); the block itself shows nothing (no stand)
panel = {"credit": "WindowsCraft", "textures": {"particle": "#front"}, "elements": [{
    "name": "panel", "from": [0, 4, 7], "to": [16, 14, 9], "faces": {
        "south": {"uv": [0, 0, 16, 10], "texture": "#front"},
        "north": {"uv": [0, 0, 16, 10], "texture": "#back"},
        "up": {"uv": [0, 10, 16, 12], "texture": "#back"},
        "down": {"uv": [0, 12, 16, 14], "texture": "#back"},
        "east": {"uv": [0, 0, 2, 10], "texture": "#body"},
        "west": {"uv": [0, 0, 2, 10], "texture": "#body"}}}]}
w(f"{A}/models/block/gaming_monitor_panel_template.json", panel)

# turn t (0..7): the screen faces yaw t*45° (0 = south, 2 = west, 4 = north, 6 = east, odd = in between)
def variant(c, t):
    if t % 2 == 0: return {"model": f"windowscraft:block/{c}_gaming_monitor", "y": ((t - 4) % 8) // 2 * 90}
    return {"model": f"windowscraft:block/{c}_gaming_monitor_diagonal", "y": ((t - 3) % 8) // 2 * 90}

def tablet_model(c):
    f, b = f"windowscraft:item/{c}_tablet", f"windowscraft:item/{c}_tablet_back"
    return {
        "gui_light": "front",
        "textures": {"front": f, "back": b, "particle": f},
        "elements": [{"from": [0, 2, 7.5], "to": [16, 14, 8.5], "faces": {
            "south": {"uv": [0, 2, 16, 14], "texture": "#front"},
            "north": {"uv": [16, 2, 0, 14], "texture": "#back"},
            "up": {"uv": [0, 2, 16, 3], "texture": "#front"},
            "down": {"uv": [0, 13, 16, 14], "texture": "#front"},
            "east": {"uv": [15, 2, 16, 14], "texture": "#front"},
            "west": {"uv": [0, 2, 1, 14], "texture": "#front"}}}],
        "display": {
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
            "thirdperson_righthand": THIRD,
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]}}}

# third person: like a compass or any small tool, its screen up (tilted a little toward you)
THIRD = {"rotation": [25, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]}

def tablet_back(c):
    from PIL import Image
    im = Image.open(f"{A}/textures/item/{c}_tablet.png").convert("RGBA")
    px = im.load()
    frame = {}
    for x in range(16):
        for y in (2, 13):
            frame[px[x, y]] = frame.get(px[x, y], 0) + 1
    for y in range(3, 13):
        for x in (0, 15):
            frame[px[x, y]] = frame.get(px[x, y], 0) + 1
    base = max(frame, key=frame.get)
    def mix(a, b, t): return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
    dark, light = mix(base, (12, 10, 18), 0.55), mix(base, (255, 244, 220), 0.3)
    for y in range(3, 13):
        for x in range(1, 15):
            h = (x * 73 + y * 151 + x * y * 7) % 23 # a light matte grain
            px[x, y] = mix(base, (255, 244, 220), 0.1) if h == 0 else mix(base, (12, 10, 18), 0.1) if h == 11 else base
    for x, y in ((2, 4), (3, 4), (2, 5), (3, 5)): px[x, y] = dark       # camera
    px[3, 4] = mix(base, (255, 255, 255), 0.5)                            # its glint
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)): px[x, y] = light      # a small logo
    im.save(f"{A}/textures/item/{c}_tablet_back.png")

lang = json.load(open(A + "/lang/en_us.json"))
for c in ORDER:
    m = json.loads(fix(open(f"{SRC}/models/block/{c}_gaming_monitor.json").read()))
    w(f"{A}/models/block/{c}_gaming_monitor.json", m)
    md = dict(m); md["parent"] = "windowscraft:block/gaming_monitor_template_diagonal"
    w(f"{A}/models/block/{c}_gaming_monitor_diagonal.json", md)
    mp = dict(m); mp["parent"] = "windowscraft:block/gaming_monitor_panel_template"
    w(f"{A}/models/block/{c}_gaming_monitor_panel.json", mp)
    w(f"{A}/models/block/{c}_gaming_monitor_free.json", {"textures": {"particle": m["textures"]["front"]}, "elements": []})
    vs = {}
    for t in range(8):
        v = variant(c, t)
        if v["y"] == 0: del v["y"]
        vs[f"free=false,turn={t}"] = v
    vs["free=true"] = {"model": f"windowscraft:block/{c}_gaming_monitor_free"}
    w(f"{A}/blockstates/{c}_gaming_monitor.json", {"variants": vs})
    w(f"{A}/models/item/{c}_gaming_monitor.json", {"parent": f"windowscraft:block/{c}_gaming_monitor"})
    # a thin slab: the screen in front, a plain back behind (in first person the game holds it
    # like a map, with your arm — see Tablet.java)
    w(f"{A}/models/item/{c}_tablet.json", tablet_model(c))
    tablet_back(c)
    name = c.replace("_", " ").title()
    lang[f"block.windowscraft.{c}_gaming_monitor"] = f"{name} Gaming Monitor"
    lang[f"item.windowscraft.{c}_tablet"] = f"{name} Tablet"
    w(f"{D}/loot_table/blocks/{c}_gaming_monitor.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"windowscraft:{c}_gaming_monitor"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    dyes = [c] if c in BASE else list(INTER[c])
    for kind, tag in (("gaming_monitor", "gaming_monitors"), ("tablet", "tablets")):
        w(f"{D}/recipe/{c}_{kind}_from_dye.json", {
            "type": "minecraft:crafting_shapeless", "category": "redstone", "group": f"windowscraft_{kind}_dye",
            "ingredients": [{"tag": f"windowscraft:{tag}"}] + [{"item": f"minecraft:{d}_dye"} for d in dyes],
            "result": {"id": f"windowscraft:{c}_{kind}", "count": 1}})
lang["itemGroup.windowscraft"] = "WindowsCraft"
lang["item.windowscraft.tablet.tip"] = "Right-click: hold it up and use it. Sneak + right-click: turn it off."
lang["block.windowscraft.gaming_monitor.tip"] = "Look at it and press U: it becomes a real extra screen for your PC."
w(A + "/lang/en_us.json", lang)

w(f"{D}/tags/item/gaming_monitors.json", {"replace": False, "values": [f"windowscraft:{c}_gaming_monitor" for c in ORDER]})
w(f"{D}/tags/item/tablets.json", {"replace": False, "values": [f"windowscraft:{c}_tablet" for c in ORDER]})
w(f"{D}/tags/block/gaming_monitors.json", {"replace": False, "values": [f"windowscraft:{c}_gaming_monitor" for c in ORDER]})
w(f"{RES}/data/minecraft/tags/block/mineable/pickaxe.json", {"replace": False, "values": ["#windowscraft:gaming_monitors"]})
w(f"{D}/recipe/black_gaming_monitor.json", {
    "type": "minecraft:crafting_shaped", "category": "redstone",
    "pattern": ["IGI", "IRI", " I "],
    "key": {"I": {"item": "minecraft:iron_ingot"}, "G": {"item": "minecraft:black_stained_glass_pane"}, "R": {"item": "minecraft:redstone"}},
    "result": {"id": "windowscraft:black_gaming_monitor", "count": 1}})
w(f"{D}/recipe/black_tablet.json", {
    "type": "minecraft:crafting_shaped", "category": "redstone",
    "pattern": ["IGI", "IRI"],
    "key": {"I": {"item": "minecraft:iron_ingot"}, "G": {"item": "minecraft:black_stained_glass_pane"}, "R": {"item": "minecraft:redstone"}},
    "result": {"id": "windowscraft:black_tablet", "count": 1}})
print("ok", len(ORDER))
