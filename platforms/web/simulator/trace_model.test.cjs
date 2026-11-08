"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const model = require("./trace_model.js");
const event = (type, t_ms, extras = {}) => JSON.stringify({type, t_ms, ...extras});
const route = (t, token, points = [[44, 7],[44.1,7.1]]) =>
  event("route", t, {route_token:token, generation:1, origin:[44,7], points});
const snapshot = (t, extras = {}) => event("snapshot", t, {
  lat:44, lon:7, state:"navigating", maneuver:"turn_left", turn_m:20,
  speed_mps:8, heading_deg:0, ...extras
});

test("la stessa rotta trasmessa piu volte non conta come ricalcolo", () => {
  const s = [event("meta",0,{schema:"moto-replay-v1"}), route(2,42),
    snapshot(3), route(4,42), snapshot(5), route(6,99), snapshot(7)].join("\n");
  const result = model.parse(s);
  assert.equal(result.frames.length,3);
  assert.deepEqual(result.events.map(e=>e.kind),["start","reroute"]);
  assert.equal(result.events[1].frameIndex,2);
  assert.equal(result.frames[0].route.token,42);
  assert.equal(result.frames[2].route.token,99);
});

test("tappa, rotatoria e arrivo mantengono ordine e tempo", () => {
  const data = {schema:"moto-replay-v1",events:[
    {type:"meta",t_ms:0,schema:"moto-replay-v1"},
    {type:"route",t_ms:1,route_token:7,generation:1,points:[[44,7],[44.1,7]]},
    {type:"snapshot",t_ms:10,lat:44,lon:7,state:"navigating",maneuver:"roundabout"},
    {type:"waypoint",t_ms:11,reached:1,total:2},
    {type:"snapshot",t_ms:20,lat:44,lon:7,state:"arrived",maneuver:"arrive"}
  ]};
  const r = model.parse(JSON.stringify(data));
  assert.deepEqual(r.events.map(x=>x.kind),["start","roundabout","waypoint","arrived"]);
  assert.equal(r.events[2].frameIndex,1);
  assert.equal(r.durationMs,10);
});

test("scene limitata a 24 strade e 192 vertici, senza inventare dati", () => {
  const roads = Array.from({length:30},()=>({road_class:3,
    points:Array.from({length:20},(_,i)=>[44+i/10000,7])}));
  const scene = model.sceneFrom({revision:1,roads});
  assert.ok(scene.roads.length <= 24);
  assert.ok(scene.points <= 192);
  assert.equal(scene.points,192);
  assert.ok(scene.roads.every(r=>r.points.length>=2));
});

test("rifiuta schema estraneo e snapshot senza coordinate valide", () => {
  assert.throws(()=>model.parse('{"schema":"altra-v1","events":[]}'),/Schema/);
  assert.throws(()=>model.parse([route(0,3),snapshot(1,{lat:120})].join("\n")),/Nessuno snapshot/);
});

test("ricerca binaria del frame: prima posizione maggiore o uguale al tempo",()=>{
  const frames=[{t:100},{t:220},{t:260}];
  assert.equal(model.findFrameAtOrAfter(frames,200),1);
  assert.equal(model.findFrameAtOrAfter(frames,260),2);
  assert.equal(model.findFrameAtOrAfter(frames,300),2);
  assert.equal(model.findFrameAtOrAfter([],100),-1);
});

test("ritrasmissioni identiche non ricreano route e scene tra snapshot",()=>{
  const a=[event("meta",0,{schema:"moto-replay-v1"}),
    route(1,42),event("scene",1,{revision:7,roads:[{road_class:3,
      points:[[44,7],[44.0001,7.0001]]}]}),snapshot(2),
    route(3,42),event("scene",3,{revision:7,roads:[{road_class:3,
      points:[[44,7],[44.0001,7.0001]]}]}),snapshot(4),
    route(5,42,[[44,7],[44.2,7.1]]),snapshot(6),
    event("scene",7,{revision:8,roads:[{road_class:3,
      points:[[44,7],[44.0001,7.0001]]}]}),snapshot(8)];
  const f=model.parse(a.join("\n")).frames;
  assert.equal(f[0].route,f[1].route);
  assert.equal(f[0].scene,f[1].scene);
  assert.notEqual(f[1].route,f[2].route);
  assert.notEqual(f[2].scene,f[3].scene);
});
