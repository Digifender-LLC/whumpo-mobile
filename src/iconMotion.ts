import type {IconName} from './AnimatedIcon';

export type IconPartMotion = {
 nodes: readonly number[];
 x?: number;
 y?: number;
 rotation?: number;
 scaleX?: number;
 scaleY?: number;
 origin?: readonly [number, number];
 delay?: number;
 ring?: boolean;
 draw?: boolean;
 /** Monotonic eased turn (lands on the identical glyph, so use only symmetric angles). */
 spin?: boolean;
};

const clipboard: readonly IconPartMotion[] = [
 {nodes:[0],y:-2.7},
 {nodes:[2,4],x:1,draw:true,delay:.04},
 {nodes:[3,5],x:1,draw:true,delay:.23},
];

export const iconMotion = {
 Today:[
  {nodes:[0],scaleX:.22,scaleY:.22},
  {nodes:[1,2,3,4,5,6,7,8],rotation:45,spin:true,scaleX:.19,scaleY:.19,delay:.08},
 ],
 Partner:[
  {nodes:[0],x:-2,y:-2,scaleX:.15,scaleY:.15,origin:[7,7]},
  {nodes:[1],x:2,y:2,scaleX:.18,scaleY:.18,origin:[17,17],delay:.16},
 ],
 Work:[{nodes:[0],y:-3.3,scaleX:.12,origin:[12,6]},{nodes:[1],y:1.6,scaleY:-.12,origin:[12,21],delay:.12}],
 Reviews:clipboard,
 Website:[{nodes:[1],scaleX:-.72},{nodes:[2],scaleY:.12,delay:.1}],
 Activity:[{nodes:[0],scaleY:.38,scaleX:.06}],
 Organization:[
  {nodes:[4],y:-3.4,scaleY:.15,origin:[12,21]},
  {nodes:[1],y:-3.4,draw:true,delay:.05},
  {nodes:[0],y:-2.1,draw:true,delay:.2},
  {nodes:[2],scaleY:.42,origin:[12,21],delay:.3},
 ],
 Account:[{nodes:[1],y:-2,scaleX:.17,scaleY:.17,origin:[12,9]},{nodes:[0],y:2,delay:.12}],
 Settings:[
  {nodes:[0,2,6],x:-2.8,rotation:12,origin:[12,5]},
  {nodes:[4,7,8],x:2.8,rotation:-13,origin:[12,12],delay:.09},
  {nodes:[1,3,5],x:-2.6,rotation:12,origin:[12,19],delay:.18},
 ],
 Help:[{nodes:[1],rotation:-18,y:-1.6,origin:[12,10]},{nodes:[2],y:1.8,delay:.2}],
 new:[{nodes:[0],x:-1.6,y:1.4,delay:.09},{nodes:[1],x:2.4,y:-2.7,rotation:-13,origin:[15,8]}],
 search:[{nodes:[0],x:-2.5,y:-2.5,rotation:-17,origin:[10,10]},{nodes:[1],x:-2.5,y:-2.5,scaleX:.19,scaleY:.19,origin:[10,10]}],
 pin:[{nodes:[1],rotation:-32,origin:[12,13],y:-1.6},{nodes:[0],rotation:-28,origin:[5,19],x:1.7,y:-1.6,delay:.08}],
 More:[
  {nodes:[0],scaleX:.37,scaleY:.37,origin:[6.5,6.5]},
  {nodes:[1],scaleX:.37,scaleY:.37,origin:[17.5,6.5],delay:.12},
  {nodes:[2],scaleX:.37,scaleY:.37,origin:[6.5,17.5],delay:.24},
  {nodes:[3],scaleX:.37,scaleY:.37,origin:[17.5,17.5],delay:.36},
 ],
 send:[{nodes:[0],y:-5.2},{nodes:[1],y:-2.6,scaleY:.26,origin:[12,19],delay:.12}],
 back:[{nodes:[0],x:-4.6},{nodes:[1],x:-2.6,scaleX:.23,origin:[18,12],delay:.1}],
 menu:[
  {nodes:[0],y:1.6,scaleX:-.3,origin:[4,5]},
  {nodes:[1],x:2.2,scaleX:-.2,origin:[4,12],delay:.1},
  {nodes:[2],y:-1.6,scaleX:-.3,origin:[4,19],delay:.2},
 ],
 close:[{nodes:[0,1],rotation:180,spin:true,scaleX:.15,scaleY:.15}],
 forward:[{nodes:[0],x:4.3,scaleX:.21}],
 down:[{nodes:[0],y:4.2,scaleY:.24}],
 reset:[{nodes:[0,1],rotation:-360,spin:true}],
 Notifications:[
  {nodes:[1],rotation:25,origin:[12,4],ring:true},
  {nodes:[0],rotation:-34,origin:[12,17],ring:true,delay:.11},
 ],
 Privacy:[{nodes:[0],scaleX:.16,scaleY:.16,y:-1,origin:[12,12]}],
 Appearance:[
  {nodes:[0],rotation:-12,y:.8},
  {nodes:[1],scaleX:.26,scaleY:.26,y:-1.8},
  {nodes:[2],scaleX:.26,scaleY:.26,y:-1.8,delay:.1},
  {nodes:[3],scaleX:.26,scaleY:.26,y:-1.8,delay:.2},
  {nodes:[4],scaleX:.26,scaleY:.26,y:-1.8,delay:.3},
 ],
 About:[{nodes:[0],scaleX:.12,scaleY:.12},{nodes:[1],y:1.6},{nodes:[2],y:-2.2,delay:.12}],
 preview:[{nodes:[0,1],rotation:16,origin:[12,10]},{nodes:[2],y:-2,rotation:-14,delay:.13}],
 members:[{nodes:[0],y:-2.5,origin:[9,7],scaleY:.18},{nodes:[1],scaleX:.17,origin:[8,20],delay:.1},{nodes:[2],x:2.6,y:-.8,delay:.16}],
 checklist:clipboard,
 plus:[{nodes:[0,1],rotation:90,spin:true,scaleX:.18,scaleY:.18}],
 check:[{nodes:[0],x:1.2,y:-1.4,scaleX:.3,scaleY:.3}],
 up:[{nodes:[0],y:-4.2,scaleY:.24}],
 image:[{nodes:[1],y:-2.3,scaleY:.15},{nodes:[2],y:2.4,scaleY:.35,origin:[12,21],delay:.1}],
 file:[{nodes:[1],rotation:-25,y:-1.6,origin:[14,2]},{nodes:[2],draw:true},{nodes:[3],draw:true,delay:.12},{nodes:[4],draw:true,delay:.24}],
 offline:[{nodes:[6],x:-1.4,draw:true},{nodes:[1,2,3,4,5],scaleX:.19,origin:[12,20],delay:.12},{nodes:[0],y:2,delay:.26}],
 loading:[{nodes:[0],rotation:360,spin:true}],
 warning:[{nodes:[0],rotation:11,ring:true},{nodes:[1],y:-2.1},{nodes:[2],y:2,delay:.2}],
 signout:[{nodes:[0,1],x:4.2},{nodes:[2],x:-1.3,delay:.1}],
 email:[{nodes:[0],scaleY:-.55,y:-1.5,origin:[12,7]},{nodes:[1],scaleY:.1,origin:[12,20],delay:.12}],
 key:[{nodes:[0],rotation:-32,origin:[17,7]},{nodes:[1],scaleX:.35,scaleY:.35,origin:[16,8],delay:.1}],
 clock:[{nodes:[1],rotation:88,origin:[12,12]},{nodes:[0],scaleX:.11,scaleY:.11,delay:.18}],
 download:[{nodes:[0,2],y:3.6},{nodes:[1],scaleY:.23,origin:[12,21],delay:.16}],
} satisfies Record<IconName, readonly IconPartMotion[]>;
