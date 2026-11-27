"""Record native emulator frames for visible functional icon motion (console, no screencap)."""
from device import *
from emulator_console import console
os.chdir(ROOT)
adb('shell','settings','put','system','font_scale','1.0');time.sleep(2)
launch('today',restart=True);click('Today',True);time.sleep(.6)
adb('shell','dumpsys','gfxinfo',APP_ID,'reset')
p=ROOT/'artifacts/icon-motion.webm';print(console(f'screenrecord start --fps 60 --time-limit 12 --bit-rate 6M {p}'))
# Tabs are stable while the destination content changes; each explicit press plays once.
for x in [165,165,414,414,916,916,165,916]:
 tap(x,2280);time.sleep(.15)
print(console('screenrecord stop'))
stat=adb('shell','dumpsys','gfxinfo',APP_ID);(ROOT/'artifacts/gfxinfo-motion.txt').write_text(stat)
assert p.exists() and p.stat().st_size>1000
print('Saved',p.stat().st_size,'bytes')
