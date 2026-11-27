import {View,Platform} from 'react-native';
import Animated,{useAnimatedStyle} from 'react-native-reanimated';
import {HeaderTitle,OrganizationChip} from './Chrome';
import {useKeyboardMotion,compactState} from './keyboardMotion';
import {HeaderIconFocusContext} from './AnimatedIcon';
import {Org,colors} from './model';
import {IconButton,Badge,Txt} from './ui';
export function AdaptiveHeader({title,org,conversation,left,right,onOrganization,top,focused=false}:{focused?:boolean;title:string;org:Org;conversation:string;left:React.ReactNode;right:React.ReactNode;onOrganization:()=>void;top:number}){
 const {progress,compact,semanticCompact,reduced}=useKeyboardMotion(),today=title==='Today',partner=title==='Partner';
 const frame=useAnimatedStyle(()=>({paddingBottom:12+(today?compactState(progress.value,reduced).headerSpace:0)}));
 const standard=useAnimatedStyle(()=>({opacity:(today||partner)?compactState(progress.value,reduced).titleOpacity:1}));
 const chip=useAnimatedStyle(()=>{const v=compactState(progress.value,reduced);return {top:reduced?v.chipTop:0,opacity:v.chipOpacity,transform:reduced?[]:[{translateY:v.chipY}]}});
 const compactTitle=useAnimatedStyle(()=>({opacity:1-compactState(progress.value,reduced).titleOpacity}));
 return <HeaderIconFocusContext.Provider value={focused}><Animated.View testID={today&&compact?'today-header-compact':'screen-header'} style={[{paddingHorizontal:24,paddingTop:Platform.OS==='android'?50:Math.max(50,top+12)},frame]}>
 <View style={{height:48,flexDirection:'row',alignItems:'center'}}>{left}<View style={{flex:1}}/><View style={{width:48,height:48}}>{right}</View></View>
 <View pointerEvents="box-none" style={{position:'absolute',left:80,right:80,top:Platform.OS==='android'?50:Math.max(50,top+12),height:today?96:48,alignItems:'center'}}>
 <Animated.View testID="screen-title" importantForAccessibility={semanticCompact&&(today||partner)?'no-hide-descendants':'auto'} accessibilityElementsHidden={semanticCompact&&(today||partner)} style={[{height:48,justifyContent:'center',width:'100%'},standard]}><HeaderTitle title={title}/></Animated.View>
 {today&&<Animated.View testID="today-organization-chip" style={[{position:'absolute',height:48,alignSelf:'center',maxWidth:'100%',flexDirection:'row',alignItems:'center',gap:8},chip]}>
  {semanticCompact&&<Txt accessibilityRole="header" size={12} weight="600" color={colors.muted} maxFontSizeMultiplier={1.2}>Today</Txt>}
  <OrganizationChip org={org} onPress={onOrganization}/>
 </Animated.View>}
 {partner&&<Animated.View pointerEvents="none" importantForAccessibility={!semanticCompact?'no-hide-descendants':'auto'} accessibilityElementsHidden={!semanticCompact} style={[{position:'absolute',height:48,justifyContent:'center',width:'100%'},compactTitle]}><HeaderTitle title={conversation}/></Animated.View>}
 </View></Animated.View></HeaderIconFocusContext.Provider>;
}
export function HeaderAction({page,count,onReviews,onNew,onSettings,onHelp}:{page:string;count:number;onReviews:()=>void;onNew:()=>void;onSettings:()=>void;onHelp:()=>void}){
 return page==='Today'?<><IconButton label={`Reviews, ${count} ${count===1?'approval':'approvals'}`} icon="Reviews" onPress={onReviews}/>{count>0&&<Badge count={count} style={{position:'absolute',right:-2,top:-3}}/>}</>:page==='Partner'?<IconButton label="New conversation" icon="new" onPress={onNew}/>:page==='More'?<IconButton label="Open settings" icon="Settings" onPress={onSettings}/>:page==='Settings'?<IconButton label="Help" icon="Help" onPress={onHelp}/>:null;
}
