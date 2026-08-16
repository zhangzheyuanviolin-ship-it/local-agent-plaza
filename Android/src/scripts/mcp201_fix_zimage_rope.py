from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/ZImageTurboRuntime.kt"
text = p.read_text()
old = "val axisDims = intArrayOf(32, 32, 64)"
new = "val axisDims = intArrayOf(32, 48, 48) // Exact Box 3.3.3 Z-Image RoPE axes."
if text.count(old) != 1:
    raise SystemExit(f"expected one RoPE axis anchor, found {text.count(old)}")
p.write_text(text.replace(old, new, 1))
print("Z-Image RoPE axes corrected to Box 3.3.3: 32,48,48")
