"""v8/v9 same-platform native comparisons and review sheets for all captured pages."""
from pathlib import Path
from PIL import Image,ImageDraw
import sys
root=Path(__file__).resolve().parents[1];number=sys.argv[1];folder=root/'compare'/('v9-pass'+number)
def pair(a,b,path):
 a=a.convert('RGB');b=b.convert('RGB');a=a.resize((b.width,round(a.height*b.width/a.width)));out=Image.new('RGB',(a.width+b.width,max(a.height,b.height)));out.paste(a);out.paste(b,(a.width,0));out.save(path)
for platform in ['android','ios']:
 new=folder/platform;old=root/'v8-snapshot/screenshots'/platform
 if not new.exists():continue
 for i,name in enumerate(['today','more','sidebar','settings','partner'],1):
  a=Image.open(old/f'{i:02d}-{name}.png');b=Image.open(new/f'{i:02d}-{name}.png');pair(a,b,new/f'v8-vs-v9-{i:02d}.png')
  if platform=='android':pair(a,b,root/f'compare/v8-vs-v9-{i:02d}.png')
  if i==3:
   # Same pixel scale; crop top through group boundary, keeping org header and pins.
   scale=b.width/(411.4285714 if platform=='android' else 402)
   box=tuple(round(x*scale) for x in [14,65,397,500]);pair(a.crop(box),b.crop(box),new/'sidebar-closeup.png')
   pair(a.crop(box),b.crop(box),root/('compare/sidebar-closeup.png' if platform=='android' else 'compare/sidebar-closeup-ios.png'))
 files=sorted(new.glob('0[1-7]-*.png'));contact=Image.new('RGB',(2100,650),'black')
 for j,f in enumerate(files):
  im=Image.open(f);im.thumbnail((300,650));contact.paste(im,(j*300,0))
 contact.save(new/'main-review.png')
 files=sorted(new.glob('L2-[0-9][0-9]-*.png'));contact=Image.new('RGB',(1800,((len(files)+5)//6)*680),'black');d=ImageDraw.Draw(contact)
 for j,f in enumerate(files):
  im=Image.open(f);im.thumbnail((300,650));x=(j%6)*300;y=(j//6)*680;contact.paste(im,(x,y+25));d.text((x+4,y+4),f.stem,fill='white')
 contact.save(new/'L2-review.png')
 print('Compared',platform,'pass',number,'screens',len(files)+7)
