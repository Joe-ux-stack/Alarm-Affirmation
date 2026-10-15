#!/usr/bin/env python3
"""Build the trimmed Kokoro asset bundle from the public sherpa-onnx release.
Usage: prepare_kokoro.py <extracted sherpa kokoro-int8-multi-lang-v1_0 dir> <output dir>
Keeps 7 English voices, English lexicon, English-only espeak data, and patches ONNX metadata."""
import sys, os, shutil, numpy as np, onnx

KEEP = ["af_sky", "af_heart", "af_alloy", "af_nova", "bf_emma", "bm_fable", "bm_lewis"]
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)

m = onnx.load(f"{src}/model.int8.onnx", load_external_data=False)
meta = {p.key: p.value for p in m.metadata_props}
names = meta["speaker_names"].split(",")
idx = [names.index(n) for n in KEEP]
n_all = len(names)

# Ship the model and voice table UNMODIFIED (sherpa's native loader validates them against each
# other); the app exposes only KEEP in its UI and maps names to these original indices.
shutil.copy(f"{src}/voices.bin", dst)
shutil.copy(f"{src}/model.int8.onnx", dst)
open(f"{dst}/speaker_ids.txt", "w").write("\n".join(f"{n} {names.index(n)}" for n in KEEP) + "\n")

for f in ["tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt"]:
    shutil.copy(f"{src}/{f}", dst)
os.makedirs(f"{dst}/espeak-ng-data", exist_ok=True)
for f in ["phondata", "phondata-manifest", "phonindex", "phontab", "intonations", "en_dict"]:
    shutil.copy(f"{src}/espeak-ng-data/{f}", f"{dst}/espeak-ng-data/")
shutil.copytree(f"{src}/espeak-ng-data/lang", f"{dst}/espeak-ng-data/lang", dirs_exist_ok=True)
open(f"{dst}/voices.txt", "w").write("\n".join(KEEP) + "\n")
print("bundle ready:", KEEP)
