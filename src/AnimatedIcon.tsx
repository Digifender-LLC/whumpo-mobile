import {createContext,memo,useContext,useEffect,useRef,type ComponentType,type ReactNode} from 'react';
import {View} from 'react-native';
import Svg,{Circle,Ellipse,G,Line,Path,Polygon,Polyline,Rect} from 'react-native-svg';
import Animated,{cancelAnimation,useAnimatedProps,useAnimatedReaction,useSharedValue,withSequence,withSpring,withTiming,type SharedValue} from 'react-native-reanimated';
import {iconGeometry} from './iconGeometry';
import {iconMotion,type IconPartMotion} from './iconMotion';
import {IconInteractionContext} from './interaction';
import {MotionContext,MotionEffectsContext,motion} from './motion';

export {MotionContext} from './motion';
export type IconName=keyof typeof iconGeometry;
export const HeaderIconFocusContext=createContext(false);

const shapes={path:Path,circle:Circle,rect:Rect,line:Line,polygon:Polygon,polyline:Polyline,ellipse:Ellipse};
const AnimatedGroup=Animated.createAnimatedComponent(G<{matrix?: number[]}>);
const drawingShapes={
 path:Animated.createAnimatedComponent(Path),circle:Animated.createAnimatedComponent(Circle),
 rect:Animated.createAnimatedComponent(Rect),line:Animated.createAnimatedComponent(Line),
 polygon:Animated.createAnimatedComponent(Polygon),polyline:Animated.createAnimatedComponent(Polyline),
 ellipse:Animated.createAnimatedComponent(Ellipse),
};

type Geometry=(typeof iconGeometry)[IconName];
type StrokeTag=keyof typeof shapes;

function pulse(clock:number,delay=0,ring=false){
 'worklet';
 const position=Math.max(0,Math.min(1,(clock-delay)/(1-delay)));
 const envelope=Math.sin(position*Math.PI);
 return ring?Math.sin(position*Math.PI*4)*envelope:envelope;
}

function settle(clock:number,delay=0){
 'worklet';
 const position=Math.max(0,Math.min(1,(clock-delay)/(1-delay)));
 return 1-Math.pow(1-position,3);
}

function play(clock:SharedValue<number>,bounce:SharedValue<number>){
 'worklet';
 clock.set(0);
 bounce.set(0);
 clock.set(withTiming(1,{duration:motion.duration.icon}));
 bounce.set(withSequence(withSpring(1,motion.spring.iconIn),withSpring(0,motion.spring.iconOut)));
}

function Stroke({tag,attributes,length,clock,delay=0}:{tag:StrokeTag;attributes:Record<string,unknown>;length:number;clock:SharedValue<number>;delay?:number}){
 const dash=length*1.08;
 const animatedProps=useAnimatedProps(()=>({
  strokeDashoffset:Math.max(0,pulse(clock.value,delay))*length*.86,
 }));
 const Shape=drawingShapes[tag] as unknown as ComponentType<Record<string,unknown>>;
 return <Shape {...attributes} strokeDasharray={[dash,dash]} animatedProps={animatedProps}/>;
}

function Part({part,clock,bounce,children}:{part:IconPartMotion;clock:SharedValue<number>;bounce:SharedValue<number>;children:ReactNode}){
 const {x=0,y=0,rotation=0,scaleX=0,scaleY=0,origin=[12,12],delay,ring,spin}=part;
 const [originX,originY]=origin;
 const animatedProps=useAnimatedProps(()=>{
  const amount=pulse(clock.value,delay,ring);
  const size=amount*(.8+.2*bounce.value);
  const angle=rotation*(spin?settle(clock.value,delay):amount)*Math.PI/180;
  const cos=Math.cos(angle),sin=Math.sin(angle);
  const horizontal=1+scaleX*size,vertical=1+scaleY*size;
  const a=cos*horizontal,b=sin*horizontal,c=-sin*vertical,d=cos*vertical;
  return {matrix:[a,b,c,d,originX-a*originX-c*originY+x*amount,originY-b*originX-d*originY+y*amount]};
 });
 return <AnimatedGroup animatedProps={animatedProps}>{children}</AnimatedGroup>;
}

function OriginalStroke({geometry,index,draw,clock,delay}:{geometry:Geometry;index:number;draw:boolean;clock:SharedValue<number>;delay?:number}){
 const [tag,{key:_,...attributes}]=geometry.data.node[index];
 const name=tag as StrokeTag;
 const length=geometry.lengths[index];
 if(draw&&length>1)return <Stroke tag={name} attributes={attributes} length={length} clock={clock} delay={delay}/>;
 const Shape=shapes[name] as unknown as ComponentType<Record<string,unknown>>;
 return <Shape {...attributes}/>;
}

export const AnimatedIcon=memo(function AnimatedIcon({name,size=24,color='#FFFFFF',active=false,animateOnMount=false}:{name:IconName;size?:number;color?:string;active?:boolean;animateOnMount?:boolean}){
 const reduced=useContext(MotionContext);
 const focus=useContext(HeaderIconFocusContext);
 const detailed=useContext(MotionEffectsContext);
 const interaction=useContext(IconInteractionContext);
 const clock=useSharedValue(1),bounce=useSharedValue(0);
 const previous=useRef<{name:IconName;active:boolean;focus:boolean;reduced:boolean}|undefined>(undefined);
 useEffect(()=>{
  const last=previous.current;
  previous.current={name,active,focus,reduced};
  if(reduced){
   cancelAnimation(clock);
   cancelAnimation(bounce);
   clock.set(1);
   bounce.set(0);
  }else if((active&&!last?.active)||(focus&&!last?.focus)||last&&last.name!==name||(!last||last.reduced)&&(animateOnMount||focus||active)){
   play(clock,bounce);
  }
 },[name,active,animateOnMount,focus,reduced,clock,bounce]);
 const pressCount=interaction?.pressCount,hovered=interaction?.hovered;
 useAnimatedReaction(()=>({count:pressCount?.value??0,hover:!reduced&&(hovered?.value??false)}),(next,last)=>{
  if(!reduced&&last&&(next.count!==last.count||next.hover&&!last.hover))play(clock,bounce);
 });
 const geometry=iconGeometry[name];
 const parts:readonly IconPartMotion[]=iconMotion[name];
 const stroke=(index:number,part?:IconPartMotion)=><OriginalStroke key={index} geometry={geometry} index={index} draw={detailed&&!!part?.draw} clock={clock} delay={part?.delay}/>;
 const glyphSize=geometry.data.size??24;
 const overflow=motion.iconOverflow;
 const bleed=size*overflow/glyphSize;
 const indices=geometry.data.node.map((_,index)=>index);
 const staticIndices=indices.filter(index=>!parts.some(part=>part.nodes.includes(index)));
 return <View accessible={false} importantForAccessibility="no-hide-descendants" style={{width:size,height:size}} pointerEvents="none">
  <Svg style={{position:'absolute',left:-bleed,top:-bleed}} width={size+2*bleed} height={size+2*bleed} viewBox={`${-overflow} ${-overflow} ${glyphSize+2*overflow} ${glyphSize+2*overflow}`} fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
   {reduced?indices.map(index=>stroke(index)):<>
    {staticIndices.map(index=>stroke(index))}
    {parts.map(part=><Part key={part.nodes[0]} part={part} clock={clock} bounce={bounce}>
     {part.nodes.map(index=>stroke(index,part))}
    </Part>)}
   </>}
  </Svg>
 </View>;
});
