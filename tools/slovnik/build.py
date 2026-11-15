"""
Sestaví přibalený český slovník Pekey (app/src/main/assets/dicts/main_cs.dict).

Zdroj: experimentální main_cs (Helium314/aosp-dictionaries, CC BY 4.0), rozbalený
přes decode.py do exp_cs.combined. Ponechá slova s četností aspoň 20, která projdou
českým Hunspellem (npm balíček dictionary-cs → cs.aff, cs.dic), a častá slova (≥ 60),
která Hunspell nezná, pokud nejsou v anglickém slovníku (en.combined). Dvojice slov
(předpověď) zůstávají jen mezi ponechanými slovy.

    pip install spylls
    python3 decode.py exp_cs.dict exp_cs.combined
    python3 decode.py main_en.dict en.combined
    python3 build.py
    curl -O https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/cs/cs_full.txt
    python3 blend.py
    pip install wordfreq        # + korpusy UD (FicTree, CAC, CLTT, PUD, Poetry) do ud/
    python3 base_v3.py
    java -jar dicttool_aosp.jar makedict -s pekey_cs_v3.combined -d main_cs.dict -2
"""
import re
import time
from spylls.hunspell import Dictionary

MIN_FREQ = 20
MIN_FREQ_UNKNOWN = 60

hun = Dictionary.from_files('cs')
en = set()
for line in open('en.combined'):
    if line.startswith(' word='):
        en.add(re.match(r' word=(.*?),f=', line).group(1).lower())

keep = set()
for line in open('exp_cs.combined'):
    if line.startswith(' word='):
        m = re.match(r' word=(.*?),f=(\d+)', line)
        w, f = m.group(1), int(m.group(2))
        if f < MIN_FREQ:
            continue
        if hun.lookup(w) or (f >= MIN_FREQ_UNKNOWN and w.lower() not in en):
            keep.add(w)

with open('pekey_cs.combined', 'w') as out:
    cur = False
    for line in open('exp_cs.combined'):
        if line.startswith('dictionary='):
            out.write('dictionary=main:cs,locale=cs,description=Čeština (Pekey),date=%d,version=19\n'
                      % int(time.time()))
        elif line.startswith(' word='):
            cur = re.match(r' word=(.*?),f=', line).group(1) in keep
            if cur:
                out.write(line)
        elif line.startswith('  bigram='):
            if cur and re.match(r'  bigram=(.*?),f=', line).group(1) in keep:
                out.write(line)
print('slov:', len(keep))
