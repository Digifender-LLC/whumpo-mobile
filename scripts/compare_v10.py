"""Same-platform native v9/v10 pairs and compact design review sheets."""
from pathlib import Path
from PIL import Image,ImageDraw
import sys
root=Path(__file__).resolve().parents[1];number=sys.argv[1];folder=root/'compare'/('v10-pass'+number)
def pair(a,b,path):
 a=a.convert('RGB');b=b.convert('RGB');a=a.resize((b.width,round(a.height*b.width/a.width)));out=Image.new('RGB',(a.width+b.width,max(a.height,b.height)));out.paste(a);out.paste(b,(a.width,0));out.save(path)
scenes=[('01-today.png','01-today.png','01-today'),('L2-12-partner-composer.png','P-01-typing-send-button.png','02-typing'),('L2-01-reviews.png','P-03-reviews-grouped.png','03-reviews'),('04-settings.png','04-settings.png','04-settings'),('L2-06-account.png','L2-06-account.png','05-account'),('L2-03-website.png','L2-03-website.png','06-website')]
for platform in ['android','ios']:
 new=folder/platform;old=root/'v9-snapshot/screenshots'/platform
 if not (new/'L2-03-website.png').exists():continue
 for before,after,name in scenes:
  source=root/'review-v9-sonnet/r02_typing.png' if platform=='android' and name=='02-typing' else root/'review-v9-sonnet/r06_back1.png' if platform=='android' and name=='03-reviews' else old/before
  if platform=='ios' and name=='02-typing':after='P-02-partner-typing.png'
  if (new/after).exists():
   a=Image.open(source);b=Image.open(new/after);pair(a,b,new/f'v9-vs-v10-{name}.png');pair(a,b,root/f'compare/v9-vs-v10-{name}{"-ios" if platform=="ios" else ""}.png')
 for label,pattern in [('main','0[1-7]-*.png'),('L2','L2-[0-9][0-9]-*.png'),('polish','P-[0-9][0-9]-*.png'),('large','A-[0-9][0-9]-*.png')]:
  files=sorted(new.glob(pattern))
  if not files:continue
  cols=min(6,len(files));contact=Image.new('RGB',(cols*300,((len(files)+cols-1)//cols)*680),'#111111');d=ImageDraw.Draw(contact)
  for j,f in enumerate(files):
   im=Image.open(f);im.thumbnail((300,650));x=(j%cols)*300;y=(j//cols)*680;contact.paste(im,(x,y+25));d.text((x+4,y+4),f.stem,fill='white')
  contact.save(new/(label+'-review.png'))
 print('Compared',platform,'pass',number)
