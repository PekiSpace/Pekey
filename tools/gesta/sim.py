# Simulace vlastního gestového enginu: umělá gesta se šumem přes klávesy, přesnost pro různé váhy.
# Potřebuje pekey_cs_blend.combined z tools/slovnik (cesta níže) a numpy.
import re, math, random, unicodedata, sys
import numpy as np
random.seed(1); np.random.seed(1)
# klávesnice podle screenshotu (px)
KEYW, KEYH = 97, 113
rows = [("qwertzuiop", 59, 107, 1880), ("asdfghjkl", 64, 119, 2003), ("yxcvbnm", 216, 108, 2128)]
C = {}
for letters, x0, step, y in rows:
    for i, ch in enumerate(letters): C[ch] = (x0 + i*step, y)
def base(ch):
    s = unicodedata.normalize('NFD', ch.lower())[0]
    return s if s in C else None
# slovník: top 150k z blend combined
words=[]
for l in open('/tmp/dictwork/pekey_cs_blend.combined'):
    if l.startswith(' word='):
        m=re.match(r' word=(.*?),f=(\d+)',l); words.append((m.group(1),int(m.group(2))))
words.sort(key=lambda x:-x[1]); words=words[:150000]
# sjednotit velikost písmen (přednost malým)
seen={}
for w,f in words:
    k=w.lower()
    if k not in seen or (w==k and seen[k][0]!=k): seen[k]=(w if k not in seen or w==k else seen[k][0], max(f, seen.get(k,(0,0))[1]))
words=list(seen.values())
SAMPLES=100
def letters(w):
    out=[]
    for ch in w:
        b=base(ch)
        if b: out.append(b)
    return out
def ideal(ls):
    pts=[]
    for i,c in enumerate(ls):
        if i>0 and c==ls[i-1]: continue
        pts.append(C[c])
    return np.array(pts,float)
def length(p): return float(np.sum(np.hypot(*np.diff(p,axis=0).T))) if len(p)>1 else 0.0
def resample(p,n=SAMPLES):
    if len(p)<2: return np.repeat(p[:1],n,axis=0)
    d=np.concatenate([[0],np.cumsum(np.hypot(*np.diff(p,axis=0).T))])
    if d[-1]==0: return np.repeat(p[:1],n,axis=0)
    t=np.linspace(0,d[-1],n)
    return np.stack([np.interp(t,d,p[:,0]),np.interp(t,d,p[:,1])],1)
def norm(p):
    mn=p.min(0); mx=p.max(0); side=max((mx-mn).max(),1e-5)
    return (p-(mn+mx)/2)/side
index={}
info=[]
for i,(w,f) in enumerate(words):
    ls=letters(w)
    if len(ls)<2 or len(ls)!=len([c for c in w if c.isalpha()]): continue
    ip=ideal(ls)
    info.append((w,f,ip,length(ip)))
    index.setdefault((ls[0],ls[-1]),[]).append(len(info)-1)
keys=list(C)
def nearest(x,y): return sorted(keys,key=lambda k:math.hypot(C[k][0]-x,C[k][1]-y))[:2]
cache={}
def prep(i):
    if i not in cache:
        r=resample(info[i][2]); cache[i]=(r,norm(r))
    return cache[i]
def classify(user, params):
    radius=min(KEYW,KEYH)
    ul=length(user); ur=resample(user); un=norm(ur)
    best=[]
    for s in nearest(*user[0]):
        for e in nearest(*user[-1]):
            for i in index.get((s,e),[]):
                w,f,ip,il=info[i]
                if abs(ul-il)>8.42*radius: continue
                r,n=prep(i)
                shape=float(np.sum(np.hypot(*(n-un).T)))
                sh=-0.5*(shape/params['shape_std'])**2
                if params['mode']=='old':
                    loc=float(np.mean(np.abs(r-ur).sum(1)))/2
                    lc=-0.5*(loc/(0.5109*radius))**2
                else:
                    msq=float(np.mean(np.sum((r-ur)**2,1)))
                    lc=-0.5*msq/(params['sigma']*KEYW)**2*params['K']
                fr=params['fw']*f if params['lin'] else math.log((f+1)/256)
                best.append((sh*params['sw']+lc+fr,w))
    best.sort(reverse=True)
    return [w for _,w in best[:3]]
def gesture(word, noise=0.22):
    ls=letters(word); ip=ideal(ls)
    pts=ip+np.random.normal(0,noise*KEYW,ip.shape)
    # zaoblení rohů (Chaikin) a hustší body
    for _ in range(2):
        q=[pts[0]]
        for a,b in zip(pts[:-1],pts[1:]): q+= [0.75*a+0.25*b,0.25*a+0.75*b]
        q.append(pts[-1]); pts=np.array(q)
    return resample(pts,60)
test="ahoj jak se máš dneska je venku hezky proč to sakra nejde jsem rád že to trochu funguje zítra večer přijdu domů později protože musím ještě nakoupit dobrý den dnes krásné počasí karlových varů michal".split()
test=[w for w in test if len(w)>=2]
configs={
 'old_ln': dict(mode='old',lin=False,fw=0,sw=1,shape_std=11.04),
 'old_lin0.1': dict(mode='old',lin=True,fw=0.1,sw=1,shape_std=11.04),
}
for K in [5,10,20]:
  for fw in [0.03,0.06,0.1]:
    configs[f'new_K{K}_fw{fw}']=dict(mode='new',lin=True,fw=fw,sw=1,shape_std=11.04,sigma=0.5,K=K)
trials=[(w,gesture(w)) for w in test for _ in range(4)]
for name,p in configs.items():
    ok=0; top3=0; bad=[]
    for w,g in trials:
        r=classify(g,p)
        if r and r[0].lower()==w: ok+=1
        else: bad.append((w,r[0] if r else '-'))
        if w in [x.lower() for x in r]: top3+=1
    print(f'{name:22s} top1 {ok}/{len(trials)}  top3 {top3}/{len(trials)}  {bad[:6]}')
    sys.stdout.flush()
