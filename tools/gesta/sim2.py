exec(open('sim.py').read().split("test=")[0])
def classify2(user, p):
    radius=min(KEYW,KEYH)
    ul=length(user); ur=resample(user); un=norm(ur)
    best=[]
    for s in nearest(*user[0]):
        for e in nearest(*user[-1]):
            for i in index.get((s,e),[]):
                w,f,ip,il=info[i]
                if w.isupper() and len(w)>1: continue
                if abs(ul-il)>8.42*radius: continue
                r,n=prep(i)
                shape=float(np.sum(np.hypot(*(n-un).T)))
                sh=-0.5*(shape/11.04)**2
                loc=float(np.mean(np.abs(r-ur).sum(1)))/2
                lc=-0.5*(loc/(0.5109*radius))**2
                fr=p['ln']*math.log((f+1)/256)+p['lin']*f
                cov=0.0
                if p['cov']:
                    # každá klávesa slova musí ležet blízko tahu (v pořadí ~ přibližně)
                    dd=np.sqrt(((ip[:,None,:]-ur[None,:,:])**2).sum(2)).min(1)
                    cov=-0.5*float(np.sum((dd/(p['covs']*KEYW))**2))*p['cov']
                best.append((sh+lc+fr+cov,w))
    best.sort(reverse=True)
    return [w for _,w in best[:3]]
test="ahoj jak se máš dneska je venku hezky proč to sakra nejde jsem rád že to trochu funguje zítra večer přijdu domů později protože musím ještě nakoupit dobrý den dnes krásné počasí karlových varů michal".split()
import itertools
for noise in [0.15,0.25]:
    random.seed(2); np.random.seed(2)
    trials=[(w,gesture(w,noise)) for w in test for _ in range(4)]
    for name,p in {
        'ln1':dict(ln=1,lin=0,cov=0),
        'ln2':dict(ln=2,lin=0,cov=0),
        'ln3':dict(ln=3,lin=0,cov=0),
        'ln1_cov1':dict(ln=1,lin=0,cov=1,covs=0.5),
        'ln2_cov1':dict(ln=2,lin=0,cov=1,covs=0.5),
        'ln2_cov2':dict(ln=2,lin=0,cov=2,covs=0.5),
        'ln3_cov2':dict(ln=3,lin=0,cov=2,covs=0.5),
    }.items():
        ok=top3=0; bad=[]
        for w,g in trials:
            r=classify2(g,p)
            if r and r[0].lower()==w: ok+=1
            else: bad.append((w,r[0] if r else '-'))
            if w in [x.lower() for x in r]: top3+=1
        print(f'noise{noise} {name:10s} top1 {ok}/{len(trials)} top3 {top3}/{len(trials)} {bad[:8]}', flush=True)
