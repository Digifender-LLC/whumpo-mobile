import {test,expect,jest} from '@jest/globals';
import {render,fireEvent,screen} from '@testing-library/react-native';
import {HeaderTitle,OrganizationChip} from '../src/Chrome';
import {ApprovalCard,Today} from '../src/screens';
import {StyleSheet} from 'react-native';
import {Badge,styles} from '../src/ui';
import {colors,initialState,reducer} from '../src/model';
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0})}));

test('screen titles share the semibold capped chrome rule and Today chip opens the organization chooser',async()=>{
 const choose=jest.fn();await render(<><HeaderTitle title="Today"/><OrganizationChip org="Studio North" onPress={choose}/></>);
 expect(screen.getByRole('header',{name:'Today'}).props.maxFontSizeMultiplier).toBe(1.2);
 await fireEvent.press(screen.getByRole('button',{name:'Switch organization, Studio North'}));expect(choose).toHaveBeenCalledTimes(1);
});
test('approval action is an explicit filled primary control without a redundant pending chip',async()=>{
 const review=jest.fn();await render(<ApprovalCard large onReview={review}/>);
 expect(screen.queryByText('Awaiting approval')).toBeNull();expect(screen.queryByLabelText('2 approvals awaiting your decision')).toBeNull();expect(screen.getByText('Next · Website draft')).toBeTruthy();
 const action=screen.getByRole('button',{name:'Review 2 decisions'});
 expect(StyleSheet.flatten(action.props.style)).toMatchObject({minHeight:48});
 expect(screen.getByText('Review 2 decisions').parent?.props.style).toMatchObject({backgroundColor:colors.gold});
 expect(screen.getByText('Review website changes').props.maxFontSizeMultiplier).toBeUndefined();
 await fireEvent.press(action);expect(review).toHaveBeenCalledTimes(1);
});
test('badge scaling is capped independently from uncapped content',async()=>{
 await render(<Badge/>);expect(screen.getByText('2').props.maxFontSizeMultiplier).toBe(1.15);expect(styles.iconButton.width).toBe(48);
});
test('pending approvals use a compact greeting; only the empty state restores the centered question',async()=>{
 const result=await render(<Today onOpen={()=>{}} state={initialState}/>);
 expect(screen.queryByText('What needs you today?')).toBeNull();
 expect(screen.queryByText('Your work')).toBeNull();expect(screen.queryByText('Launch plan')).toBeNull();
 expect(screen.getByText('Hi, Alex')).toBeTruthy();
 expect(screen.getByRole('button',{name:'Review 2 decisions'})).toBeTruthy();
 await result.rerender(<Today onOpen={()=>{}} state={reducer(reducer(initialState,{type:'approve',id:'intro'}),{type:'approve',id:'services'})}/>);
 expect(screen.getByText('What needs you today?')).toBeTruthy();
 expect(screen.getByText('What needs you today?').props.style).toEqual(expect.arrayContaining([expect.objectContaining({textAlign:'center'})]));
 expect(screen.queryByText('Needs your approval')).toBeNull();
});
