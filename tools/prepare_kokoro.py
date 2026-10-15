#!/usr/bin/env python3
"""Build the Kokoro asset bundle from the public sherpa-onnx release package.
Usage: prepare_kokoro.py <extracted kokoro-int8-multi-lang-v1_0 dir> <output dir>
Ships: model.int8.onnx (unmodified), voices.bin trimmed to our 7 voices (index = order below),
tokens.txt, lexicon-us-en.txt. No espeak, no sherpa: the app does G2P itself."""
import sys, os, shutil, numpy as np

KEEP = ["af_sky", "af_heart", "af_alloy", "af_nova", "bf_emma", "bm_fable", "bm_lewis"]
ALL_IDX = {"af_sky": 10, "af_heart": 3, "af_alloy": 0, "af_nova": 7, "bf_emma": 21, "bm_fable": 25, "bm_lewis": 27}
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)

v = np.fromfile(f"{src}/voices.bin", dtype=np.float32)
n = v.size // (510 * 256)
v = v.reshape(n, 510, 256)
np.stack([v[ALL_IDX[k]] for k in KEEP]).astype(np.float32).tofile(f"{dst}/voices.bin")
shutil.copy(f"{src}/model.int8.onnx", dst)
shutil.copy(f"{src}/tokens.txt", dst)
shutil.copy(f"{src}/lexicon-us-en.txt", f"{dst}/lexicon.txt")
open(f"{dst}/voices.txt", "w").write("\n".join(KEEP) + "\n")
print("bundle ready:", KEEP, "model MB:", os.path.getsize(f"{dst}/model.int8.onnx") // 1_000_000)
