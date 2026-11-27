import {readFileSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
import {test,expect} from '@jest/globals';
import {iconMotion} from '../src/iconMotion';

test('screen and component code use the shared icon renderer',()=>{
 const files=['App.tsx','index.ts',...readdirSync('src',{recursive:true,encoding:'utf8'}).filter(path=>/\.[jt]sx?$/.test(path)).map(path=>join('src',path))];
 const offenders=files.filter(file=>file!=='src/iconGeometry.ts'&&
  /(?:from\s*|(?:import|require)\s*\(\s*|import\s*)['"]lucide-react-native(?:\/|['"])/.test(readFileSync(file,'utf8')));
 expect(offenders).toEqual([]);
});

test('every pinned Lucide glyph has part motion and only references existing nodes',()=>{
 const source=readFileSync('src/iconGeometry.ts','utf8');
 const glyphs=Array.from(source.matchAll(/^\s*(\w+):\{data:\w+,lengths:\[([^\]]+)\]/gm));
 expect(Object.keys(iconMotion).sort()).toEqual(glyphs.map(([,key])=>key).sort());
 for(const [,key,strokes] of glyphs){
  const parts=iconMotion[key as keyof typeof iconMotion];
  expect(parts.length).toBeGreaterThan(0);
  const indices=parts.flatMap(part=>[...part.nodes]);
  expect(new Set(indices).size).toBe(indices.length);
  for(const part of parts){
   expect(part.nodes.every(index=>index>=0&&index<strokes.split(',').length)).toBe(true);
  }
 }
});
