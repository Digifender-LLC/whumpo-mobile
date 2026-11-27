from nav_ui import *
from emulator_console import console
import json
os.chdir(ROOT);adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
ns=nodes();n=next(n for n in ns if n.get('resource-id')=='composer-input');r=list(map(int,re.findall(r'\d+',n.get('bounds'))));x=(r[0]+r[2])//2;y=(r[1]+r[3])//2
p=ROOT/'artifacts/v11-keyboard-android.webm';print(console(f'screenrecord start --fps 60 --time-limit 12 --bit-rate 8M {p}'),flush=True);time.sleep(1);adb('shell','input','tap',str(x),str(y));time.sleep(1.5);adb('shell','input','keyevent','4');time.sleep(1.5)
# Interrupt a second opening instead of queueing independent stagger timers.
adb('shell','input','tap',str(x),str(y));adb('shell','input','keyevent','4');time.sleep(1.5);print(console('screenrecord stop'),flush=True)
ns=nodes();assert any(n.get('resource-id','').startswith('compact-mode-0-') for n in ns);assert find('Hi, Alex',True) is not None;assert find('Next · Website draft',True) is not None
Path('artifacts/v11-keyboard-reversal-android.json').write_text(json.dumps({'passed':True,'scope':'Native keyboard open, close and interrupted opening all restore the closed endpoint.'},indent=2))
