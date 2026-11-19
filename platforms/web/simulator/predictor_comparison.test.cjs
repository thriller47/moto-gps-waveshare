"use strict";
const {test}=require("node:test");
const assert=require("node:assert/strict");
const {csv}=require("./heading_diagnostics.js");

test("A/B/C CSV exports signed curve components on same recorded frame",()=>{
  const rows=[{i:0,t:5000,lat:45.0,lon:9.0,gps:90,
    routeHeading:90,headingMismatch:0,headingRate:20,
    lateral:2,moved:6,speed:10,flags:[]}];
  const results=new Map([[0,{off:0,on:8,curve:3,
    geometryAgeMs:700,along:4,lateral:-6,
    curveAlong:2,curveLateral:-2}]]);
  const [head,values]=csv(rows,results).split("\r\n");
  const columns=head.split(","),items=values.split(",");
  assert.equal(columns.length,items.length);
  assert.equal(items[columns.indexOf("predicted_offset_on_m")],"8.00");
  assert.equal(items[columns.indexOf("predicted_offset_curve_m")],"3.00");
  assert.equal(items[columns.indexOf("predicted_lateral_heading_m")],"-6.00");
  assert.equal(items[columns.indexOf("predicted_curve_lateral_heading_m")],"-2.00");
  assert.equal(items[columns.indexOf("predicted_offset_off_m")],"0.00");
});

test("older WASM data does not invent curve measurements",()=>{
  const rows=[{i:0,t:0,lat:45.0,lon:9.0,gps:0,
    routeHeading:0,headingMismatch:0,headingRate:0,
    lateral:0,moved:0,speed:0,flags:[]}];
  const [head,values]=csv(rows,new Map()).split("\r\n");
  const cols=head.split(","),data=values.split(",");
  assert.equal(data[cols.indexOf("predicted_offset_curve_m")],"");
  assert.equal(cols.length,data.length);
});
