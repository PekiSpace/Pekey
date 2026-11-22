#!/usr/bin/env python3
"""
Doplní do slovníku AOSP (formát v2, verze 202) dvojice slov (bigramy) spočítané z textu.

Použití:
  python3 tools/dict_bigrams.py VSTUP.dict VYSTUP.dict KORPUS.conllu[:VÁHA] [KORPUS2.conllu[:VÁHA] ...]

VÁHA (výchozí 1) násobí počty dvojic ze souboru: mluvená řeč a beletrie se blíží psaní
v mobilu víc než noviny, proto mají dostat víc.

Korpus je CoNLL-U (Universal Dependencies). Počítají se dvojice sousedních slov ve větě;
interpunkce a čísla kontext přeruší (stejně jako v klávesnici). Slovní zásoba a četnosti
slov zůstávají ze vstupního slovníku, mění se jen seznamy dvojic. Stávající dvojice se
zachovají beze změny, nové z korpusu se přidají s hodnotou nejvýš NEW_MAX.
"""
import math
import sys
from collections import Counter, defaultdict

sys.setrecursionlimit(100000)

MAGIC = 0x9BC13AFE
F_CHILDREN = 0xC0
F_MULTI = 0x20
F_TERMINAL = 0x10
F_SHORTCUTS = 0x08
F_BIGRAMS = 0x04
F_NOT_A_WORD = 0x02
F_OFFENSIVE = 0x01

MAX_PER_WORD = 24      # nejvíc navázaných slov u jednoho slova
NEW_MAX = 10           # nejvyšší hodnota (0–15) nové dvojice z korpusu
MIN_LIFT = 2.0         # dvojice musí být aspoň 2× častější než náhoda
MIN_COUNT = 3          # řídké dvojice (vážený počet pod 3) se neberou: šum


class Node:
    __slots__ = ("flags", "chars", "freq", "children", "shortcuts", "bigrams", "word", "pos")

    def __init__(self):
        self.children = None   # list[Node] nebo None
        self.bigrams = []      # list[(Node, 0..15)]
        self.shortcuts = b""


def parse(d):
    assert int.from_bytes(d[0:4], "big") == MAGIC, "není slovník AOSP"
    ver = int.from_bytes(d[4:6], "big")
    assert ver in (2, 202), f"nepodporovaná verze {ver}"
    root = int.from_bytes(d[8:12], "big")
    by_pos = {}
    raw_bigrams = []

    def arr(p, prefix):
        c = d[p]; p += 1
        if c & 0x80:
            c = ((c & 0x7F) << 8) | d[p]; p += 1
        out = []
        for _ in range(c):
            n = Node()
            start = p
            fl = d[p]; p += 1
            chars = []
            while True:
                b = d[p]
                if fl & F_MULTI and b == 0x1F:
                    p += 1
                    break
                if b < 0x20:
                    cp = (b << 16) | (d[p + 1] << 8) | d[p + 2]; p += 3
                else:
                    cp = b; p += 1
                chars.append(cp)
                if not fl & F_MULTI:
                    break
            n.flags = fl
            n.chars = chars
            n.word = prefix + "".join(map(chr, chars))
            n.freq = None
            if fl & F_TERMINAL:
                n.freq = d[p]; p += 1
            ch = 0
            t = fl & F_CHILDREN
            if t == 0x40:
                ch = p + d[p]; p += 1
            elif t == 0x80:
                ch = p + int.from_bytes(d[p:p + 2], "big"); p += 2
            elif t == 0xC0:
                ch = p + int.from_bytes(d[p:p + 3], "big"); p += 3
            if fl & F_SHORTCUTS:
                size = int.from_bytes(d[p:p + 2], "big")
                n.shortcuts = d[p:p + size]
                p += size
            if fl & F_BIGRAMS:
                while True:
                    bf = d[p]; p += 1
                    sz = {0x10: 1, 0x20: 2, 0x30: 3}.get(bf & 0x30, 0)
                    ap = p
                    off = int.from_bytes(d[p:p + sz], "big"); p += sz
                    if bf & 0x40:
                        off = -off
                    raw_bigrams.append((n, ap + off, bf & 0x0F))
                    if not bf & 0x80:
                        break
            by_pos[start] = n
            out.append(n)
            if ch:
                n.children = arr(ch, n.word)
        return out

    roots = arr(root, "")
    for n, target, f in raw_bigrams:
        n.bigrams.append((by_pos[target], f))
    return d[:root], roots


def encode_chars(chars):
    out = bytearray()
    for cp in chars:
        if 0x20 <= cp <= 0xFF:
            out.append(cp)
        else:
            out += cp.to_bytes(3, "big")
    if len(chars) > 1:
        out.append(0x1F)
    return bytes(out)


def serialize(header, roots):
    # pořadí polí: kořen, pak do hloubky; dětské pole je vždy za rodičem (kladný posun)
    arrays = []

    def collect(a):
        arrays.append(a)
        for n in a:
            if n.children:
                collect(n.children)
    collect(roots)

    def count_size(a):
        return 1 if len(a) < 128 else 2

    def node_size(n):
        s = 1 + len(encode_chars(n.chars))
        if n.freq is not None:
            s += 1
        if n.children:
            s += 3
        s += len(n.shortcuts)
        s += 4 * len(n.bigrams)
        return s

    pos = len(header)
    array_pos = {}
    for a in arrays:
        array_pos[id(a)] = pos
        pos += count_size(a)
        for n in a:
            n.pos = pos
            pos += node_size(n)

    out = bytearray(header)
    for a in arrays:
        assert len(out) == array_pos[id(a)]
        c = len(a)
        if c < 128:
            out.append(c)
        else:
            out += bytes([0x80 | (c >> 8), c & 0xFF])
        for n in a:
            assert len(out) == n.pos
            fl = n.flags & (F_MULTI | F_TERMINAL | F_SHORTCUTS | F_NOT_A_WORD | F_OFFENSIVE)
            if len(n.chars) > 1:
                fl |= F_MULTI
            if n.children:
                fl |= 0xC0
            if n.bigrams:
                fl |= F_BIGRAMS
            out.append(fl)
            out += encode_chars(n.chars)
            if n.freq is not None:
                out.append(n.freq)
            if n.children:
                ap = len(out)
                off = array_pos[id(n.children)] - ap
                assert 0 < off < (1 << 24)
                out += off.to_bytes(3, "big")
            out += n.shortcuts
            for i, (t, f) in enumerate(n.bigrams):
                bf = 0x30 | (f & 0x0F)
                if i < len(n.bigrams) - 1:
                    bf |= 0x80
                ap = len(out) + 1
                off = t.pos - ap
                if off < 0:
                    bf |= 0x40
                    off = -off
                assert off < (1 << 24)
                out.append(bf)
                out += off.to_bytes(3, "big")
    return bytes(out)


def all_nodes(roots):
    stack = list(roots)
    while stack:
        n = stack.pop()
        yield n
        if n.children:
            stack.extend(n.children)


def is_word(tok):
    return any(c.isalpha() for c in tok) and all(c.isalpha() or c in "'-’" for c in tok)


def corpus_pairs(paths):
    """Dvojice sousedních slov ve větách (forma z CoNLL-U, víceslovné tokeny jako celek)."""
    pairs = Counter()
    sentences = 0
    tokens = 0
    for spec in paths:
        path, _, w = spec.partition(":")
        weight = int(w) if w else 1
        with open(path, encoding="utf-8") as f:
            prev = None
            skip_to = 0
            for line in f:
                line = line.rstrip("\n")
                if not line:
                    prev = None
                    skip_to = 0
                    sentences += 1
                    continue
                if line.startswith("#"):
                    continue
                cols = line.split("\t")
                if len(cols) < 2:
                    continue
                tid = cols[0]
                if "." in tid:
                    continue
                if "-" in tid:
                    a, b = tid.split("-")
                    skip_to = int(b)
                    form = cols[1]
                else:
                    i = int(tid)
                    if i <= skip_to:
                        continue
                    form = cols[1]
                tokens += 1
                if is_word(form):
                    if prev is not None:
                        pairs[(prev, form)] += weight
                    prev = form
                else:
                    prev = None
    return pairs, sentences, tokens


def main():
    src, dst, *corpora = sys.argv[1:]
    data = open(src, "rb").read()
    header, roots = parse(data)

    # kontrola: přepis beze změn musí dát stejný strom
    check = serialize(header, roots)
    h2, r2 = parse(check)
    a = sorted((n.word, n.freq, tuple(sorted((t.word, f) for t, f in n.bigrams))) for n in all_nodes(roots) if n.freq is not None)
    b = sorted((n.word, n.freq, tuple(sorted((t.word, f) for t, f in n.bigrams))) for n in all_nodes(r2) if n.freq is not None)
    assert a == b, "přepis slovníku nesedí"
    print("kontrola přepisu: OK")

    words = {}
    for n in all_nodes(roots):
        if n.freq is not None and not n.flags & (F_NOT_A_WORD | F_OFFENSIVE):
            words[n.word] = n
    old_total = sum(len(n.bigrams) for n in words.values())

    pairs, sentences, tokens = corpus_pairs(corpora)
    print(f"korpus: {sentences} vět, {tokens} tokenů, {len(pairs)} různých dvojic")

    def source(w):
        # předchozí slovo hledá klávesnice malými, když ho slovník tak zná
        lw = w.lower()
        if lw in words:
            return words[lw]
        return words.get(w)

    def target(w, sentence_start):
        if w in words and not (sentence_start and w[:1].isupper() and w.lower() in words):
            return words[w]
        return words.get(w.lower())

    grouped = defaultdict(Counter)
    tcount = Counter()
    for (p, w), c in pairs.items():
        s = source(p)
        t = target(w, False)
        if s is None or t is None:
            continue
        grouped[s][t] += c
        tcount[t] += c
    total_pairs = sum(tcount.values())

    added = 0
    for s, succ in grouped.items():
        n_s = sum(succ.values())
        # síla dvojice = kolikrát častěji slovo po [s] následuje, než je běžné (lift);
        # „se, na, v“ jsou častá všude, slovník je i tak nabídne podle četnosti slov
        scored = []
        for t, c in succ.items():
            if c < MIN_COUNT:
                continue
            lift = (c / n_s) / (tcount[t] / total_pairs)
            if lift < MIN_LIFT:
                continue
            scored.append((t, c * math.log2(lift), lift))
        scored.sort(key=lambda x: -x[1])
        top = scored[:MAX_PER_WORD]
        if not top:
            continue
        new = {}
        for t, _, lift in top:
            new[t] = max(0, min(15, round(2 * math.log2(lift))))
        # stávající dvojice zůstávají, jak jsou; nové se přidají jen pod ně (strop NEW_MAX),
        # aby z novin nepřebily vyladěné pořadí (budu → mít, dělat… ne budu → v, na)
        merged = {t: f for t, f in s.bigrams}
        for t, f in new.items():
            if t not in merged:
                added += 1
                merged[t] = min(f, NEW_MAX)
        s.bigrams = sorted(merged.items(), key=lambda x: -x[1])

    out = serialize(header, roots)
    h3, r3 = parse(out)
    total = sum(len(n.bigrams) for n in all_nodes(r3))
    open(dst, "wb").write(out)
    print(f"dvojic: {old_total} → {total} (+{added}), velikost {len(data)} → {len(out)} B")


if __name__ == "__main__":
    main()
