"use strict";
const test=require("node:test"),assert=require("node:assert/strict");
const h=require("./heading_stability.js");
const frame=(t,heading,options={})=>({t,route:{token:1,generation:1},
  snapshot:{heading_deg:heading,speed_mps:14,state:"navigating",
    maneuver:"continue",...options}});
test("circular angles interpolate across north",()=>{
 assert.ok(Math.abs(h.diff(1,359)-2)<1e-9);
 assert.ok(Math.abs(h.interpolate(359,1,0.5))<1e-9);
});
test("straight heading jitter is attenuated",()=>{
 const f=[frame(0,90),frame(250,91),frame(500,89.5),frame(750,90.8)];
 const v=h.build(f);
 assert.equal(v[0],90);
 assert.ok(Math.abs(h.diff(v.at(-1),90))<0.5);
});
test("roundabout responds promptly to turning",()=>{
 const f=[frame(0,340),frame(250,350,{maneuver:"roundabout"}),
  frame(500,3,{maneuver:"roundabout"})];
 const v=h.build(f);
 assert.ok(Math.abs(h.diff(v[2],3))<3);
 assert.ok(Math.abs(h.at(f,v,375)-h.interpolate(v[1],v[2],0.5))<1e-6);
});
test("gap reanchors and seek interpolates deterministically",()=>{
 const f=[frame(0,90),frame(1000,91),frame(5000,180)];
 const v=h.build(f);
 assert.equal(v[2],180);
 assert.equal(h.at(f,v,5100),180);
 assert.equal(h.at(f,v,2000),v[1]);
});

test("rapid isolated heading jump is not a turn in stabilized mode",()=>{
 const f=[frame(0,90),frame(250,90),frame(500,110),frame(750,90)];
 const v=h.build(f);
 assert.ok(Math.abs(h.diff(v[2],90))<10);
});
test("GPS validated mode ignores heading jumps without motion",()=>{
 const loc={lat:45,lon:9};
 const f=[0,250,500,750,1000].map((t,i)=>({t,route:{token:1,generation:1},
  snapshot:{...loc,heading_deg:i===2?155:90,speed_mps:14,
   state:"navigating",maneuver:"continue"}}));
 const v=h.buildCourseValidated(f);
 assert.ok(v.every(x=>Math.abs(h.diff(x,90))<0.001));
});
test("GPS validated mode rejects non-causal heading jump on straight fixes",()=>{
 const f=[];
 for(let i=0;i<7;i++)f.push({t:i*1000,route:{token:1,generation:1},
  snapshot:{lat:45,lon:9+i*0.00018,heading_deg:i===4?120:90,
    speed_mps:14,state:"navigating",maneuver:"continue"}});
 const v=h.buildCourseValidated(f);
 assert.ok(Math.abs(h.diff(v[4],90))<4);
});
test("GPS verified heading follows genuine track turn",()=>{
 const coords=[[45,9],[45,9.0002],[45,9.0004],[45.00014,9.0004],
  [45.00028,9.0004],[45.00042,9.0004]];
 const f=coords.map(([lat,lon],i)=>({t:i*1000,route:{token:1,generation:1},
  snapshot:{lat,lon,heading_deg:i<3?90:0,speed_mps:14,
    state:"navigating",maneuver:"continue"}}));
 const v=h.buildCourseValidated(f);
 assert.ok(Math.abs(h.diff(v.at(-1),0))<15);
});
