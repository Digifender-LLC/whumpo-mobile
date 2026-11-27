import {useCallback,useEffect,useState} from 'react';
import {useFrameCallback,useSharedValue} from 'react-native-reanimated';

const sampleMs=520;
const minimumIntervals=8;

export function updateMotionBudget(current:{simple:boolean;clean:number},missed:number,intervals:number){
 if(intervals<minimumIntervals)return current;
 if(missed>=3&&missed/intervals>=.22)return current.simple&&current.clean===0?current:{simple:true,clean:0};
 if(missed>1||!current.simple)return current.clean===0?current:{...current,clean:0};
 const clean=current.clean+1;
 return clean>=3?{simple:false,clean:0}:{simple:true,clean};
}

export function useMotionBudget(transitionKey:string,reduced:boolean){
 const [budget,setBudget]=useState({simple:false,clean:0});
 const best=useSharedValue(0),intervals=useSharedValue(0),missed=useSharedValue(0);
 const measureFrame=useCallback(({timeSincePreviousFrame:delta,timeSinceFirstFrame:elapsed}:{timeSincePreviousFrame:number|null;timeSinceFirstFrame:number})=>{
  'worklet';
  if(elapsed>=sampleMs||delta===null||delta<4)return;
  const lower=best.value===0||delta<best.value*.8;
  if(lower){
   best.set(delta);
   intervals.set(0);
   missed.set(0);
  }
  intervals.set(intervals.value+1);
  if(delta>best.value*1.6)missed.set(missed.value+Math.max(1,Math.round(delta/best.value)-1));
 },[best,intervals,missed]);
 const frames=useFrameCallback(measureFrame,false);
 const {setActive}=frames;
 useEffect(()=>{
  if(reduced)return;
  best.set(0);intervals.set(0);missed.set(0);
  setActive(true);
  const timeout=setTimeout(()=>{
   setActive(false);
   const fastest=best.get(),drops=missed.get(),count=intervals.get();
   if(__DEV__&&count>=minimumIntervals)console.info('[Whumpo motion]',{transition:transitionKey,fastestMs:Math.round(fastest*10)/10,missed:drops,intervals:count});
   setBudget(previous=>updateMotionBudget(previous,drops,count));
  },sampleMs+20);
  return()=>{clearTimeout(timeout);setActive(false)};
 },[transitionKey,reduced,setActive,best,intervals,missed]);
 return budget.simple;
}
