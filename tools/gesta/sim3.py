exec(open('sim.py').read().split("test=")[0])
def nearestN(x,y,k): return sorted(keys,key=lambda q:math.hypot(C[q][0]-x,C[q][1]-y))[:k]
def classify3(user, p):
    radius=min(KEYW,KEYH)
    ul=length(user); ur=resample(user); un=norm(ur)
    best=[]
    for s in nearestN(*user[0],p['nk']):
        for e in nearestN(*user[-1],p['nk']):
            for i in index.get((s,e),[]):
                w,f,ip,il=info[i]
                if w.isupper() and len(w)>1: continue
                if abs(ul-il)>8.42*radius: continue
                r,n=prep(i)
                shape=float(np.sum(np.hypot(*(n-un).T)))
                sh=-0.5*(shape/11.04)**2
                loc=float(np.mean(np.abs(r-ur).sum(1)))/2
                lc=-0.5*(loc/(0.5109*radius))**2*p['lw']
                fr=2.5*math.log((f+1)/256)
                best.append((sh+lc+fr,w))
    best.sort(reverse=True)
    return [w for _,w in best[:3]]
def gesture2(word, noise, dy, over):
    g=gesture(word,noise)
    g=g+np.array([0,dy*KEYH])
    if over:
        v=g[-1]-g[-4]; g=np.vstack([g, g[-1]+v*over])
    return g
test="ahoj jak se máš dneska je venku hezky proč to sakra nejde jsem rád že to trochu funguje zítra večer přijdu domů později protože musím ještě nakoupit dobrý den dnes krásné počasí karlových varů michal".split()
for dy,over in [(0,0),(0.3,0),(0.3,1.5)]:
    random.seed(3); np.random.seed(3)
    trials=[(w,gesture2(w,0.2,dy,over)) for w in test for _ in range(3)]
    for name,p in {'nk2':dict(nk=2,lw=1),'nk3':dict(nk=3,lw=1),'nk4':dict(nk=4,lw=1),'nk4_lw3':dict(nk=4,lw=3),'nk3_lw3':dict(nk=3,lw=3)}.items():
        ok=top3=0; bad=[]
        for w,g in trials:
            r=classify3(g,p)
            if r and r[0].lower()==w: ok+=1
            else: bad.append((w,r[0] if r else '-'))
            if w in [x.lower() for x in r]: top3+=1
        print(f'dy{dy} over{over} {name:8s} top1 {ok}/{len(trials)} top3 {top3}/{len(trials)} {bad[:6]}', flush=True)
