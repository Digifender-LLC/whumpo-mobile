"""Compare actual same-tab motion against system animator scale zero, using console frames."""
from device import *
from emulator_console import console
import json

os.chdir(ROOT)
adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True);click('Today',True)

def sample(label,scale):
 adb('shell','settings','put','global','animator_duration_scale',scale);adb('shell','settings','put','global','transition_animation_scale',scale);time.sleep(2)
 path=ROOT/f'artifacts/motion-{label}.webm'
 assert 'KO' not in console(f'screenrecord start --fps 60 --time-limit 10 --bit-rate 4M {path}')
 time.sleep(1)
 for _ in range(4):tap(165,2280)
 time.sleep(.4);console('screenrecord stop')
 data=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-vf','crop=100:90:115:2235,fps=30','-pix_fmt','rgb24','-f','rawvideo','pipe:1'])
 frame_size=100*90*3;masks=[]
 for start in range(0,len(data)-frame_size+1,frame_size):
  frame=data[start:start+frame_size]
  masks.append(bytes(1 if r>190 and 155<g<215 and 70<b<160 and r>g>b else 0 for r,g,b in zip(frame[::3],frame[1::3],frame[2::3])))
 assert len(masks)>10
 baseline=masks[-1] # settled final frame; encoder startup is not icon motion
 return {'frames':len(masks),'max_changed_gold_pixels':max(sum(a!=b for a,b in zip(baseline,m)) for m in masks[15:])}

try:
 active=sample('enabled','1');reduced=sample('system-reduced','0')
 result={'active':active,'system_reduced':reduced,'passed':active['max_changed_gold_pixels']>60 and reduced['max_changed_gold_pixels']<active['max_changed_gold_pixels']*.25}
 (ROOT/'artifacts/motion-verification.json').write_text(json.dumps(result,indent=2))
 print(json.dumps(result));assert result['passed'],result
finally:
 adb('shell','settings','put','global','animator_duration_scale','1')
 adb('shell','settings','put','global','transition_animation_scale','1')
