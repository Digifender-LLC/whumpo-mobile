import {expect,jest,test} from '@jest/globals';
import {motion,settlePanel,transition} from '../src/motion';
import {updateMotionBudget} from '../src/motionBudget';

test('reduced motion jumps to the destination and finishes without waiting for animation',()=>{
 const complete=jest.fn();
 expect(transition(1,motion.duration.sheetIn,true,complete)).toBe(1);
 expect(settlePanel(0,true,2,complete)).toBe(0);
 expect(complete.mock.calls).toEqual([[true],[true]]);
});

test('the effects budget ignores short samples and recovers after three clean transitions',()=>{
 const normal={simple:false,clean:0};
 expect(updateMotionBudget(normal,8,6)).toBe(normal);
 const simplified=updateMotionBudget(normal,7,22);
 expect(simplified).toEqual({simple:true,clean:0});
 expect(updateMotionBudget(simplified,7,22)).toBe(simplified);
 const clean=updateMotionBudget(simplified,0,25);
 expect(updateMotionBudget(clean,3,25)).toEqual(simplified);
 expect(updateMotionBudget(updateMotionBudget(clean,1,26),0,24)).toEqual(normal);
});
