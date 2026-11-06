"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fallback = require("./fallback_map.js");
const s = {lat:44,lon:7,heading_deg:0,turn_m:75,state:"navigating",maneuver:"roundabout",has_turn:true};
test("zoom segue la regola attuale del presenter (100m a 0m)",()=>{
  assert.equal(fallback.zoomFactor(s,true),1.75);
  assert.equal(fallback.zoomFactor({...s,turn_m:0},true),4);
  assert.equal(fallback.zoomFactor({...s,turn_m:110},true),1);
  assert.equal(fallback.zoomFactor({...s,maneuver:"continue"},true),1);
  assert.equal(fallback.zoomFactor(s,false),1);
});
test("proietta rider al riferimento e nord verso l'alto",()=>{
  const r=fallback.project([44,7],s,1);
  assert.equal(r[0],233);
  assert.ok(Math.abs(r[1]-(196*466/360))<.001);
  const n=fallback.project([44.0001,7],s,1);
  assert.ok(n[1]<r[1]);
});
