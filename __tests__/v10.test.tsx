import {test,expect,jest} from '@jest/globals';
import {render,screen,fireEvent,act} from '@testing-library/react-native';
import {Keyboard,StyleSheet} from 'react-native';
import {Composer,SEND_DIAMETER,SEND_RADIUS} from '../src/ui';
import {Today} from '../src/screens';
import {Details} from '../src/details';
import {NavigationTabs} from '../src/NavigationChrome';
import {useKeyboardVisible} from '../src/keyboard';
import {initialState,reducer,chipVariant,todayApprovalCopy} from '../src/model';
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,right:0,bottom:0})}));
const base={state:initialState,dispatch:jest.fn(),go:jest.fn(),sheet:jest.fn(),approvalId:'intro' as const,listMode:'ready' as const,reload:jest.fn(),setListMode:jest.fn()};
test('send face is an explicit40dp circle inside48dp target in both states',async()=>{
 const r=await render(<Composer value="" onChange={()=>{}} onDraft={()=>{}}/>);
 expect(StyleSheet.flatten(screen.getByTestId('composer-send-disc').props.style)).toMatchObject({width:40,height:40,borderRadius:20,overflow:'hidden'});
 expect(SEND_RADIUS*2).toBe(SEND_DIAMETER);expect(screen.getByRole('button',{name:'Create draft in Partner'}).props.accessibilityState.disabled).toBe(true);
 await r.rerender(<Composer value="A draft" onChange={()=>{}} onDraft={()=>{}}/>);
 expect(screen.getByRole('button',{name:'Create draft in Partner'}).props.accessibilityState.disabled).toBe(false);
 expect(StyleSheet.flatten(screen.getByRole('button',{name:'Create draft in Partner'}).props.style)).toMatchObject({width:48,height:48});
});
test('multiline composer caps at five lines and Return never sends',async()=>{
 const send=jest.fn();await render(<Composer value="one\ntwo\nthree\nfour\nfive\nsix" onChange={()=>{}} onDraft={send}/>);
 const input=screen.getByLabelText('Ask your Partner, draft only');await fireEvent(screen.getByTestId('composer-text-measure',{includeHiddenElements:true}),'textLayout',{nativeEvent:{lines:Array.from({length:12},()=>({height:44.2}))}});
 expect(StyleSheet.flatten(screen.getByLabelText('Ask your Partner, draft only').props.style).height).toBeCloseTo(22.1*2*5+16);
 expect(input.props.multiline).toBe(true);expect(input.props.submitBehavior).toBe('newline');await fireEvent(input,'submitEditing');expect(send).not.toHaveBeenCalled();
});
test('explicit newlines grow composer even without native contentSize callback',async()=>{
 await render(<Composer value={"one\ntwo\nthree"} onChange={()=>{}} onDraft={()=>{}}/>);expect(StyleSheet.flatten(screen.getByTestId('composer-input').props.style).height).toBeCloseTo(22.1*2*3+16);
});
test('measured wrap count is bounded and clears after sending',async()=>{
 const r=await render(<Composer value="Long wrapped paragraph" onChange={()=>{}} onDraft={()=>{}}/>);await fireEvent(screen.getByTestId('composer-text-measure',{includeHiddenElements:true}),'textLayout',{nativeEvent:{lines:Array.from({length:99},()=>({height:44.2}))}});expect(StyleSheet.flatten(screen.getByTestId('composer-input').props.style).height).toBeCloseTo(237);await r.rerender(<Composer value="" onChange={()=>{}} onDraft={()=>{}}/>);expect(StyleSheet.flatten(screen.getByTestId('composer-input').props.style).height).toBeCloseTo(60.2);
});
test('native keyboard events hide and restore the actual tab strip',async()=>{
 const listeners:Record<string,()=>void>={};const spy=jest.spyOn(Keyboard,'addListener').mockImplementation((name,fn)=>{listeners[name]=fn as ()=>void;return {remove:jest.fn()}});jest.spyOn(Keyboard,'isVisible').mockReturnValue(false);
 function Dock(){const visible=useKeyboardVisible();return <NavigationTabs keyboardVisible={visible} tabs={['Today','Partner','Work','More']} page="Today" count={2} onSelect={()=>{}}/>}
 await render(<Dock/>);expect(screen.getByTestId('floating-tabs')).toBeTruthy();await act(()=>listeners.keyboardDidShow());expect(screen.queryByTestId('floating-tabs')).toBeNull();await act(()=>listeners.keyboardDidHide());expect(screen.getByTestId('floating-tabs')).toBeTruthy();spy.mockRestore();
});
test('remaining item drives Today and no pending items has calm Activity route',async()=>{
 const one=reducer(initialState,{type:'approve',id:'intro'}),copy=todayApprovalCopy(one);expect(copy.title).toBe('Review service descriptions');expect(copy.body).toBe('More specific descriptions for three services.');
 const go=jest.fn(),r=await render(<Today state={one} onOpen={go}/>);expect(screen.getByText(copy.title)).toBeTruthy();expect(screen.queryByText('Review website changes')).toBeNull();
 const done=reducer(one,{type:'approve',id:'services'});await r.rerender(<Today state={done} onOpen={go}/>);expect(screen.getByText('Nothing needs you right now')).toBeTruthy();await fireEvent.press(screen.getByRole('button',{name:'View Activity'}));expect(go).toHaveBeenCalledWith('Activity');
});
test('Reviews separates pending and neutral approved records, then resolves',async()=>{
 const one=reducer(initialState,{type:'approve',id:'intro'});const r=await render(<Details {...base} state={one} page="Reviews"/>);
 expect(screen.getByText('Needs your decision')).toBeTruthy();expect(screen.getByText('Approved')).toBeTruthy();expect(screen.getByText('1 decision needs you')).toBeTruthy();expect(screen.getByTestId('chip-neutral')).toBeTruthy();expect(screen.queryByText('Required approvals stay in this list until you make a decision.')).toBeNull();
 await r.rerender(<Details {...base} state={reducer(one,{type:'approve',id:'services'})} page="Reviews"/>);expect(screen.queryByText('Needs your decision')).toBeNull();expect(screen.getByText('Nothing needs you right now')).toBeTruthy();
});
test('chip semantics reserve amber for action, green for verified samples',()=>{
 for(const s of ['Signed in','Draft, not published','Nothing is published','Approved, awaiting publish step','Sample','Draft · not sent'])expect(chipVariant(s)).toBe('neutral');
 expect(chipVariant('Awaiting approval')).toBe('action');expect(chipVariant('Changes requested')).toBe('action');expect(chipVariant('Approvals still required')).toBe('action');expect(chipVariant('Sample check')).toBe('verified');expect(chipVariant('Error')).toBe('destructive');
});
test('Settings is a complete account hub and reset is only under About',async()=>{
 const r=await render(<Details {...base} page="Settings"/>);await fireEvent.press(screen.getByRole('button',{name:'Alex Morgan'}));expect(base.go).toHaveBeenCalledWith('Account');expect(screen.queryByText('Reset sample data')).toBeNull();
 await r.rerender(<Details {...base} page="Privacy & data"/>);expect(screen.queryByText('Reset sample data')).toBeNull();await r.rerender(<Details {...base} page="About"/>);await fireEvent.press(screen.getByRole('button',{name:'Reset sample data'}));expect(base.sheet).toHaveBeenCalledWith('Reset sample data');
});

test('every selected tab uses one fully round pill and badges anchor to its wrapper',async()=>{
 const r=await render(<NavigationTabs keyboardVisible={false} tabs={['Today','Partner','Reviews','More']} page="Today" count={2} onSelect={()=>{}}/>);
 for(const page of ['Today','Partner','Reviews','More']){await r.rerender(<NavigationTabs keyboardVisible={false} tabs={['Today','Partner','Reviews','More']} page={page} count={2} onSelect={()=>{}}/>);expect(StyleSheet.flatten(screen.getByTestId('selected-tab-pill').props.style)).toMatchObject({width:66,height:44,borderRadius:100,backgroundColor:'#2A2A2A'});expect(screen.getByRole('tab',{name:page==='Reviews'?'Pinned tab, Reviews':page}).props.accessibilityState.selected).toBe(true)}
});
