# Generuje 3D ikonu Pekey keyboard jako SVG (náhled) i jako Android vector drawable.
def rr(x,y,w,h,r):
    return (f"M{x+r},{y} h{w-2*r} a{r},{r} 0 0 1 {r},{r} v{h-2*r} a{r},{r} 0 0 1 -{r},{r} "
            f"h-{w-2*r} a{r},{r} 0 0 1 -{r},-{r} v-{h-2*r} a{r},{r} 0 0 1 {r},-{r} z")
S=12; G=6  # velikost klávesy, mezera
xs=[54-S*1.5-G, 54-S/2, 54+S/2+G]; ys=[33,49,65]
xs=[33,48,63]; ys=[31,46,61]; S=12
shadow=" ".join(rr(x+0.6,y+3.2,S,S,3.4) for y in ys for x in xs)
side=" ".join(rr(x,y+1.8,S,S,3.4) for y in ys for x in xs)
top=" ".join(rr(x,y,S,S-1.6,3.4) for y in ys for x in xs)
shine=" ".join(rr(x+2,y+1.2,S-4,2.2,1.1) for y in ys for x in xs)
cx=[x+S/2 for x in xs]; cy=[y+(S-1.6)/2 for y in ys]
P=f"M{cx[0]},{cy[2]+0.5} V{cy[0]} H{cx[1]+3} C{cx[2]+5},{cy[0]} {cx[2]+5},{cy[1]} {cx[1]+3},{cy[1]} H{cx[0]+3}"
def sh(dx,dy): return P  # same path, posunutí přes translate
parts=dict(shadow=shadow,side=side,top=top,shine=shine,P=P,dot=(cx[0],cy[2]+0.5))
import json; json.dump(parts,open('parts.json','w'))
svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">
<defs>
<clipPath id="c"><circle cx="54" cy="54" r="54"/></clipPath>
<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E9ECF2"/></linearGradient>
<linearGradient id="top" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3A3E48"/><stop offset="1" stop-color="#1A1C22"/></linearGradient>
<linearGradient id="blue" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6DB0FF"/><stop offset="1" stop-color="#1E5BE6"/></linearGradient>
</defs>
<g clip-path="url(#c)"><rect width="108" height="108" fill="url(#bg)"/>
<path d="{shadow}" fill="#000" fill-opacity="0.13"/>
<path d="{side}" fill="#0B0C10"/>
<path d="{top}" fill="url(#top)"/>
<path d="{shine}" fill="#FFF" fill-opacity="0.16"/>
<g transform="translate(0.8,2.6)"><path d="{P}" stroke="#000" stroke-opacity="0.22" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" fill="none"/><circle cx="{cx[0]}" cy="{cy[2]+0.5}" r="5" fill="#000" fill-opacity="0.22"/></g>
<g transform="translate(0,1.4)"><path d="{P}" stroke="#1440A8" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" fill="none"/><circle cx="{cx[0]}" cy="{cy[2]+0.5}" r="5" fill="#1440A8"/></g>
<path d="{P}" stroke="url(#blue)" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
<circle cx="{cx[0]}" cy="{cy[2]+0.5}" r="5" fill="url(#blue)"/>
<g transform="translate(-0.8,-1)"><path d="{P}" stroke="#FFF" stroke-opacity="0.45" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" fill="none"/></g>
</g></svg>'''
open('icon.svg','w').write(svg)
