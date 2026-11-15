"""
Anglický slovník Pekey (en_US): rozbalený experimentální main_en_us (en.combined
z decode.py), pročištěný (četnost ≥ 20 a Hunspell nebo wordfreq ≥ 2,5),
četnosti 60 % wordfreq + 40 % původní, dvojice doplněné z korpusů UD English
(EWT, GUM, ParTUT, LinES, PUD) ve složce uden/. Výstup: pekey_en.combined.
"""
import re, glob, time, collections
import wordfreq
from spylls.hunspell import Dictionary

W_WF = 0.6
def zipf_to_f(z): return max(0, min(255, round((z - 1.0) * 255 / 6.5)))
hun = Dictionary.from_files('en')

words = {}; bigr = collections.defaultdict(dict); cur = None
for l in open('en.combined'):
    if l.startswith(' word='):
        m = re.match(r' word=(.*?),f=(\d+)', l); cur = m.group(1); words[cur] = int(m.group(2))
    elif l.startswith('  bigram='):
        m = re.match(r'  bigram=(.*?),f=(\d+)', l); bigr[cur][m.group(1)] = int(m.group(2))

new = {}
for w, f in words.items():
    z = wordfreq.zipf_frequency(w, 'en')
    if f < 20 and z < 2.5: continue
    if z < 2.5 and not hun.lookup(w): continue
    new[w] = round(W_WF * zipf_to_f(z) + (1 - W_WF) * f) if z > 0 else max(0, f - 15)
lower_form = {}
for w in new:
    k = w.lower()
    if k not in lower_form or w == k: lower_form[k] = w
# zájmeno I a jeho stažené tvary se píšou velkým (ne římské číslo i)
for k in list(lower_form):
    if k == 'i' or k.startswith("i'"):
        cap = 'I' + k[1:]
        if cap in new: lower_form[k] = cap
added = 0
for w in wordfreq.top_n_list('en', 300000):
    z = wordfreq.zipf_frequency(w, 'en')
    if z < 2.5: break
    if w in lower_form or not w.replace("'", "").isalpha() or len(w) < 2: continue
    if hun.lookup(w):
        new[w] = zipf_to_f(z); lower_form[w] = w; added += 1

pairs = collections.Counter(); firsts = collections.Counter()
for path in glob.glob('/tmp/uden/*.conllu'):
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
        if '.' in c[0]: continue
        if '-' in c[0]:
            # stažený tvar (don't, I'm) jako jedno slovo, jeho části se přeskočí
            a, b = c[0].split('-'); skip = int(b) - int(a) + 1
            t = c[1].lower(); sent.append(lower_form.get(t)); sent_skip = skip; continue
        if 'sent_skip' in dir() and sent_skip > 0:
            sent_skip -= 1; continue
        t = c[1].lower()
        sent.append(lower_form.get(t) if t.replace("'", "").isalpha() else None)
    sent_skip = 0
by_first = collections.defaultdict(list)
for (a, b), n in pairs.items():
    if n >= 2: by_first[a].append((n, b))
nb = 0
for a, lst in by_first.items():
    lst.sort(reverse=True)
    for n, b in lst[:10]:
        p = n / firsts[a]; uni = new.get(b, 0)
        f = round(uni + (255 - uni) * (0.25 + 0.75 * min(1.0, p * 4)))
        if f > bigr[a].get(b, 0): bigr[a][b] = f; nb += 1

with open('pekey_en.combined', 'w') as out:
    out.write('dictionary=main:en_us,locale=en_US,description=English (Pekey),date=%d,version=21\n' % int(time.time()))
    for w, f in new.items():
        out.write(' word=%s,f=%d\n' % (w, f))
        for b, bf in bigr.get(w, {}).items():
            if b in new: out.write('  bigram=%s,f=%d\n' % (b, bf))
print('slov', len(words), '→', len(new), 'přidáno', added, 'nových dvojic', nb)
for w in ['how','are','you','I','I\'m','don\'t','the']:
    print(w, words.get(w), '→', new.get(w), sorted(bigr.get(w,{}).items(), key=lambda x:-x[1])[:6])
