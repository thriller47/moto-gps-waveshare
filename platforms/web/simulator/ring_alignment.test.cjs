"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const analyzer = require("./ring_alignment.js");

// Anonymous synthetic geometries; no real rider coordinates, route or timestamps.
const ORIGIN = [45, 8];
const metres = (north, east) => [
  ORIGIN[0] + north / 111195,
  ORIGIN[1] + east / (111195 * Math.cos(Math.PI / 4))
];
const radius = 14;
const ring = Array.from({length:19}, (_, i) => {
  const angle = i * 2 * Math.PI / 19;
  return metres(radius * Math.sin(angle), radius * Math.cos(angle));
});
ring.push(ring[0]);
const frame = points => ({
  scene:{roads:[{points:ring},{points:[metres(0,0),metres(25,25)]}]},
  route:{points}
});

test("anello chiuso valido, bypass a 11 metri: vicino ma non coincidente", () => {
  const result = analyzer.inspect(frame([metres(-40,25),metres(40,25)]));
  assert.equal(result.status,"nearby");
  assert.ok(result.gapMeters > 10 && result.gapMeters < 12, result.gapMeters);
  assert.equal(result.ringPoints,20);
});

test("anello percorso realmente: scarto nullo anche con una finestra BLE parziale", () => {
  const result = analyzer.inspect(frame(ring.slice(2,8)));
  assert.equal(result.status,"aligned");
  assert.ok(result.gapMeters < 0.01);
});

test("percorso che attraversa un segmento del ring senza vertici comuni", () => {
  const result = analyzer.inspect(frame([metres(0,-40),metres(0,40)]));
  assert.equal(result.status,"aligned");
  assert.ok(result.gapMeters < 0.01);
});

test("la geometria di una strada normale non diventa una rotatoria", () => {
  const f=frame([metres(0,0),metres(40,0)]);
  f.scene.roads[0].points=[metres(0,0),metres(0,20),metres(40,20)];
  assert.deepEqual(analyzer.inspect(f),{status:"no-ring"});
});

test("anello senza geometria rotta: dati assenti, nessun falso scarto", () => {
  assert.deepEqual(analyzer.inspect({scene:{roads:[{points:ring}]} }),
    {status:"no-route",ringPoints:20});
});

test("anello molto distante dalla finestra verde non implica assenza nell'itinerario", () => {
  const result=analyzer.inspect(frame([metres(120,90),metres(130,90)]));
  assert.equal(result.status,"separated");
  assert.ok(result.gapMeters > 100);
});

test("la chiusura di una forma aperta o degenere non viene inventata", () => {
  const f=frame([metres(0,0),metres(0,20)]);
  f.scene.roads[0].points=ring.slice(0,-2);
  assert.equal(analyzer.inspect(f).status,"no-ring");
  f.scene.roads[0].points=[metres(0,0),metres(0,1),metres(0,2),metres(0,3),metres(0,0)];
  assert.equal(analyzer.inspect(f).status,"no-ring");
});
