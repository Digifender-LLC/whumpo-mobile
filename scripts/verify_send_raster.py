"""Measure the actual gold send face; a sharp gold square fails area/corner checks."""
from pathlib import Path
from PIL import Image
from collections import deque
import json,sys
root=Path(__file__).resolve().parents[1]
def measure(file,platform):
 im=Image.open(file).convert('RGB');scale=im.width/(411.4285714 if platform=='android' else 402)
 # Restrict to right-hand composer zone; reject wider card CTAs by component width.
 pix=im.load();x0=int(im.width*.68);y0=int(im.height*.27)
 points={(x,y) for y in range(y0,im.height) for x in range(x0,im.width) if max(abs(pix[x,y][i]-[224,190,120][i]) for i in range(3))<=3}
 matches=[]
 while points:
  seed=points.pop();q=deque([seed]);component=[seed]
  while q:
   x,y=q.popleft()
   for pt in [(x-1,y),(x+1,y),(x,y-1),(x,y+1)]:
    if pt in points:points.remove(pt);q.append(pt);component.append(pt)
  xs=[p[0] for p in component];ys=[p[1] for p in component];w=max(xs)-min(xs)+1;h=max(ys)-min(ys)+1
  if 35<=w/scale<=43 and 35<=h/scale<=43:
   fill=len(component)/(w*h);r={'bbox':[min(xs),min(ys),max(xs)+1,max(ys)+1],'diameter_dp':[w/scale,h/scale],'radius_dp':(w+h)/4/scale,'gold_area_fraction':fill}
   assert abs(w-h)<=2,r
   assert .62<fill<.82,('Square or clipped send face',r)
   cx=(min(xs)+max(xs))//2;cy=(min(ys)+max(ys))//2
   right=max(x for x in range(max(xs)+1,im.width) if pix[x,cy]==(48,48,48))
   bottom=next(y for y in range(max(ys)+2,min(im.height,max(ys)+round(20*scale))) if pix[cx,y]!=(48,48,48))
   r['right_inset_dp']=(right-max(xs))/scale;r['bottom_inset_dp']=(bottom-max(ys))/scale
   assert 6<=r['right_inset_dp']<=10,r
   assert 6<=r['bottom_inset_dp']<=10,r
   r['passed']=True;matches.append(r)
 assert len(matches)==1,(file,matches)
 return matches[0]
if __name__=='__main__':
 platform=sys.argv[1];file=root/'screenshots'/platform/'P-01-typing-send-button.png';r=measure(file,platform);(root/'artifacts'/f'v10-send-raster-{platform}.json').write_text(json.dumps(r,indent=2));print(json.dumps(r))
