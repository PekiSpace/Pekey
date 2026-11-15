"""
Přepočítá četnosti slovníku Pekey podle hovorové češtiny (titulky OpenSubtitles 2018,
hermitdave/FrequencyWords, cs_full.txt) a doplní častá hovorová slova, která chybí.
Vstup: pekey_cs.combined (z build.py), cs_full.txt, cs.aff/cs.dic. Výstup: pekey_cs_blend.combined.
"""
import re, bisect, time
from spylls.hunspell import Dictionary

W_SUB = 0.65          # váha titulků, zbytek původní (webový) slovník
MISSING_PENALTY = 15  # slovo, které v titulcích není: o tolik nižší „titulková“ četnost
ADD_MIN_COUNT = 50    # nová slova z titulků: aspoň tolikrát v titulcích

lines = open('pekey_cs.combined').read().split('\n')
words = {}
for l in lines:
    if l.startswith(' word='):
        m = re.match(r' word=(.*?),f=(\d+)', l); words[m.group(1)] = int(m.group(2))

sub = {}
for l in open('cs_full.txt'):
    p = l.split()
    if len(p) == 2 and p[1].isdigit():
        sub[p[0]] = int(p[1])

# pořadí v titulcích → četnost se stejným pořadím ve slovníku (stejná stupnice)
dict_sorted = sorted(words.values(), reverse=True)
sub_sorted = sorted(sub.items(), key=lambda x: -x[1])
rank = {w: i for i, (w, _) in enumerate(sub_sorted)}
def sub_freq(w):
    r = rank.get(w.lower())
    if r is None: return None
    return dict_sorted[min(r, len(dict_sorted) - 1)]

def blend(w, f):
    s = sub_freq(w)
    if s is None: s = max(0, f - MISSING_PENALTY)
    return max(0, min(255, round((1 - W_SUB) * f + W_SUB * s)))

new = {w: blend(w, f) for w, f in words.items()}

# chybějící hovorová slova (jen malými, ověřená Hunspellem)
hun = Dictionary.from_files('cs')
lower_known = {w.lower() for w in words}
added = 0
extra = []
for w, c in sub_sorted:
    if c < ADD_MIN_COUNT: break
    if w in lower_known or not w.isalpha() or len(w) < 2: continue
    if hun.lookup(w):
        extra.append((w, sub_freq(w)))
        added += 1

with open('pekey_cs_blend.combined', 'w') as out:
    for l in lines:
        if l.startswith('dictionary='):
            out.write('dictionary=main:cs,locale=cs,description=Čeština (Pekey),date=%d,version=20\n' % int(time.time()))
        elif l.startswith(' word='):
            m = re.match(r' word=(.*?),f=(\d+)(.*)', l)
            out.write(' word=%s,f=%d%s\n' % (m.group(1), new[m.group(1)], m.group(3)))
        elif l:
            out.write(l + '\n')
    for w, f in extra:
        out.write(' word=%s,f=%d\n' % (w, f))
print('přidáno', added)
for w in ['rád','řád','proč','Přerov','sakra','sajga','drhne','druhé','denně','normálně','pánve','trochu','těžící']:
    print(w, words.get(w), '→', new.get(w))
