"""Six actual native video frames in each direction, selected by the moving chip."""
from pathlib import Path
from PIL import Image,ImageDraw
import subprocess,json,sys
root=Path(__file__).resolve().parents[1];platform=sys.argv[1];video=Path(sys.argv[2]);fps=60;w=h=160
raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(video),'-t','30','-vf','fps=60,scale=402:-1,crop=160:160:121:40','-pix_fmt','rgb24','-f','rawvideo','pipe:1']);size=w*h*3;values=[]
for i in range(len(raw)//size):
 data=raw[i*size:(i+1)*size];rows=[]
 for y in range(h):
  row=data[y*w*3:(y+1)*w*3];count=sum(1 for r,g,b in zip(row[::3],row[1::3],row[2::3]) if abs(r-42)<=5 and abs(g-42)<=5 and abs(b-42)<=5)
  if count>45:rows.append(y)
 values.append(sum(rows)/len(rows)+40 if len(rows)>12 else None)
valid=[v for v in values if v is not None];lo=min(valid);hi=max(valid);assert hi-lo>35,(lo,hi)
p=[None if v is None else max(0,min(1,(hi-v)/(hi-lo))) for v in values]
start=next(i for i,v in enumerate(p) if v is not None and v>.06);end=next(i for i in range(start,len(p)) if p[i] is not None and p[i]>.98);close=next(i for i in range(end+6,len(p)) if p[i] is not None and p[i]<.94);closed=next(i for i in range(close,len(p)) if p[i] is not None and p[i]<.02)
report={'platform':platform,'video':str(video),'fps':fps,'chip_closed_center_dp':hi,'chip_open_center_dp':lo,'transitions':{}}
for direction,a,b in [('open',max(0,start-5),end+6),('close',max(end,close-5),closed+6)]:
 # Keep the resting endpoint inside this transition. A subsequent test may
 # immediately focus the field again; that belongs to a separate opening.
 next_transition=next((i for i in range(b+1,len(p)) if p[i] is not None and (p[i]>.06 if direction=='close' else p[i]<.94)),len(p))
 last=min(b+12,next_transition-2,len(p)-1)
 times=[max(0,a-10)/fps,a/fps,(a+(b-a)/3)/fps,(a+2*(b-a)/3)/fps,b/fps,last/fps];sheet=Image.new('RGB',(6*260,610),'#111111');draw=ImageDraw.Draw(sheet)
 for j,t in enumerate(times):
  out=root/'.native-build'/f'v11-{platform}-{direction}-{j}.png';subprocess.run(['ffmpeg','-v','error','-ss',str(t),'-i',str(video),'-frames:v','1','-y',str(out)],check=True);im=Image.open(out).convert('RGB')
  if platform=='android' and im.height==2400:im=im.crop((0,30,im.width,2370))
  im.thumbnail((260,570));sheet.paste(im,(j*260,32));draw.text((j*260+6,8),f'{round((t-times[0])*1000)} ms / frame {j+1}',fill='white')
 filename=f'keyboard-transition-{direction}-contact-sheet'+('-ios' if platform=='ios' else '')+'.png';sheet.save(root/'compare'/filename);report['transitions'][direction]={'video_times_seconds':times,'sampled_duration_ms':round((times[-1]-times[0])*1000),'last_frame_normalized_chip_position':p[round(times[-1]*fps)],'contact_sheet':'compare/'+filename}
(root/'artifacts'/f'v11-keyboard-motion-{platform}.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
