"""Native blur evidence: identical history foreground above two real source screens."""
from device import *
from PIL import Image,ImageChops,ImageStat
import json,sys
os.chdir(ROOT)
if '--analyze-only' not in sys.argv:
 adb('shell','settings','put','system','font_scale','1.0');time.sleep(2)
 launch('today',restart=True);shot('artifacts/blur-source-today.png')
 click('Open Partner history',True);time.sleep(1);shot('artifacts/blur-over-today.png')
 back();click('More',True);shot('artifacts/blur-source-more.png')
 click('Open Partner history',True);time.sleep(1);shot('artifacts/blur-over-more.png')
scale=1080/411.4285714
roi=tuple(round(v*scale) for v in [355,250,390,650])
today=Image.open('artifacts/blur-over-today.png').convert('RGB');more=Image.open('artifacts/blur-over-more.png').convert('RGB')
a_mean=ImageStat.Stat(today.crop(roi)).mean;b_mean=ImageStat.Stat(more.crop(roi)).mean
mean_difference=sum(abs(a-b) for a,b in zip(a_mean,b_mean))/3
diff=sum(ImageStat.Stat(ImageChops.difference(today.crop(roi),more.crop(roi))).mean)/3
def energy(im):
 p=im.crop(roi).convert('L');w,h=p.size
 return (ImageStat.Stat(ImageChops.difference(p.crop((0,0,w-1,h)),p.crop((1,0,w,h)))).mean[0]+ImageStat.Stat(ImageChops.difference(p.crop((0,0,w,h-1)),p.crop((0,1,w,h)))).mean[0])/2
source_energy=energy(Image.open('artifacts/blur-source-more.png'));blur_energy=energy(more)
result={'native_package':APP_ID,'background_only_roi_pixels':roi,'mean_absolute_rgb_difference_changing_underlay':diff,'difference_between_region_mean_rgb':mean_difference,'more_source_edge_energy':source_energy,'more_blurred_edge_energy':blur_energy,'passed':mean_difference>1,'interpretation':'Same history foreground/dim/fade, changing native source. Background difference verifies source-dependent sampling. Raw edge energy is diagnostic only: native grain makes it unsuitable as a blur-quality threshold. Visual softness and native BlurTarget setup are separate evidence; no GPU trace or pixel-equivalence claim.'}
Path('artifacts/blur-verification.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert result['passed'],result
pair=Image.new('RGB',(2160,2340));pair.paste(today,(0,0));pair.paste(more,(1080,0));pair.save('artifacts/blur-today-vs-more.png')
if '--analyze-only' not in sys.argv:back()
