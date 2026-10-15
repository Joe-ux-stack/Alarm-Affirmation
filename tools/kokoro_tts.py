"""Reference Python implementation of the app's text->speech pipeline (mirrors Kokoro.kt). Used to
render the bundled voice testers in CI and to check changes before porting them to Kotlin."""
import re, numpy as np, onnxruntime as ort, wave, sys, time, os
D = os.environ.get("KOKORO_DIR", "kokoro-int8-multi-lang-v1_0") + "/"
vocab={}
for line in open(D+"tokens.txt",encoding="utf8"):
    line=line.rstrip("\n")
    if not line: continue
    sym,idx=line.rsplit(" ",1); vocab[sym]=int(idx)
lex={}
for line in open(D+"lexicon-us-en.txt",encoding="utf8"):
    p=line.rstrip("\n").split(" ")
    if len(p)>1: lex[p[0]]=p[1:]
ONES="zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS="x x twenty thirty forty fifty sixty seventy eighty ninety".split()
def num(n):
    n=int(n)
    if n<20: return ONES[n]
    if n<100: return TENS[n//10]+("" if n%10==0 else " "+ONES[n%10])
    if n<1000: return ONES[n//100]+" hundred"+("" if n%100==0 else " "+num(n%100))
    return " ".join(num(int(c)) for c in str(n)) if n>9999 else num(n//1000)+" thousand"+("" if n%1000==0 else " "+num(n%1000))
def expand(text):
    def tm(m):
        h,mi=int(m.group(1)),int(m.group(2))
        if mi==0: return num(h)+" o'clock"
        if mi<10: return num(h)+" oh "+num(mi)
        return num(h)+" "+num(mi)
    text=re.sub(r"(\d{1,2}):(\d{2})",tm,text)
    text=re.sub(r"\d+",lambda m:num(m.group()),text)
    return text
# letter-to-sound fallback for words not in the lexicon (rough but intelligible)
RULES=[("tion","ʃən"),("ough","ʌf"),("igh","ˈaɪ"),("ch","ʧ"),("sh","ʃ"),("th","θ"),("ph","f"),("wh","w"),("ck","k"),("ng","ŋ"),("qu","kw"),("ee","i"),("ea","i"),("oo","u"),("ou","aʊ"),("ow","oʊ"),("ai","eɪ"),("ay","eɪ"),("oi","ɔɪ"),("oy","ɔɪ"),("ar","ɑɹ"),("er","ɚ"),("or","ɔɹ"),("ur","ɚ"),("ir","ɚ"),
("a","æ"),("b","b"),("c","k"),("d","d"),("e","ɛ"),("f","f"),("g","ɡ"),("h","h"),("i","ɪ"),("j","ʤ"),("k","k"),("l","l"),("m","m"),("n","n"),("o","ɑ"),("p","p"),("r","ɹ"),("s","s"),("t","t"),("u","ʌ"),("v","v"),("w","w"),("x","ks"),("y","i"),("z","z")]
def lts(w):
    out=[];i=0
    while i<len(w):
        for k,v in RULES:
            if w.startswith(k,i): out.append(v); i+=len(k); break
        else: i+=1
    s="".join(out)
    return list(s) if s else []
def g2p(text):
    text=expand(text)
    toks=[]
    for m in re.finditer(r"[A-Za-z']+|[.,!?;:]|\s+|.",text):
        t=m.group()
        if t.isspace(): toks.append(" "); continue
        if re.match(r"[A-Za-z']",t):
            w=t.lower()
            ph=lex.get(w)
            if ph is None and w.endswith("'s"): ph=lex.get(w[:-2]); ph=ph+["z"] if ph else None
            if ph is None and w.endswith("s"): ph=lex.get(w[:-1]); ph=ph+["z"] if ph else None
            if ph is None: ph=lts(w); print("OOV",w,"".join(ph),file=sys.stderr)
            toks+=ph
        elif t in vocab: toks.append(t)
    ids=[vocab[p] for p in toks if p in vocab]
    return ids
class Kokoro:
    def __init__(self, d=None, model=None):
        d = (d or D.rstrip("/")) + "/"
        self.sess = ort.InferenceSession(model or d + "model.int8.onnx")
        v = np.fromfile(d + "voices.bin", dtype=np.float32)
        self.voices = v.reshape(v.size // (510 * 256), 510, 256)
    def tts(self, text, sid=10, speed=1.0):
        ids = g2p(text)[:508]
        style = self.voices[sid][len(ids)][None, :]
        return self.sess.run(["audio"], {"tokens": np.array([[0] + ids + [0]], dtype=np.int64), "style": style, "speed": np.array([speed], dtype=np.float32)})[0]
    def to_wav(self, text, sid, path, speed=1.0):
        parts = [p.strip() for p in re.split(r"(?<=[.!?])\s+", text) if p.strip()]
        out = []
        for p in parts:
            out.append(self.tts(p, sid, speed)); out.append(np.zeros(int(24000 * 0.35), np.float32))
        a = (np.clip(np.concatenate(out), -1, 1) * 32767).astype(np.int16)
        w = wave.open(path, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(a.tobytes()); w.close()

if __name__ == "__main__":
    Kokoro().to_wav(" ".join(sys.argv[1:]) or "Good morning. It's Thursday.", 10, "out.wav")
