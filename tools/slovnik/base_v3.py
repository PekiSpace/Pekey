"""
Základ slovníku Pekey v3: četnosti z wordfreq (Wikipedie, titulky, zprávy, knihy,
Twitter, Reddit) smíchané se stávajícími, doplněná častá slova a dvojice slov
pro předpověď spočítané z českých korpusů Universal Dependencies (FicTree, CAC,
CLTT, PUD, Poetry). Vstup: pekey_cs_blend.combined (blend.py), složka ud/ s .conllu.
Výstup: pekey_cs_v3.combined.
"""
import re, glob, time, collections
import wordfreq
from spylls.hunspell import Dictionary

W_WF = 0.6
def zipf_to_f(z): return max(0, min(255, round((z - 1.0) * 255 / 6.5)))

# --- načíst stávající slovník ---
words = {}      # slovo -> f
bigr = collections.defaultdict(dict)  # w1 -> {w2: f}
cur = None
for l in open('pekey_cs_blend.combined'):
    if l.startswith(' word='):
        m = re.match(r' word=(.*?),f=(\d+)', l); cur = m.group(1); words[cur] = int(m.group(2))
    elif l.startswith('  bigram='):
        m = re.match(r'  bigram=(.*?),f=(\d+)', l); bigr[cur][m.group(1)] = int(m.group(2))
lower_form = {}
for w in words:
    k = w.lower()
    if k not in lower_form or w == k: lower_form[k] = w

# --- přepočet četností ---
new = {}
for w, f in words.items():
    z = wordfreq.zipf_frequency(w, 'cs')
    if z <= 0 and w != w.lower(): z = wordfreq.zipf_frequency(w.lower(), 'cs') - 0.3
    new[w] = round(W_WF * zipf_to_f(z) + (1 - W_WF) * f) if z > 0 else max(0, f - 15)

# --- doplnit častá slova z wordfreq ---
hun = Dictionary.from_files('cs')
added = 0
for w in wordfreq.top_n_list('cs', 300000):
    z = wordfreq.zipf_frequency(w, 'cs')
    if z < 2.5: break
    if w in lower_form or not w.isalpha() or len(w) < 2: continue
    if hun.lookup(w):
        new[w] = zipf_to_f(z); lower_form[w] = w; added += 1

# --- dvojice z korpusů UD ---
pairs = collections.Counter(); firsts = collections.Counter()
for path in glob.glob('/tmp/ud/*.conllu'):
    sent = []
    for l in open(path, encoding='utf-8'):
        if not l.strip():
            for a, b in zip(sent, sent[1:]):
                if a and b: pairs[(a, b)] += 1
            for a in sent:
                if a: firsts[a] += 1
            sent = []; continue
        if l.startswith('#'): continue
        c = l.split('\t')
        if '-' in c[0] or '.' in c[0]: continue
        t = c[1].lower()
        sent.append(lower_form.get(t) if t.isalpha() else None)
by_first = collections.defaultdict(list)
for (a, b), n in pairs.items():
    if n >= 2: by_first[a].append((n, b))
nb = 0
for a, lst in by_first.items():
    lst.sort(reverse=True)
    for n, b in lst[:10]:
        p = n / firsts[a]
        uni = new.get(b, 0)
        f = round(uni + (255 - uni) * (0.25 + 0.75 * min(1.0, p * 4)))
        if f > bigr[a].get(b, 0):
            bigr[a][b] = f; nb += 1

with open('pekey_cs_v3.combined', 'w') as out:
    out.write('dictionary=main:cs,locale=cs,description=Čeština (Pekey),date=%d,version=21\n' % int(time.time()))
    for w, f in new.items():
        out.write(' word=%s,f=%d\n' % (w, f))
        for b, bf in bigr.get(w, {}).items():
            if b in new: out.write('  bigram=%s,f=%d\n' % (b, bf))
print('slov', len(new), 'přidáno', added, 'nových dvojic', nb, 'vět', sum(firsts.values()))
for w in ['ahoj','máš','nad','dneska','dneška','rád','řád','hezky','hezký','proč','Přerov','sakra','sajga','jo','teda']:
    print(w, words.get(w), '→', new.get(w))
for w in ['se','jak','to','dobrý','je','já','co']:
    print(w, sorted(bigr.get(w,{}).items(), key=lambda x:-x[1])[:8])
