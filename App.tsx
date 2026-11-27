import 'react-native-gesture-handler';
import React,{useState,useEffect,useLayoutEffect,useReducer,useRef,useCallback,createContext,useContext,useMemo} from 'react';
import {AccessibilityInfo,BackHandler,Keyboard,Linking,Modal,Platform,ScrollView,StyleSheet,useWindowDimensions,View,Image} from 'react-native';
import {Gesture,GestureDetector,GestureHandlerRootView} from 'react-native-gesture-handler';
import {SafeAreaProvider,useSafeAreaInsets} from 'react-native-safe-area-context';
import {NavigationContainer,createNavigationContainerRef,DarkTheme,StackActions,CommonActions,useIsFocused} from '@react-navigation/native';
import {createNativeStackNavigator,NativeStackScreenProps} from '@react-navigation/native-stack';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {BlurView,BlurTargetView} from 'expo-blur';
import {LinearGradient} from 'expo-linear-gradient';
import {useFonts} from 'expo-font';
import {StatusBar} from 'expo-status-bar';
import * as NavigationBar from 'expo-navigation-bar';
import * as Haptics from 'expo-haptics';
import * as SplashScreen from 'expo-splash-screen';
import Animated,{useSharedValue,useAnimatedStyle,FadeInUp,FadeOut,runOnJS} from 'react-native-reanimated';
import {Pressable,ripple} from './src/interaction';
import {KeyboardProvider} from 'react-native-keyboard-controller';
import {KeyboardMotionContext,useCoordinatedKeyboard,useKeyboardMotion,compactState,CompactProbe} from './src/keyboardMotion';
import {NavigationTabs} from './src/NavigationChrome';
import {createTabLabelLaunchTracker,TAB_LABEL_INTRO_MS} from './src/tabLabels';
import {AdaptiveHeader,HeaderAction} from './src/AdaptiveHeader';
import {AnimatedIcon} from './src/AnimatedIcon';
import {MotionContext,MotionEffectsContext,motion,settlePanel,transition} from './src/motion';
import {useMotionBudget} from './src/motionBudget';
import {usePresence} from './src/presence';
import {Txt,IconButton,Action,Composer,Fade,BackdropContext} from './src/ui';
import {Today,More} from './src/screens';
import {Details,PartnerScreen,Page,ListMode} from './src/details';
import {HistoryPane} from './src/HistoryPane';
import {Row,Surface} from './src/product-ui';
import {State,Action as ModelAction,Destination,Org,organizations,isPinnable,initialState,reducer,pendingCount,ApprovalId,colors as c} from './src/model';
SplashScreen.preventAutoHideAsync().catch(()=>{});
type Routes={Page:{page:Page;root:boolean;id?:ApprovalId;focusReview?:boolean}};
const Stack=createNativeStackNavigator<Routes>(),nav=createNavigationContainerRef<Routes>();
const showTabLabelsForLaunch=createTabLabelLaunchTracker(AsyncStorage);
const allPages:Page[]=['Today','More','Partner','Settings','Reviews','Approval detail','Website','Review changes','Publish-ready checklist','Activity','Organization','Account','Members & permissions','Pinned tab','Approval reminders','Quiet hours','Privacy & data','Appearance','Help','About','Work','Project','Preview states','Sign-in methods','Contact support'];
type ScreenData={blocked:boolean;state:State;count:number;tabs:readonly(Destination|'Today'|'More')[];inset:{top:number;bottom:number};composerHeight:number;showHistory:()=>void;go:(p:Page,id?:ApprovalId)=>void;newConversation:()=>void;reload:()=>void;dispatch:React.Dispatch<ModelAction>;setModal:(v:string)=>void;listMode:ListMode;setListMode:(v:ListMode)=>void;setComposer:(v:string)=>void};
const ScreenContext=createContext<ScreenData>(null!);
type PageParams=Routes['Page'];
function Screen({route}:NativeStackScreenProps<Routes,'Page'>){
 const {tabs}=useContext(ScreenContext);
 const reduced=useContext(MotionContext);
 const focused=useIsFocused();
 const {width}=useWindowDimensions();
 const params=route.params;
 const [shown,setShown]=useState(params);
 const [outgoing,setOutgoing]=useState<PageParams>();
 const from=tabs.findIndex(tab=>tab===outgoing?.page);
 const to=tabs.findIndex(tab=>tab===params.page);
 const [direction,setDirection]=useState(0);
 const progress=useSharedValue(1);
 const finish=useCallback((page:PageParams)=>setOutgoing(previous=>previous===page?undefined:previous),[]);
 if(shown!==params){
  const old=tabs.findIndex(tab=>tab===shown.page);
  const slide=!reduced&&focused&&params.root&&old>=0&&to>=0&&old!==to;
  setShown(params);
  setOutgoing(slide?shown:undefined);
  setDirection(slide?Math.sign(to-old):0);
 }
 useLayoutEffect(()=>{
  if(!outgoing)return;
  if(reduced){finish(outgoing);progress.set(1);return}
  progress.set(0);
  progress.set(transition(1,motion.duration.pane,false,done=>{
   'worklet';
   if(done)runOnJS(finish)(outgoing);
  }));
 },[outgoing,finish,progress,reduced]);
 const slide=!!outgoing&&outgoing.page!==params.page&&from>=0&&to>=0&&!reduced;
 const enter=useAnimatedStyle(()=>({transform:[{translateX:slide?direction*width*(1-progress.value):0}]}));
 const exit=useAnimatedStyle(()=>({transform:[{translateX:-direction*width*progress.value}]}));
 return <View style={s.deck}>
  {slide&&<Animated.View key={outgoing.page} pointerEvents="none" importantForAccessibility="no-hide-descendants" accessibilityElementsHidden style={[s.pane,exit]}>
   <PageContent params={outgoing} focused={false}/>
  </Animated.View>}
  <Animated.View key={params.page} style={[s.pane,enter]}><PageContent params={params} focused={focused}/></Animated.View>
 </View>;
}
function PageContent({params,focused}:{params:PageParams;focused:boolean}){
 const {blocked,state,count,tabs,inset,composerHeight,showHistory,go,newConversation,reload,dispatch,setModal,listMode,setListMode,setComposer}=useContext(ScreenContext);
 const p=params.page,id=params.id||'intro',isRoot=params.root&&tabs.includes(p as typeof tabs[number]);
 const {progress:keyboardProgress,height:keyboardHeight,reduced:motionReduced,compact}=useKeyboardMotion();
 const bodyStyle=useAnimatedStyle(()=>({marginBottom:((isRoot||p==='Contact support')?((p==='Today'||p==='Partner'||p==='Contact support'?composerHeight+4:0)+Math.max(8,inset.bottom)+(4-Math.max(8,inset.bottom))*keyboardProgress.value+(isRoot?compactState(keyboardProgress.value,motionReduced).tabsSpace:0)):Math.max(16,inset.bottom))+Math.max(0,-keyboardHeight.value)}));
 return <Animated.View collapsable={false} importantForAccessibility={blocked?'no-hide-descendants':'auto'} accessibilityElementsHidden={blocked} style={s.root}>
 <AdaptiveHeader focused={focused} title={p} org={state.org} conversation={state.conversations[state.org]} top={inset.top} onOrganization={()=>{Keyboard.dismiss();setModal('Organizations')}} left={<IconButton label={isRoot?'Open Partner history':'Back'} icon={isRoot?'menu':'back'} onPress={()=>{if(isRoot)showHistory();else if(nav.canGoBack())nav.goBack();else go('More')}}/>} right={<HeaderAction page={p} count={count} onReviews={()=>go('Reviews')} onNew={newConversation} onSettings={()=>go('Settings')} onHelp={()=>go('Help')}/>}/>

 {state.offline&&<Animated.View entering={motionReduced?undefined:FadeInUp.duration(motion.duration.sheetIn)} exiting={motionReduced?undefined:FadeOut.duration(motion.duration.listOut)} style={{marginHorizontal:20,marginBottom:12}}><Pressable android_ripple={ripple} accessibilityRole="button" accessibilityLabel="Dismiss local offline notice" onPress={()=>dispatch({type:'offline'})} style={{minHeight:48,padding:12,borderRadius:16,backgroundColor:c.selected,flexDirection:'row',gap:12,alignItems:'center'}}><AnimatedIcon name="offline" size={20} color={c.muted}/><Txt size={15} color={c.muted} style={{flex:1}}>Working locally. Drafts stay on this device.</Txt></Pressable></Animated.View>}
 <Animated.View style={[{flex:1},bodyStyle]}>{p==='Today'?<Today composerHeight={composerHeight} state={state} focusReview={params.focusReview} onOpen={n=>go(n)}/>:p==='More'?<More count={count} pin={state.pins[state.org]} onOpen={n=>go(n)} onPin={n=>setModal('Pin:'+n)}/>:p==='Partner'?<PartnerScreen key={state.org} state={state} go={go} prompt={setComposer}/>:<Details page={p} state={state} dispatch={dispatch} go={go} sheet={setModal} approvalId={id} listMode={listMode} reload={reload} setListMode={setListMode}/>}<Fade height={compact&&p==='Today'?0:22} style={{position:'absolute',bottom:0,left:0,right:0}}/></Animated.View>
 </Animated.View>}
export default function App(){const [loaded,error]=useFonts({Inter400:require('./assets/fonts/inter_400.ttf'),Inter500:require('./assets/fonts/inter_500.ttf'),Inter600:require('./assets/fonts/inter_600.ttf')});useEffect(()=>{if(loaded||error)SplashScreen.hideAsync().catch(()=>{})},[loaded,error]);return <GestureHandlerRootView style={s.root}><KeyboardProvider statusBarTranslucent navigationBarTranslucent><SafeAreaProvider><StatusBar style="light"/>{loaded||error?<Workspace/>:<View style={[s.root,{alignItems:'center',justifyContent:'center'}]}><Image source={require('./assets/splash-icon.png')} style={{width:120,height:120}}/></View>}</SafeAreaProvider></KeyboardProvider></GestureHandlerRootView>}
// Android needs a native dialog window for accessibility isolation. iOS keeps the
// sheet in the existing native root: SDK58/RN0.88 modal portals intermittently
// swallowed subsequent taps after keyboard composition in the simulator.
function SheetHost({children,open,reduced,onClose,onHidden}:{children:React.ReactNode;open:boolean;reduced:boolean;onClose:()=>void;onHidden:()=>void}){
 const inset=useSafeAreaInsets();
 const progress=useSharedValue(open&&reduced?1:0);
 useEffect(()=>{
  if(!open&&reduced){onHidden();return;}
  progress.set(transition(open?1:0,open?motion.duration.sheetIn:motion.duration.sheetOut,reduced,finished=>{
   'worklet';
   if(finished&&!open)runOnJS(onHidden)();
  }));
 },[onHidden,open,progress,reduced]);
 const dim=useAnimatedStyle(()=>({opacity:progress.value*.70}));
 const panel=useAnimatedStyle(()=>({opacity:progress.value,transform:[{translateY:reduced?0:28*(1-progress.value)}]}));
 const content=<View style={s.scrim} pointerEvents={open?'auto':'none'} accessibilityViewIsModal={open} accessibilityElementsHidden={!open} importantForAccessibility={open?'auto':'no-hide-descendants'} onAccessibilityEscape={onClose}>
  <Animated.View pointerEvents="none" style={[StyleSheet.absoluteFill,{backgroundColor:c.canvas},dim]}/>
  <Pressable accessibilityRole="button" accessibilityLabel="Dismiss dialog" onPress={onClose} style={StyleSheet.absoluteFill}/>
  <Animated.ScrollView style={[s.sheet,panel]} contentContainerStyle={{padding:24,paddingBottom:Math.max(32,inset.bottom+16),gap:20}} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS==='ios'?'interactive':'on-drag'}>{children}</Animated.ScrollView>
 </View>;
 return Platform.OS==='android'?<Modal visible transparent statusBarTranslucent navigationBarTranslucent hardwareAccelerated animationType="none" onRequestClose={onClose}>{content}</Modal>:content;
}
function Workspace(){
 const [state,dispatch]=useReducer(reducer,initialState),[ready,setReady]=useState(false),[page,setPage]=useState<Page>('Today'),[modal,setModalValue]=useState(''),[sheetOpen,setSheetOpen]=useState(false),[sidebar,setSidebar]=useState(false),[orgMenu,setOrgMenu]=useState(false),[historySearch,setHistorySearch]=useState(''),[composer,setComposer]=useState(''),[supportDraft,setSupportDraft]=useState(''),[systemReduced,setSystemReduced]=useState(true),[listMode,setListMode]=useState<ListMode>('ready');
 const setModal=useCallback((value:string)=>{
  if(value)setModalValue(value);
  setSheetOpen(!!value);
 },[]);
 const clearSheet=useCallback(()=>setModalValue(''),[]);
 const closeSheet=useCallback(()=>setModal(''),[setModal]);
 const [introduceLabels,setIntroduceLabels]=useState(false);
 const feedbackTimer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
 const target=useRef<React.ComponentRef<typeof BlurTargetView>>(null),pending=useRef<string|null>(null),receivedLink=useRef(false),timer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);const inset=useSafeAreaInsets(),{width}=useWindowDimensions(),progress=useSharedValue(0);
 const reduced=systemReduced||state.reduceMotion,count=pendingCount(state),root=nav.isReady()?(nav.getRootState()?.index||0)===0:true;
 const tabs=['Today','Partner',state.pins[state.org],'More'] as const,hasTabs=root&&tabs.includes(page as typeof tabs[number]);
 const keyboardMotion=useCoordinatedKeyboard(reduced),keyboardVisible=keyboardMotion.compact,[composerHeight,setComposerHeight]=useState(56),[feedback,setFeedback]=useState('');
 const simplifyMotion=useMotionBudget(`${page}|${sidebar}|${sheetOpen}|${!!feedback}|${keyboardVisible}`,reduced);
 const support=page==='Contact support',hasComposer=support||(hasTabs&&(page==='Today'||page==='Partner'));
 const composerPresence=usePresence(hasComposer,reduced,28),footerPresence=usePresence(hasTabs||support,reduced,32),tabPresence=usePresence(hasTabs,reduced,20),composerPage=useRef(page);
 if(hasComposer)composerPage.current=page;
 const composerOnSupport=composerPage.current==='Contact support';
 const unmountHistory=useCallback(()=>{setSidebar(false);setOrgMenu(false)},[]);
 const closeHistory=useCallback(()=>{setOrgMenu(false);progress.value=settlePanel(0,reduced,0,done=>{'worklet';if(done)runOnJS(unmountHistory)()})},[progress,reduced,unmountHistory]);
 const showHistory=useCallback((query='')=>{Keyboard.dismiss();setHistorySearch(query);setSidebar(true);progress.value=settlePanel(1,reduced)},[progress,reduced]);
 function go(p:Page,id?:ApprovalId){Keyboard.dismiss();if(!nav.isReady())return;nav.dispatch(StackActions.push('Page',{page:p,id,root:false}));}
 function tab(p:Page,focusReview=false){
  Keyboard.dismiss();
  if(!nav.isReady())return;
  const current=nav.getCurrentRoute();
  const params={page:p,root:true,focusReview};
  if(current?.params.root&&current.params.page!==p&&tabs.some(name=>name===current.params.page)&&tabs.some(name=>name===p)){
   nav.dispatch({...CommonActions.setParams(params),source:current.key});
  }else nav.reset({index:0,routes:[{name:'Page',params}]});
 }
 function newConversation(){dispatch({type:'conversation',title:'New conversation'});setComposer('');closeHistory();tab('Partner')}
 function switchOrg(org:Org){
  if(state.org!==org){dispatch({type:'org',org});setComposer('')}
  setOrgMenu(false);
 }
 function routeURL(url:string){receivedLink.current=true;if(!nav.isReady()){pending.current=url;return}const path=(url.includes('/--/')?url.split('/--/')[1]:url.split('://')[1]||'').split('?')[0].replace(/^\/+|\/+$/g,'').toLowerCase();setModal('');setOrgMenu(false);setSidebar(false);progress.value=0;
 const aliases:Record<string,Page>={today:'Today',more:'More',partner:'Partner',settings:'Settings',reviews:'Reviews','approval-detail':'Approval detail',website:'Website',activity:'Activity',organization:'Organization',account:'Account',members:'Members & permissions','pinned-tab':'Pinned tab',reminders:'Approval reminders',privacy:'Privacy & data',help:'Help',appearance:'Appearance',about:'About',work:'Work',checklist:'Publish-ready checklist','quiet-hours':'Quiet hours','review-changes':'Review changes',project:'Project','preview-states':'Preview states','sign-in-methods':'Sign-in methods','contact-support':'Contact support'};
 if(path==='history'||path==='org-dropdown'||path==='history-empty'){tab('Today');showHistory(path==='history-empty'?'no-match-987':'');if(path==='org-dropdown')setOrgMenu(true)}
 else if(path==='today-review'){tab('Today',true)}
 else if(path==='attachments'){tab('Partner');setModal('Add attachment')}
 else if(path==='signout-confirmation'||path==='export-confirmation'){nav.reset({index:1,routes:[{name:'Page',params:{page:'More',root:true}},{name:'Page',params:{root:false,page:path==='signout-confirmation'?'Account':'Privacy & data'}}]});setModal(path==='signout-confirmation'?'Sign out':'Export data')}
 else if(__DEV__&&(path==='review-error'||path==='review-loading'||path==='activity-loading'||path==='activity-error')){setListMode(path.endsWith('error')?'error':'loading');nav.reset({index:1,routes:[{name:'Page',params:{page:'Today',root:true}},{name:'Page',params:{page:path.startsWith('activity')?'Activity':'Reviews',root:false}}]})}
 else if(path==='approval-confirmation'){tab('Today');go('Approval detail','intro');setModal('Approve:intro')}
 else if(path==='partner-composer'){tab('Partner');setComposer('Review the studio introduction')}
 else if(path==='partner-new'){newConversation()}
 else {const match=aliases[path]||allPages.find(p=>p.toLowerCase()===path);if(match){if(['Today','More','Partner','Work'].includes(match))tab(match);else {const parents:Partial<Record<Page,Page[]>>={'Approval detail':['Today','Reviews'],'Members & permissions':['More','Organization'],'Pinned tab':['More','Settings'],'Approval reminders':['More','Settings'],'Privacy & data':['More','Settings'],Appearance:['More','Settings'],About:['More','Settings'],'Quiet hours':['More','Settings','Approval reminders'],'Sign-in methods':['More','Account'],'Contact support':['More','Help'],'Publish-ready checklist':['More','Website'],'Review changes':['More','Website'],Project:['Work'],'Preview states':['More','Settings','About']};const stack=[...(parents[match]||[match==='Reviews'?'Today':'More']),match];nav.reset({index:stack.length-1,routes:stack.map((page,i)=>({name:'Page',params:{page,root:i===0}}))})}}}
 }
 useEffect(()=>{AccessibilityInfo.isReduceMotionEnabled().then(setSystemReduced);const sub=AccessibilityInfo.addEventListener('reduceMotionChanged',setSystemReduced);if(Platform.OS==='android')NavigationBar.setVisibilityAsync('hidden').catch(()=>{});return()=>sub.remove()},[]);
 useEffect(()=>{
  let active=true;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  showTabLabelsForLaunch().then(show=>{
   if(!active||!show)return;
   setIntroduceLabels(true);
   timeout=setTimeout(()=>setIntroduceLabels(false),TAB_LABEL_INTRO_MS);
  });
  return()=>{active=false;clearTimeout(timeout)};
 },[]);
 useEffect(()=>{AsyncStorage.getItem('whumpo-nav-v6').then(v=>{if(v)try{const p=JSON.parse(v);if(organizations.includes(p.org)&&p.pins)dispatch({type:'hydrate',org:p.org,pins:p.pins})}catch{}setReady(true)})},[]);
 useEffect(()=>{if(ready)AsyncStorage.setItem('whumpo-nav-v6',JSON.stringify({org:state.org,pins:state.pins}))},[ready,state.org,state.pins]);
 useEffect(()=>{const sub=Linking.addEventListener('url',e=>routeURL(e.url));Linking.getInitialURL().then(u=>{if(u&&!receivedLink.current)routeURL(u)});return()=>sub.remove()},[]);
 useEffect(()=>()=>{clearTimeout(timer.current);clearTimeout(feedbackTimer.current)},[]);
 useEffect(()=>{const back=BackHandler.addEventListener('hardwareBackPress',()=>{if(modal){setModal('');return true}if(orgMenu){setOrgMenu(false);return true}if(sidebar){closeHistory();return true}if(!nav.canGoBack()&&page!=='Today'){tab('Today');return true}return false});return()=>back.remove()},[modal,orgMenu,sidebar,closeHistory,page]);
 const overlayStyle=useAnimatedStyle(()=>({bottom:Math.max(0,-keyboardMotion.height.value)}));
 const scrimStyle=useAnimatedStyle(()=>({opacity:progress.value}));
 const historyPosition=useAnimatedStyle(()=>({opacity:progress.value,transform:[{translateX:reduced?0:(progress.value-1)*width}]}));
 const closePan=useMemo(()=>Gesture.Pan()
  .cancelsTouchesInView(false)
  .activeOffsetX([-16,16]).failOffsetY([-24,24])
  .onUpdate(e=>{if(!reduced)progress.value=Math.max(0,Math.min(1,1+e.translationX/width))})
  .onEnd((e,success)=>{
   const close=success&&(e.translationX<-width*.25||e.velocityX<-500);
   progress.value=settlePanel(close?0:1,reduced,e.velocityX/width,done=>{'worklet';if(done&&close)runOnJS(unmountHistory)()});
  }),[progress,width,reduced,unmountHistory]);
 const mountEdgeHistory=useCallback(()=>{Keyboard.dismiss();setHistorySearch('');setSidebar(true)},[]);
 const edgePan=useMemo(()=>Gesture.Pan()
  .enabled(hasTabs&&!modal)
  .onTouchesDown((e,manager)=>{if((e.allTouches[0]?.absoluteX??40)>=40)manager.fail()})
  .activeOffsetX(12).failOffsetX(-12).failOffsetY([-18,18])
  .onStart(()=>{if(!reduced){progress.value=0;runOnJS(mountEdgeHistory)()}})
  .onUpdate(e=>{if(!reduced)progress.value=Math.max(0,Math.min(1,e.translationX/width))})
  .onEnd((e,success)=>{
   const open=success&&(e.translationX>Math.min(72,width*.2)||e.velocityX>450);
   if(reduced){
    if(open){progress.value=1;runOnJS(mountEdgeHistory)()}
    return;
   }
   progress.value=settlePanel(open?1:0,false,e.velocityX/width,done=>{'worklet';if(done&&!open)runOnJS(unmountHistory)()});
  }),[progress,width,reduced,unmountHistory,mountEdgeHistory,hasTabs,modal]);
 const reload=()=>{setListMode('loading');timer.current=setTimeout(()=>setListMode('ready'),500)};
 const approvalModal=modal.startsWith('Approve:')||modal.startsWith('Changes:'),modalId=modal.split(':')[1] as ApprovalId,pin=modal.startsWith('Pin:')?modal.slice(4) as Destination:null;
 const title=approvalModal?(modal.startsWith('Approve:')?'Approve this change?':'Request changes?'):pin?'Pin to tab bar':modal;
 const footerStyle=useAnimatedStyle(()=>({paddingBottom:Math.max(8,inset.bottom)+(4-Math.max(8,inset.bottom))*keyboardMotion.progress.value,bottom:Math.max(0,-keyboardMotion.height.value)}));
 const footerFade=useAnimatedStyle(()=>({opacity:1-keyboardMotion.progress.value}));
 return <MotionContext.Provider value={reduced}><MotionEffectsContext.Provider value={!simplifyMotion}><KeyboardMotionContext.Provider value={keyboardMotion}><View style={s.root}><GestureDetector gesture={edgePan}><BlurTargetView ref={target} collapsable={false} style={s.root}>
 <ScreenContext.Provider value={{blocked:sidebar||!!modal,state,count,tabs,inset,composerHeight,showHistory,go,newConversation,reload,dispatch,setModal,listMode,setListMode,setComposer}}><BackdropContext.Provider value={sidebar}><NavigationContainer ref={nav} theme={{...DarkTheme,colors:{...DarkTheme.colors,background:c.canvas,card:c.canvas}}} onReady={()=>{if(pending.current){routeURL(pending.current);pending.current=null}}} onStateChange={()=>{setPage(nav.getCurrentRoute()?.params?.page||'Today')}}><Stack.Navigator initialRouteName="Page" screenOptions={({route})=>({headerShown:false,contentStyle:{backgroundColor:c.canvas},animation:reduced?'none':route.params.root?'none':'default',gestureEnabled:!route.params.root,fullScreenGestureEnabled:!route.params.root,animationDuration:motion.duration.pane})}><Stack.Screen name="Page" initialParams={{page:'Today',root:true}} component={Screen}/></Stack.Navigator></NavigationContainer></BackdropContext.Provider></ScreenContext.Provider>
 {footerPresence.mounted&&<Animated.View importantForAccessibility={sidebar||modal?'no-hide-descendants':'auto'} accessibilityElementsHidden={sidebar||!!modal} style={[s.footer,footerStyle]} pointerEvents={sidebar||!(hasTabs||support)?'none':'auto'}><Animated.View pointerEvents="none" style={[{position:'absolute',left:0,right:0,top:-40},footerFade]}><Fade height={90}/></Animated.View>{composerPresence.mounted&&<Animated.View pointerEvents={hasComposer?'auto':'none'} accessibilityElementsHidden={!hasComposer} importantForAccessibility={hasComposer?'auto':'no-hide-descendants'} style={[{marginHorizontal:24,marginBottom:4},composerPresence.style]}><Composer key={composerOnSupport?'support':'partner'} inputLabel={composerOnSupport?'Support request draft':undefined} label={composerOnSupport?'Save support draft in Partner':'Create draft in Partner'} onHeightChange={setComposerHeight} value={composerOnSupport?supportDraft:composer} onChange={composerOnSupport?setSupportDraft:setComposer} onAttach={composerPage.current==='Partner'?()=>{Keyboard.dismiss();setModal('Add attachment')}:undefined} onDraft={()=>{Keyboard.dismiss();dispatch({type:'draft',text:support?'Support request\n'+supportDraft:composer});setComposer('');if(support)setSupportDraft('');if(page!=='Partner')tab('Partner')}}/></Animated.View>}{tabPresence.mounted&&<Animated.View pointerEvents={hasTabs?'auto':'none'} style={tabPresence.style}><NavigationTabs introduceLabels={introduceLabels} keyboardVisible={keyboardVisible} tabs={tabs} page={page} count={count} onSelect={name=>{if(Platform.OS==='ios'&&!reduced&&page!==name)Haptics.selectionAsync().catch(()=>{});tab(name as Page)}}/></Animated.View>}</Animated.View>}
 </BlurTargetView></GestureDetector>
 {sidebar&&<GestureDetector gesture={closePan}>
  <Animated.View onAccessibilityEscape={closeHistory} accessibilityViewIsModal importantForAccessibility={modal?'no-hide-descendants':'yes'} style={[StyleSheet.absoluteFill,{zIndex:2},overlayStyle]}>
   <Animated.View pointerEvents="none" style={[StyleSheet.absoluteFill,scrimStyle]}>
    {!simplifyMotion&&<BlurView blurTarget={target} blurMethod="dimezisBlurViewSdk31Plus" intensity={65} blurReductionFactor={2.5} tint="dark" style={StyleSheet.absoluteFill}/>}
    <LinearGradient colors={simplifyMotion?['rgba(24,24,24,0.94)','rgba(8,8,8,0.92)']:['rgba(24,24,24,0.35)','rgba(8,8,8,0.55)']} start={{x:0,y:0}} end={{x:1,y:0}} style={StyleSheet.absoluteFill}/>
   </Animated.View>
   <Animated.View style={[{flex:1},historyPosition]}><HistoryPane key={historySearch} state={state} onClose={closeHistory} onSelect={t=>{dispatch({type:'conversation',title:t});closeHistory();tab('Partner')}} onNew={newConversation} onSettings={()=>{closeHistory();go('Settings')}} onOrg={switchOrg} onAddOrganization={()=>{setOrgMenu(false);Keyboard.dismiss();setModal('Add organization')}} dropdown={orgMenu} setDropdown={setOrgMenu} initialSearch={historySearch}/></Animated.View>
  </Animated.View>
 </GestureDetector>}
 {!!modal&&<SheetHost open={sheetOpen} reduced={reduced} onClose={closeSheet} onHidden={clearSheet}>{approvalModal&&<View style={{width:40,height:4,borderRadius:2,backgroundColor:c.muted,alignSelf:'center'}}/>}<View style={{flexDirection:'row',alignItems:'center',gap:12}}><Txt size={24} weight="600" style={{flex:1}}>{title}</Txt>{!approvalModal&&<IconButton label="Close dialog" icon="close" onPress={()=>setModal('')}/>}</View>
 {approvalModal?<><Txt color={c.muted}>{modal.startsWith('Approve:')?'It will be recorded. Publishing is a separate step.':'The change will stay in your required approvals.'}</Txt><Action label={modal.startsWith('Approve:')?'Approve':'Request changes'} primary onPress={()=>{dispatch({type:modal.startsWith('Approve:')?'approve':'changes',id:modalId});setFeedback(modal.startsWith('Approve:')?'Approved · awaiting publish step':'Changes requested');AccessibilityInfo.announceForAccessibility(modal.startsWith('Approve:')?'Approved. Awaiting a separate publish step.':'Changes requested. Approval is still required.');setModal('');clearTimeout(feedbackTimer.current);feedbackTimer.current=setTimeout(()=>setFeedback(''),2600);if(!reduced)Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(()=>{})}}/><Action label="Cancel" onPress={()=>setModal('')}/>
 </>:pin?<><Txt color={c.muted}>Replace the flexible tab with {pin}. The other destinations stay in More.</Txt><Action label={`Pin ${pin} to tab bar`} primary onPress={()=>{if(isPinnable(pin))dispatch({type:'pin',destination:pin});setModal('')}}/></>:modal==='Organizations'?<><Txt color={c.muted}>Conversations and approvals stay with their organization.</Txt><Surface>{organizations.map((org,i)=><Row key={org} title={org} value={org===state.org?'Active':undefined} onPress={()=>{switchOrg(org);setModal('')}} last={i===1}/>)}</Surface></>:modal==='Add organization'?<><Txt color={c.muted}>Organization creation is unavailable in this preview. You can switch between the sample organizations.</Txt><Action label="Done" onPress={closeSheet}/></>:modal==='Add attachment'?<Surface>{[['image','Photo or image'],['file','Document'],['Website','Website draft']].map(([icon,label],i)=><Row key={label} title={label} icon={icon as 'image'} onPress={()=>{setComposer(v=>(v?v+'\n':'')+(label==='Website draft'?'[Website draft attached]':`[Sample ${label.toLowerCase()} attached]`));setModal('')}} last={i===2}/>)}</Surface>:modal==='Sign out'?<><Txt color={c.muted}>Sign out of this preview account on this device? Your sample workspace will still be available.</Txt><Action label="Confirm sign out" primary onPress={()=>{dispatch({type:'signout'});setModal('')}}/></>:modal==='Export data'?<><Txt color={c.muted}>Record a local export request? This preview does not send a request or create an external download.</Txt><Action label="Request export" primary onPress={()=>{dispatch({type:'export'});setModal('')}}/></>:modal==='Reset sample data'?<><Txt color={c.muted}>Restore the two required approvals and clear session drafts? Your pinned tab choices stay in place.</Txt><Action label="Restore sample data" primary onPress={()=>{dispatch({type:'resetData'});setModal('')}}/></>:null}
 </SheetHost>}
 {feedback!==''&&<Animated.View entering={reduced?undefined:FadeInUp.duration(motion.duration.toastIn)} exiting={reduced?undefined:FadeOut.duration(motion.duration.toastOut)} pointerEvents="none" accessibilityLiveRegion="polite" style={{position:'absolute',left:24,right:24,bottom:Math.max(30,inset.bottom+12),backgroundColor:c.surface,padding:16,borderRadius:20,zIndex:4}}><Txt weight="500">{feedback}</Txt></Animated.View>}
 <CompactProbe/></View></KeyboardMotionContext.Provider></MotionEffectsContext.Provider></MotionContext.Provider>;
}
const s=StyleSheet.create({deck:{flex:1,overflow:'hidden',backgroundColor:c.canvas},pane:{...StyleSheet.absoluteFill},root:{flex:1,backgroundColor:'#000000'},footer:{zIndex:1,position:'absolute',bottom:0,left:0,right:0,backgroundColor:'#000000',justifyContent:'flex-end'},scrim:{...StyleSheet.absoluteFill,zIndex:3,justifyContent:'flex-end'},sheet:{maxHeight:'84%',flexGrow:0,borderTopLeftRadius:28,borderTopRightRadius:28,backgroundColor:c.surface}});
