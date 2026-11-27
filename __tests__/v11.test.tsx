import {test,expect,jest} from '@jest/globals';
import {render,screen,fireEvent} from '@testing-library/react-native';
import {Dimensions,StyleSheet} from 'react-native';
import {compactState,KeyboardMotionContext,KeyboardMotion} from '../src/keyboardMotion';
import {AdaptiveHeader,HeaderAction} from '../src/AdaptiveHeader';
import {Today} from '../src/screens';
import {Badge,Composer} from '../src/ui';
import {Details} from '../src/details';
import {initialState,reducer} from '../src/model';
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0})}));
const motion=(p:number,reduced=false)=>({progress:{value:p},height:{value:p?-335:0},compact:p===1,semanticCompact:p===1,settled:p,reduced} as KeyboardMotion);
const base={state:initialState,dispatch:jest.fn(),go:jest.fn(),sheet:jest.fn(),approvalId:'intro' as const,listMode:'ready' as const,reload:jest.fn(),setListMode:jest.fn()};
test('one native progress maps closed/open and staggered compact states',()=>{
 expect(compactState(0)).toMatchObject({rearOpacity:1,titleOpacity:1,greetingOpacity:1,tabsOpacity:1,headerSpace:48,tabsSpace:64,chipY:48,layout:0});
 expect(compactState(1)).toMatchObject({rearOpacity:0,titleOpacity:0,greetingOpacity:0,tabsOpacity:0,headerSpace:0,tabsSpace:0,chipY:0,layout:1});
 expect(compactState(.5).rearOpacity).toBe(0);expect(compactState(.5).greetingOpacity).toBeGreaterThan(0);expect(compactState(.5).tabsOpacity).toBeGreaterThan(compactState(.5).greetingOpacity);
 for(let p=.001;p<=1;p+=.001){expect(Math.abs(compactState(p).chipY-compactState(p-.001).chipY)).toBeLessThan(.08);expect(Math.abs(compactState(p).rearOpacity-compactState(p-.001).rearOpacity)).toBeLessThan(.003)}
});
test('reduced path switches instantly without translations or fades',()=>{
 for(const p of [0,.2,.5,.8,1])expect(compactState(p,true)).toMatchObject({chipY:0,rearY:0,tabsY:0});
 expect(compactState(.5,true).contentOpacity).toBe(1);expect(compactState(0,true).chipTop).toBe(48);expect(compactState(1,true).chipTop).toBe(0);
});
test('Today retains one organization chip and hides faded semantics in compact mode',async()=>{
 const r=await render(<KeyboardMotionContext.Provider value={motion(1)}><AdaptiveHeader title="Today" org="Studio North" conversation="Website launch" left={null} right={null} top={0} onOrganization={()=>{}}/><Today state={initialState} onOpen={()=>{}}/></KeyboardMotionContext.Provider>);
 expect(screen.getByRole('header',{name:'Today'}).props.maxFontSizeMultiplier).toBe(1.2);expect(screen.queryByText('Hi, Alex')).toBeNull();expect(screen.queryByText('Next · Website draft')).toBeNull();expect(screen.getAllByRole('button',{name:'Switch organization, Studio North'})).toHaveLength(1);
 expect(screen.getByRole('button',{name:'Review 2 decisions'})).toBeTruthy();
 await r.rerender(<KeyboardMotionContext.Provider value={motion(0)}><AdaptiveHeader title="Today" org="Studio North" conversation="Website launch" left={null} right={null} top={0} onOrganization={()=>{}}/><Today state={initialState} onOpen={()=>{}}/></KeyboardMotionContext.Provider>);
 expect(screen.getByText('Today')).toBeTruthy();expect(screen.getByText('Hi, Alex')).toBeTruthy();expect(screen.getByTestId('rear-card-globe')).toBeTruthy();
});
test('enlarged Today shows only the relevant summary while the keyboard is open',async()=>{
 const window=Dimensions.get('window'),display=Dimensions.get('screen');
 const zoomedWindow={...window,fontScale:2};
 Dimensions.set({window:zoomedWindow,screen:{...display,fontScale:2}});
 const onOpen=jest.fn();
 const onePending=reducer(initialState,{type:'approve',id:'intro'});
 const resolved=reducer(onePending,{type:'approve',id:'services'});
 const today=(p:number,state=initialState)=><KeyboardMotionContext.Provider value={motion(p)}><Today state={state} onOpen={onOpen}/></KeyboardMotionContext.Provider>;
 try {
  const view=await render(today(1,onePending));
  expect(screen.queryByTestId('approval-stack')).toBeNull();
  expect(screen.queryByText('Hi, Alex')).toBeNull();
  await fireEvent.press(screen.getByRole('button',{name:'Review 1 decision'}));
  expect(onOpen).toHaveBeenCalledWith('Reviews');
  await view.rerender(today(1,resolved));
  expect(screen.getByText('Hi, Alex')).toBeTruthy();
  expect(screen.queryByRole('button',{name:'View Activity'})).toBeNull();
  await view.rerender(today(0));
  expect(screen.getByTestId('approval-stack')).toBeTruthy();
  expect(screen.queryByTestId('today-keyboard-summary')).toBeNull();
 } finally {
  Dimensions.set({window,screen:display});
 }
});
test('Settings uses the circular Help header action, no duplicate Help row',async()=>{
 const help=jest.fn();const r=await render(<HeaderAction page="Settings" count={2} onReviews={()=>{}} onNew={()=>{}} onSettings={()=>{}} onHelp={help}/>);await fireEvent.press(screen.getByRole('button',{name:'Help'}));expect(help).toHaveBeenCalled();expect(screen.getByTestId('icon-Help')).toBeTruthy();
 await r.rerender(<Details {...base} page="Settings"/>);expect(screen.queryByRole('button',{name:'Help'})).toBeNull();expect(screen.getByRole('button',{name:'About'})).toBeTruthy();
});
test('single-line Return saves only a draft; multiline remains newline and disabled cannot send',async()=>{
 const save=jest.fn();const r=await render(<Composer value="Review this" onChange={()=>{}} onDraft={save}/>);const input=screen.getByLabelText('Ask your Partner, draft only');expect(input.props.returnKeyType).toBe('send');await fireEvent(input,'submitEditing');expect(save).toHaveBeenCalledTimes(1);
 await r.rerender(<Composer value={'one\ntwo'} onChange={()=>{}} onDraft={save}/>);expect(screen.getByLabelText('Ask your Partner, draft only').props.submitBehavior).toBe('newline');await fireEvent(screen.getByLabelText('Ask your Partner, draft only'),'submitEditing');expect(save).toHaveBeenCalledTimes(1);
 await r.rerender(<Composer value="" onChange={()=>{}} onDraft={save}/>);await fireEvent(screen.getByLabelText('Ask your Partner, draft only'),'submitEditing');expect(save).toHaveBeenCalledTimes(1);
});
test('10+ badge caps visual numeral, preserves exact accessible count and fixed chrome',async()=>{
 await render(<Badge count={42}/>);const text=screen.getByText('9+');expect(text.props.allowFontScaling).toBe(false);expect(screen.getByLabelText('42 approvals awaiting your decision')).toBeTruthy();expect(StyleSheet.flatten(text.props.style).fontSize).toBe(12);
});
test('Reviews and Activity loading use meaningful static skeletons',async()=>{
 const r=await render(<Details {...{...base,listMode:"loading" as const}} page="Reviews"/>);expect(screen.getByRole('progressbar',{name:'Loading your reviews'})).toBeTruthy();await r.rerender(<Details {...{...base,listMode:"loading" as const}} page="Activity"/>);expect(screen.getByRole('progressbar',{name:'Loading activity'})).toBeTruthy();
});

test('duplicate native line-layout notifications do not re-report composer height',async()=>{
 const report=jest.fn();await render(<Composer value="A wrapped local draft" onChange={()=>{}} onDraft={()=>{}} onHeightChange={report}/>);
 const text=screen.getByTestId('composer-text-measure',{includeHiddenElements:true});
 await fireEvent(text,'textLayout',{nativeEvent:{lines:Array.from({length:8},()=>({height:22}))}});const calls=report.mock.calls.length;
 for(let i=0;i<100;i++)await fireEvent(text,'textLayout',{nativeEvent:{lines:Array.from({length:8},()=>({height:22}))}});
 expect(report.mock.calls.length).toBe(calls);
});

test('a stale native text-layout event cannot collapse a newly wrapped draft',async()=>{
 await render(<Composer value="one two three four five six" onChange={()=>{}} onDraft={()=>{}}/>);const text=screen.getByTestId('composer-text-measure',{includeHiddenElements:true});
 await fireEvent(text,'textLayout',{nativeEvent:{lines:[{text:'one two '},{text:'three four '},{text:'five six'}]}});const height=StyleSheet.flatten(screen.getByTestId('composer-input').props.style).height;
 await fireEvent(text,'textLayout',{nativeEvent:{lines:[{text:'one'}]}});expect(StyleSheet.flatten(screen.getByTestId('composer-input').props.style).height).toBe(height);
});
