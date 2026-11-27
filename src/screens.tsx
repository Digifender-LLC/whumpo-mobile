import React,{useRef,useEffect} from 'react';
import {View,ScrollView,StyleSheet,useWindowDimensions,Platform} from 'react-native';
import Animated,{FadeIn,FadeOut,SharedValue,useSharedValue,useDerivedValue,useAnimatedStyle,useAnimatedRef,measure} from 'react-native-reanimated';
import {useKeyboardMotion,compactState} from './keyboardMotion';
import {LinearGradient} from 'expo-linear-gradient';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {colors as c,moreDestinations,isPinnable,Destination,hues,State,initialState,todayApprovalCopy} from './model';
import {AnimatedIcon} from './AnimatedIcon';
import {Pressable,ripple} from './interaction';
import {motion} from './motion';
import {Txt,Badge,rim,CompactAction,Fade} from './ui';
import {Row,StateMessage} from './product-ui';

const reviewDecisionsLabel=(count:number)=>`Review ${count} ${count===1?'decision':'decisions'}`;

export function ApprovalCard({state=initialState,onReview,large=false,availableHeight}:{state?:State;onReview:()=>void;large?:boolean;availableHeight?:SharedValue<number>}){
 const {count,title,body}=todayApprovalCopy(state),{progress,reduced,compact,semanticCompact}=useKeyboardMotion();
 const bodyHeight=useSharedValue(large?480:128),actionHeight=useSharedValue(48),reveal=useSharedValue(large?108:68),natural=useSharedValue(large?650:240);
 const lastBodyHeight=useRef(-1),lastActionHeight=useRef(-1);
 // Commit intrinsic size at layout/keyboard boundaries, not continuously during
 // native layout. Reconcile compact measurements once the keyboard closes.
 useEffect(()=>{if(!compact)natural.set(Math.max(240,bodyHeight.get()+actionHeight.get()+64))},[compact,natural,bodyHeight,actionHeight]);
 const measureActions=(e:import("react-native").LayoutChangeEvent)=>{const n=Math.ceil(e.nativeEvent.layout.height);if(lastActionHeight.current===n)return;lastActionHeight.current=n;actionHeight.set(n);if(!compact)natural.set(Math.max(240,bodyHeight.get()+n+64))};
 const card=useAnimatedStyle(()=>{const m=compactState(progress.value,reduced),cap=Math.max(140,(availableHeight?.value||natural.value));return {height:Math.min(natural.value+(Math.max(140,bodyHeight.value+actionHeight.value+64)-natural.value)*m.layout,cap),padding:large?24-8*m.layout:24}});
 const rear=useAnimatedStyle(()=>{const m=compactState(progress.value,reduced);return {height:reveal.value*m.rearSpace,opacity:m.rearOpacity,transform:reduced?[]:[{translateY:m.rearY}]}});
 const back=useAnimatedStyle(()=>({opacity:compactState(progress.value,reduced).rearOpacity}));
 return <Animated.View testID="approval-stack" entering={reduced?undefined:FadeIn.duration(motion.duration.sheetIn)} exiting={reduced?undefined:FadeOut.duration(motion.duration.listOut)}>
 <Animated.View pointerEvents="none" style={[s.backCard,back]}><LinearGradient colors={['#1A1A1A','#242424']} style={StyleSheet.absoluteFill}/></Animated.View>
 <Animated.View testID="approval-main-card" style={[s.approval,card]}>
 <View style={{flex:1}}><ScrollView testID="approval-card-body" keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS==='ios'?'interactive':'on-drag'} showsVerticalScrollIndicator={false} style={{flex:1}}><View testID="approval-intrinsic-content" style={{flexShrink:0}} onLayout={e=>{const n=Math.ceil(e.nativeEvent.layout.height);if(lastBodyHeight.current===n)return;lastBodyHeight.current=n;bodyHeight.set(n);if(!compact)natural.set(Math.max(240,n+actionHeight.get()+64))}}>
 <View style={s.sectionTitle}><Txt size={15} weight="500" style={{flex:1}}>Needs your approval</Txt></View>
 <View style={{flexDirection:'row',gap:16,alignItems:'flex-start'}}><View style={s.approvalIcon}><AnimatedIcon name="Reviews" size={32} color={c.gold}/></View><View style={{flex:1,gap:8}}><Txt size={20} weight="600">{title}</Txt><Txt size={15} color={c.muted}>{body}</Txt></View></View>
 </View></ScrollView>{large&&<Fade height={12} from="rgba(33,33,33,0)" to="#212121" style={{position:"absolute",left:0,right:0,bottom:0}}/>}</View>
 <View testID="approval-actions" onLayout={measureActions} style={s.approvalActions}><CompactAction label={reviewDecisionsLabel(count)} onPress={onReview}/></View>
 <View pointerEvents="none" style={s.frontBottomRim}/>
 </Animated.View>
 <Animated.View testID="rear-card" importantForAccessibility={semanticCompact?'no-hide-descendants':'auto'} accessibilityElementsHidden={semanticCompact} style={[{zIndex:1,overflow:'visible'},rear]}><View onLayout={e=>{if(progress.value<.001)reveal.value=Math.ceil(e.nativeEvent.layout.height)}} style={s.nextDraft}><LinearGradient pointerEvents="none" colors={['rgba(0,0,0,.65)','transparent']} style={s.stackShade}/><View testID="rear-card-globe" style={{width:44,height:44,alignItems:'center',justifyContent:'center',flexShrink:0}}><AnimatedIcon name="Website" color={hues.Website} size={32}/></View><Txt size={14} color={c.muted} style={{flex:1}}>Next · Website draft</Txt></View></Animated.View>
 </Animated.View>;
}
export function Today({state=initialState,onOpen,focusReview=false,composerHeight=56}:{composerHeight?:number;state?:State;onOpen:(p:Destination)=>void;focusReview?:boolean}){
 const pendingCount=todayApprovalCopy(state).count,{progress,height:keyboardHeight,compact,semanticCompact,reduced}=useKeyboardMotion();
 const viewportRef=useAnimatedRef<Animated.View>(),actualViewport=useSharedValue(600);
 const scroll=useRef<React.ComponentRef<typeof ScrollView>>(null),{fontScale,height}=useWindowDimensions(),insets=useSafeAreaInsets(),available=useSharedValue(600),closedComposer=useSharedValue(composerHeight),greetingHeight=useSharedValue(31.2*fontScale+(pendingCount?18:34));
 const large=fontScale>=1.7;
 const showKeyboardSummary=large&&semanticCompact;
 const openReviews=()=>onOpen('Reviews');
 const cardAvailable=useDerivedValue(()=>{const m=compactState(progress.value,reduced),nativeViewport=(progress.value>.001?measure(viewportRef)?.height:null)??actualViewport.value,estimated=available.value+keyboardHeight.value+(48-m.headerSpace)+(64-m.tabsSpace)+(Math.max(8,insets.bottom)-4)*progress.value-(composerHeight-closedComposer.value),guard=8*Math.sin(Math.PI*Math.max(0,Math.min(1,progress.value)));return Math.max(140,Math.min(nativeViewport,estimated)-28-guard-greetingHeight.value*m.greetingSpace)});
 const greet=useAnimatedStyle(()=>{const m=compactState(progress.value,reduced);return {height:greetingHeight.value*m.greetingSpace,opacity:m.greetingOpacity,overflow:'hidden'}});
 const content=useAnimatedStyle(()=>({opacity:compactState(progress.value,reduced).contentOpacity}));
 const measureViewport=(e:import("react-native").LayoutChangeEvent)=>{actualViewport.set(e.nativeEvent.layout.height);if(progress.value<.001){available.set(e.nativeEvent.layout.height);closedComposer.set(composerHeight)}};
 const headerHeight=(Platform.OS==='android'?50:Math.max(50,insets.top+12))+108;
 return <Animated.View ref={viewportRef} testID="today-viewport" style={{flex:1}} onLayout={measureViewport}><ScrollView ref={scroll} scrollEnabled={!compact||showKeyboardSummary} onLayout={()=>{if(focusReview)requestAnimationFrame(()=>scroll.current?.scrollToEnd({animated:false}))}} onContentSizeChange={()=>{if(focusReview&&!compact)scroll.current?.scrollToEnd({animated:false})}} keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS==='ios'?'interactive':'on-drag'} showsVerticalScrollIndicator={false} testID="today-scroll" contentContainerStyle={[s.todayContent,{paddingTop:12,paddingBottom:16,flexGrow:1}]}>
 <Animated.View style={content}>
 {showKeyboardSummary ? (
  <View testID="today-keyboard-summary">
   {pendingCount>0
    ? <CompactAction label={reviewDecisionsLabel(pendingCount)} onPress={openReviews}/>
    : <Txt size={24}>Hi, Alex</Txt>}
  </View>
 ) : <>
 <Animated.View testID="today-greeting" importantForAccessibility={semanticCompact?'no-hide-descendants':'auto'} accessibilityElementsHidden={semanticCompact} style={greet}><View onLayout={e=>{greetingHeight.set(e.nativeEvent.layout.height)}} style={{position:'absolute',left:0,right:0,top:0,alignItems:pendingCount?'flex-start':'center',paddingBottom:pendingCount?18:34,paddingTop:pendingCount?0:Math.max(24,height*.39-headerHeight)}}><Txt size={24}>Hi, Alex</Txt>{pendingCount===0&&<Txt size={22} color={c.subtitle} style={{textAlign:'center',marginTop:10}}>What needs you today?</Txt>}</View></Animated.View>
 {pendingCount>0&&<ApprovalCard state={state} large={large} availableHeight={cardAvailable} onReview={openReviews}/>}
 {pendingCount===0&&<StateMessage title="Nothing needs you right now" detail="Approved changes still need a separate publish step." action="View Activity" onPress={()=>onOpen('Activity')}/>}
 </>}
 </Animated.View></ScrollView></Animated.View>;
}
function Tile({name,onOpen,onPin,count}:{name:Destination;onOpen:(n:Destination)=>void;onPin:(n:Destination)=>void;count:number}){
 const canPin=isPinnable(name);
 return <Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel={name+(name==='Reviews'?`, ${count} ${count===1?'approval':'approvals'}`:'')} accessibilityHint={canPin?'Hold to pin to tab bar':undefined} accessibilityActions={canPin?[{name:'pin',label:`Pin ${name} to tab bar`}]:undefined} onAccessibilityAction={e=>{if(canPin&&e.nativeEvent.actionName==='pin')onPin(name)}} onPress={()=>onOpen(name)} onLongPress={canPin?()=>onPin(name):undefined} style={[s.tile,s.tileContent]}>
 <Txt size={20} weight="500">{name}</Txt><View style={{height:22}}/><View style={s.tileBottom}><View style={[s.tileGlyph,{backgroundColor:hues[name]+'18'}]}><AnimatedIcon name={name} size={30} color={hues[name]}/></View>{name==='Reviews'&&count>0&&<Badge count={count}/>}</View>
 </Pressable>;
}
export function More({pin,onOpen,onPin,count=2}:{pin:Destination;count?:number;onOpen:(n:Destination)=>void;onPin:(n:Destination)=>void}){
 const {fontScale}=useWindowDimensions(),columns=fontScale>=1.7?1:2,items=moreDestinations(pin);const rows:Destination[][]=[];for(let i=0;i<items.length;i+=columns)rows.push(items.slice(i,i+columns));
 return <ScrollView testID="more-scroll" keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS==='ios'?'interactive':'on-drag'} showsVerticalScrollIndicator={false} contentContainerStyle={s.moreContent}><View style={s.grid}>{rows.map((row,i)=><View key={i} style={{flexDirection:'row',gap:16}}>{row.map(n=><Tile key={n} name={n} count={count} onOpen={onOpen} onPin={onPin}/>)}{columns===2&&row.length===1&&<View style={{flex:1}}/>}</View>)}</View><View style={[s.group,{marginTop:24}]}>{(['Account','Settings','Help'] as const).map((name,i)=><Row key={name} title={name} icon={name} onPress={()=>onOpen(name)} last={i===2}/>)}</View></ScrollView>;
}
const s=StyleSheet.create({
 todayContent:{paddingHorizontal:24,paddingBottom:30},sectionTitle:{flexDirection:'row',alignItems:'center',justifyContent:'space-between',gap:12,marginBottom:12},approval:{zIndex:2,borderRadius:26,backgroundColor:c.suggestion,padding:24,...rim},approvalIcon:{width:44,height:44,borderRadius:14,alignItems:'center',justifyContent:'center',backgroundColor:'#383327'},backCard:{position:'absolute',top:12,bottom:0,left:14,right:14,borderRadius:26,borderWidth:1,borderColor:'rgba(255,255,255,.08)',overflow:'hidden'},nextDraft:{marginHorizontal:14,minHeight:68,paddingHorizontal:10,paddingTop:12,paddingBottom:12,flexDirection:'row',justifyContent:'flex-start',alignItems:'center',gap:16},stackShade:{position:'absolute',left:0,right:0,top:0,height:22},frontBottomRim:{position:'absolute',bottom:0,left:26,right:26,height:StyleSheet.hairlineWidth,backgroundColor:'rgba(255,255,255,.25)'},approvalActions:{marginTop:16},
 moreContent:{paddingHorizontal:24,paddingTop:0,paddingBottom:24},grid:{gap:16},tile:{flex:1,minWidth:0,minHeight:152,borderRadius:17,backgroundColor:c.surface,...rim},tileContent:{flex:1,minHeight:152,paddingTop:24,paddingLeft:16,paddingRight:16,paddingBottom:24},tileBottom:{flexDirection:'row',alignItems:'center',justifyContent:'space-between',gap:4},tileGlyph:{width:40,height:40,borderRadius:12,alignItems:'center',justifyContent:'center'},
 group:{backgroundColor:c.surface,borderRadius:20,overflow:'hidden',...rim},
});
