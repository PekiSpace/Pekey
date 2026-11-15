"""Rozbalí slovník AOSP v2 (verze 202) do formátu .combined včetně dvojic (bigramů)."""
import sys
sys.setrecursionlimit(10000)
d = open(sys.argv[1], 'rb').read()
out = sys.argv[2]
assert d[0:4] == bytes([0x9B,0xC1,0x3A,0xFE])
ver = (d[4]<<8)|d[5]
hsize = int.from_bytes(d[8:12],'big')
# hlavička: páry klíč/hodnota (řetězce ukončené 0x1F)
p = 12; header = {}
def rstr(p):
    s=[]
    while True:
        b=d[p]
        if b==0x1F: return ''.join(s), p+1
        if b<0x20:
            cp=(b<<16)|(d[p+1]<<8)|d[p+2]; p+=3
        else:
            cp=b; p+=1
        s.append(chr(cp))
while p < hsize:
    k,p=rstr(p); v,p=rstr(p); header[k]=v
nodes = {}   # pos -> [word, freq, flags, bigrams[(addr,f4)]]
def array(start, prefix):
    p=start
    count=d[p]; p+=1
    if count & 0x80: count=((count&0x7F)<<8)|d[p]; p+=1
    for _ in range(count):
        nodepos=p
        flags=d[p]; p+=1
        chars=[]
        while True:
            b=d[p]
            if (flags&0x20) and b==0x1F: p+=1; break
            if b<0x20: cp=(b<<16)|(d[p+1]<<8)|d[p+2]; p+=3
            else: cp=b; p+=1
            chars.append(chr(cp))
            if not (flags&0x20): break
        word=prefix+''.join(chars)
        freq=-1
        if flags&0x10: freq=d[p]; p+=1
        child=0
        c=flags&0xC0
        if c==0x40: child=p+d[p]; p+=1
        elif c==0x80: child=p+((d[p]<<8)|d[p+1]); p+=2
        elif c==0xC0: child=p+((d[p]<<16)|(d[p+1]<<8)|d[p+2]); p+=3
        if flags&0x08: p+=(d[p]<<8)|d[p+1]
        bigrams=[]
        if flags&0x04:
            while True:
                bf=d[p]; p+=1
                size={0x10:1,0x20:2,0x30:3}.get(bf&0x30,0)
                addrpos=p
                off=int.from_bytes(d[p:p+size],'big'); p+=size
                if bf&0x40: off=-off
                bigrams.append((addrpos+off, bf&0x0F))
                if not bf&0x80: break
        if freq>=0:
            nodes[nodepos]=(word,freq,flags,bigrams)
        if child: array(child, word)
array(hsize, '')
print('slov', len(nodes), 'hlavička', header, file=sys.stderr)
def recon(uni, b4):
    step=(255-uni)/(1.5+15)
    return int(uni+step*(b4+1.0))
with open(out,'w') as f:
    f.write('dictionary=%s,locale=%s,description=%s,date=%s,version=%s\n' % (
        header.get('dictionary','main:cs'), header.get('locale','cs'),
        header.get('description',''), header.get('date','0'), header.get('version','1')))
    for pos,(w,fr,fl,bg) in nodes.items():
        extra=''
        if fl&0x02: extra+=',not_a_word=true'
        if fl&0x01: extra+=',possibly_offensive=true'
        f.write(' word=%s,f=%d%s\n' % (w,fr,extra))
        for addr,b4 in bg:
            t=nodes.get(addr)
            if t is None: continue
            f.write('  bigram=%s,f=%d\n' % (t[0], recon(t[1],b4)))
