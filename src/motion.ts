import {createContext} from 'react';
import {Easing,withSpring,withTiming} from 'react-native-reanimated';

export const MotionContext=createContext(false);
export const MotionEffectsContext=createContext(true);

export const motion={
 iconOverflow:6,
 duration:{pane:280,sheetIn:220,sheetOut:165,listIn:160,listOut:110,toastIn:180,toastOut:140,
  controls:180,pressIn:90,pressOut:110,icon:460,tabLabelOut:150},
 spring:{
  iconIn:{duration:155,dampingRatio:.72},
  iconOut:{duration:300,dampingRatio:.77},
  panel:{mass:.85,stiffness:340,damping:34,overshootClamping:true},
 },
 ease:{out:Easing.out(Easing.cubic)},
} as const;

export function transition(to:number,duration:number,reduced:boolean,onEnd?:(finished?:boolean)=>void){
 'worklet';
 if(reduced){
  onEnd?.(true);
  return to;
 }
 return withTiming(to,{duration,easing:motion.ease.out},onEnd);
}

export function settlePanel(to:number,reduced:boolean,velocity=0,onEnd?:(finished?:boolean)=>void){
 'worklet';
 if(reduced){
  onEnd?.(true);
  return to;
 }
 return withSpring(to,{...motion.spring.panel,velocity},onEnd);
}
