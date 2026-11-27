"""Measure native settled pixels, including the card edge rather than only CTA bounds."""
from pathlib import Path
from PIL import Image
import json
from verify_send_raster import measure
root=Path(__file__).resolve().parents[1];result={}
for platform in ['android','ios']:
 result[platform]={};scale=1080/411.4285714 if platform=='android' else 3
 for name in ['K-01-today-keyboard-open','K-07-today-keyboard-large']:
  p=root/f'screenshots/{platform}/{name}.png';im=Image.open(p).convert('RGB');x0=round(24*scale);x1=im.width-x0;span=x1-x0
  composer=next(y for y in range(round(260*scale),round(im.height*.78)) if sum(im.getpixel((x,y))==(48,48,48) for x in range(x0,x1))>span*.6)
  card=max(y for y in range(round(120*scale),composer) if sum(im.getpixel((x,y))==(33,33,33) for x in range(x0,x1))>100)
  gap=(composer-card-1)/scale;assert gap>=15,(platform,name,gap)
  result[platform][name]={'composer_top_px':composer,'card_bottom_px':card+1,'card_composer_gap_dp':gap}
 result[platform]['send_circle']=measure(root/f'screenshots/{platform}/K-01-today-keyboard-open.png',platform)
 # Native Today frame, only cropped; no illustration or UI pixels are repainted.
 im=Image.open(root/f'screenshots/{platform}/01-today.png').convert('RGB');x0=round(24*scale);x1=im.width-x0
 rows=[]
 for y in range(round(150*scale),round(650*scale)):
  count=sum(1 for x in range(x0,x1) if 20<=im.getpixel((x,y))[0]<=38 and max(im.getpixel((x,y)))-min(im.getpixel((x,y)))<=2)
  if count>(x1-x0)*.5:rows.append(y)
 assert rows
 im.crop((x0-6,min(rows)-12,x1+6,max(rows)+12)).save(root/f'screenshots/{platform}/K-05-rear-card-closeup.png')
result['passed']=True;(root/'artifacts/v11-keyboard-raster.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
