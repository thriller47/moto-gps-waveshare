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
