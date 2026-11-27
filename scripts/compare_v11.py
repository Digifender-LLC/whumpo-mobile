"""Same-platform v10 baseline / native v11 pairs plus honest pass contacts."""
from pathlib import Path
from PIL import Image,ImageDraw
import sys
root=Path(__file__).resolve().parents[1];number=sys.argv[1];folder=root/'compare'/('v11-final' if number=='final' else 'v11-pass'+number)
def pair(a,b,path):
 a=a.convert('RGB');b=b.convert('RGB');a=a.resize((b.width,round(a.height*b.width/a.width)));out=Image.new('RGB',(a.width+b.width,max(a.height,b.height)));out.paste(a);out.paste(b,(a.width,0));out.save(path)
scenes=[('P-01-typing-send-button.png','K-01-today-keyboard-open.png','today-keyboard'),('01-today.png','01-today.png','today-closed'),('04-settings.png','04-settings.png','settings')]
for platform in ['android','ios']:
 new=folder/platform;old=root/'v10-snapshot/screenshots'/platform
 if not new.exists():continue
 for before,after,name in scenes:
  if (new/after).exists():
   pair(Image.open(old/before),Image.open(new/after),new/f'v10-vs-v11-{name}.png');pair(Image.open(old/before),Image.open(new/after),root/f'compare/v10-vs-v11-{name}{"-ios" if platform=="ios" else ""}.png')
 for label,pattern in [('main','0[1-7]-*.png'),('L2','L2-[0-9][0-9]-*.png'),('keyboard','K-[0-9][0-9]-*.png'),('large','A-[0-9][0-9]-*.png')]:
  files=sorted(new.glob(pattern))
  if not files:continue
  cols=min(6,len(files));contact=Image.new('RGB',(cols*300,((len(files)+cols-1)//cols)*680),'#111111');d=ImageDraw.Draw(contact)
  for j,f in enumerate(files):
   im=Image.open(f);im.thumbnail((300,650));x=(j%cols)*300;y=(j//cols)*680;contact.paste(im,(x,y+25));d.text((x+4,y+4),f.stem,fill='white')
  contact.save(new/(label+'-review.png'))
 print('Compared',platform,'pass',number)
