"""Native v7/v8 comparisons, per-platform stack crops and review sheets."""
from pathlib import Path
from PIL import Image
import sys
ROOT=Path(__file__).resolve().parents[1];number=sys.argv[1];folder=ROOT/'compare'/('v8-pass'+number)
def pair(left,right,path):
 right=right.convert('RGB');left=left.convert('RGB')
 left=left.resize((right.width,round(left.height*right.width/left.width)));h=max(left.height,right.height)
 out=Image.new('RGB',(left.width+right.width,h));out.paste(left);out.paste(right,(left.width,0));out.save(path)
def stack(im):
 # First substantial approval surface, ignoring the greeting and round top corners.
 im=im.convert('RGB');scale=im.width/(411.4285714 if im.width==1080 else 402)
 active=[]
 for y in range(round(130*scale),round(650*scale)):
  px=im.getpixel((round(24*scale),y))
  if max(abs(c-33) for c in px)<=2:active.append(y)
 assert active,'Approval stack surface missing'
 groups=[]
 for y in active:
  if not groups or y-groups[-1][-1]>2:groups.append([y])
  else:groups[-1].append(y)
 run=next(g for g in groups if g[-1]-g[0]>80*scale)
 top,bottom=run[0],run[-1]
 return im.crop((round(16*scale),max(0,top-round(28*scale)),round(395*scale),min(im.height,bottom+round(88*scale))))
for platform in ['android','ios']:
 old=ROOT/'v7-snapshot/screenshots'/platform;new=folder if platform=='android' else folder/'ios';dest=folder/platform;dest.mkdir(exist_ok=True,parents=True)
 for i,name in enumerate(['today','more','sidebar','settings','partner'],1):
  a=Image.open(old/f'{i:02d}-{name}.png');b=Image.open(new/f'{i:02d}-{name}.png');pair(a,b,dest/f'v7-vs-v8-{i:02d}.png')
  if platform=='android':pair(a,b,ROOT/f'compare/v7-vs-v8-{i:02d}.png')
 a=stack(Image.open(old/'01-today.png'));b=stack(Image.open(new/'01-today.png'));pair(a,b,dest/'today-stack-closeup.png')
 if platform=='android':pair(a,b,ROOT/'compare/today-stack-closeup.png')
 else:pair(a,b,ROOT/'compare/today-stack-closeup-ios.png')
 # Full More close-up preserves the information hierarchy, not just one tile.
 im=Image.open(new/'02-more.png');scale=im.width/(411.4285714 if im.width==1080 else 402);offset=0 if platform=='android' else 24
 im.crop(tuple(round(v*scale) for v in [14,104+offset,397,588+offset])).save(dest/'more-closeup.png')
 contact=Image.new('RGB',(2100,650),'black')
 for i,f in enumerate(sorted(new.glob('0[1-7]-*.png'))):
  im=Image.open(f);im.thumbnail((300,650));contact.paste(im,(i*300,0))
 contact.save(dest/'review-all-seven.png')
print('Compared pass',number)
