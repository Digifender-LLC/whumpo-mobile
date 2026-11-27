import {expect,jest,test} from '@jest/globals';
import {fireEvent,render,screen} from '@testing-library/react-native';
import {NavigationTabs} from '../src/NavigationChrome';
import {KeyboardMotionContext,useKeyboardMotion} from '../src/keyboardMotion';
import {createTabLabelLaunchTracker} from '../src/tabLabels';

test('only the first three cold launches introduce tab labels, with one update per launch',async()=>{
 let saved:string|null=null;
 let writes=0;
 const storage={getItem:async()=>saved,setItem:async(_key:string,value:string)=>{saved=value;writes++}};
 for(const visible of [true,true,true,false,false]){
  const launch=createTabLabelLaunchTracker(storage);
  expect(await Promise.all([launch(),launch()])).toEqual([visible,visible]);
 }
 expect(writes).toBe(3);
 expect(saved).toBe('3');
});

test('a long press shows the destination label without navigating, including with Reduce Motion',async()=>{
 const select=jest.fn();
 function Tabs(){
  const motion=useKeyboardMotion();
  return <KeyboardMotionContext.Provider value={{...motion,reduced:true}}>
   <NavigationTabs keyboardVisible={false} tabs={['Today','Partner','Work','More']} page="Today" count={2} onSelect={select}/>
  </KeyboardMotionContext.Provider>;
 }
 await render(<Tabs/>);
 expect(screen.queryByTestId('tab-label-Work',{includeHiddenElements:true})).toBeNull();
 await fireEvent(screen.getByRole('tab',{name:'Pinned tab, Work'}),'longPress');
 expect(screen.getByTestId('tab-label-Work',{includeHiddenElements:true}).props.exiting).toBeUndefined();
 expect(select).not.toHaveBeenCalled();
});
