#!/usr/bin/env python3
"""Build the small Kokoro asset bundle (no model!) from the sherpa-onnx int8 package.
Usage: prepare_kokoro.py <extracted kokoro-int8-multi-lang-v1_0 dir> <output dir>
Ships: voices.bin trimmed to our 7 voices, tokens.txt, lexicon.txt, and preview/<voice>.m4a testers
rendered here with the full pipeline (tools/kokoro_tts.py). The model itself is downloaded by the app."""
import sys, os, shutil, subprocess, numpy as np

KEEP = ["af_sky", "af_heart", "af_alloy", "af_nova", "bf_emma", "bm_fable", "bm_lewis"]
ALL_IDX = {"af_sky": 10, "af_heart": 3, "af_alloy": 0, "af_nova": 7, "bf_emma": 21, "bm_fable": 25, "bm_lewis": 27}
SAMPLE = "Good morning. It's Thursday. Standup at ten, lunch at twelve thirty. You've handled harder."
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(f"{dst}/preview", exist_ok=True)

v = np.fromfile(f"{src}/voices.bin", dtype=np.float32)
v = v.reshape(v.size // (510 * 256), 510, 256)
np.stack([v[ALL_IDX[k]] for k in KEEP]).astype(np.float32).tofile(f"{dst}/voices.bin")
shutil.copy(f"{src}/tokens.txt", dst)
shutil.copy(f"{src}/lexicon-us-en.txt", f"{dst}/lexicon.txt")
open(f"{dst}/voices.txt", "w").write("\n".join(KEEP) + "\n")

sys.path.insert(0, os.path.dirname(__file__))
import kokoro_tts
tts = kokoro_tts.Kokoro(src, model=os.environ.get("KOKORO_MODEL", f"{src}/model.int8.onnx"))
for k in KEEP:
    wav = f"{dst}/preview/{k}.wav"
    tts.to_wav(SAMPLE, ALL_IDX[k], wav)
    subprocess.check_call(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-b:a", "64k", f"{dst}/preview/{k}.m4a"])
    os.remove(wav)
print("bundle ready:", os.listdir(dst), os.listdir(f"{dst}/preview"))
