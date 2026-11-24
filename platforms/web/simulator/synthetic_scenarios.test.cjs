"use strict";
const assert=require("node:assert/strict");
const test=require("node:test");
const model=require("./trace_model.js");
const scenarios=require("./synthetic_scenarios.js");
for(const id of Object.keys(scenarios.titles))test("synthetic "+id,()=>{
  const data=model.parse(scenarios.create(id));
  assert.equal(data.frames.at(-1).snapshot.state,"arrived");
  assert.equal(data.events.filter(e=>e.kind==="reroute").length,id==="reroute"?1:0);
  assert.ok(data.frames.every(f=>f.route.points.length<=24));
  if(id==="tunnel")assert.ok(data.frames.some((f,i)=>i>0&&f.t-data.frames[i-1].t>=8000));
  if(id==="reconnect")assert.ok(data.frames.some(f=>f.snapshot.network==="offline"));
});
