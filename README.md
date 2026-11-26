# Base Finder ESP (Fabric, Minecraft 26.3, client-only)

Commands: /esp on | off | threshold <n> | strict | relaxed | debug

What it flags: a chunk holding >= threshold (default 8) chests + shulker boxes + armor stands (each half of a double chest counts, so a double chest = 2).
Then it analyses the 3x3 chunks around it and decides:
- RAIDED (hidden): stray obsidian, floating debris, scattered containers, loose items lying around.
- ACTIVE (green): kelp/crops/cane/bamboo/cactus/amethyst buds next to pistons/observers CHANGED while you were watching.
- GROWING (yellow): such a farm is caught mid-growth.
- STASH (grey, relaxed mode only): enough containers, no farm activity.

Overlay is drawn in 2D over the screen so it shows through walls. Use first person and set
Video Settings > FOV Effects to 0% so boxes line up exactly.
Purely passive: reads chunk data the server already sent, sends no packets.
