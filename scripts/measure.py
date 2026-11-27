"""Measure final native More materials and geometry at each platform's actual scale."""
from PIL import Image
from pathlib import Path
import json
root=Path(__file__).resolve().parents[1]
def sample(platform):
 im=Image.open(root/f'screenshots/{platform}/02-more.png').convert('RGB');scale=im.width/(411.4285714 if platform=='android' else 402)
 # Plain tile interiors are exact neutral303030; group continuous scanline runs.
 def runs(axis,fixed,lo,hi):
  hits=[]
  for v in range(round(lo*scale),round(hi*scale)):
   px=im.getpixel((v,round(fixed*scale)) if axis=='x' else (round(fixed*scale),v))
   if px==(48,48,48):
    if not hits or v-hits[-1][-1]>1:hits.append([v])
    else:hits[-1].append(v)
  return hits
 # Vertical central stroke at x90 avoids the label and low-left icon backing.
 vr=max(runs('y',130,90 if platform=='android' else 110,280 if platform=='android' else 310),key=len)
 # Use center of the first tile. Find left/right edges excluding corner curvature.
 y=vr[0]/scale+125
 hr=max(runs('x',y,0,205 if platform=='android' else 201),key=len)
 return {'canvas':'#%02X%02X%02X'%im.getpixel((round(205*scale),round(740*scale))),'tile_surface':'#303030','tile_width_dp':len(hr)/scale,'tile_height_dp':len(vr)/scale,'tile_left_dp':hr[0]/scale,'density_scale':scale,'note':'Uninterrupted neutral tile scanlines; radii can trim edge subpixels.'}
r={p:sample(p) for p in ['android','ios']}
(root/'artifacts/v11-raster-measurements.json').write_text(json.dumps(r,indent=2))
print(json.dumps(r,indent=2))
