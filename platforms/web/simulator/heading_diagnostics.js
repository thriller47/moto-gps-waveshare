/* Replay analysis only. No routing, GNSS provider or ESP32 control.
 * Works on recorded snapshots; the WASM predictor offset is observational.
 */
(function(root,factory){
  const diagnostic=factory();
  if(typeof module==="object"&&module.exports)module.exports=diagnostic;
  if(root)root.MotoHeadingDiagnostics=diagnostic;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";
  const R=111195,PI=Math.PI;
  function num(n){return n===null||n===undefined||n===""?NaN:Number(n);}
  function valid(n){return Number.isFinite(n);}
  function angle(n){return valid(n)?((n%360)+360)%360:NaN;}
  function delta(a,b){return valid(a)&&valid(b)?((a-b+540)%360)-180:NaN;}
  function metres(a,b){if(!a||!b)return NaN;const lat=num(a[0]),lon=num(a[1]),x=num(b[0]),y=num(b[1]);return [lat,lon,x,y].every(valid)?Math.hypot((x-lat)*R,(y-lon)*R*Math.cos(lat*PI/180)):NaN;}
  function nearestRoute(lat,lon,route){
    if(!route||!route.points||route.points.length<2)return {heading:NaN,lateral:NaN};
    let best=Infinity,heading=NaN;
    for(let i=1;i<route.points.length;i++){
      const a=route.points[i-1],b=route.points[i];
      const ax=(a[1]-lon)*R*Math.cos(lat*PI/180),ay=(a[0]-lat)*R;
      const bx=(b[1]-lon)*R*Math.cos(lat*PI/180),by=(b[0]-lat)*R;
      const dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy;
      if(den<0.01)continue;
      const u=Math.max(0,Math.min(1,-(ax*dx+ay*dy)/den));
      const distance=Math.hypot(ax+u*dx,ay+u*dy);
      if(distance<best){best=distance;heading=angle(Math.atan2(dx,dy)*180/PI);}
    }
    return {heading,lateral:valid(best)?best:NaN};
  }
  function analyze(frames){
    const data=[];let lastFix=null,lastHeading=null;
    for(let i=0;i<frames.length;i++){
      const f=frames[i],s=f.snapshot,lat=num(s.lat),lon=num(s.lon);
      const gps=angle(num(s.heading_deg)),position=[lat,lon];
      const route=nearestRoute(lat,lon,f.route),dt=lastFix?(f.t-lastFix.t)/1000:NaN;
      const moved=lastFix?metres(lastFix.position,position):NaN;
      const jump=valid(dt)&&dt>0?moved:NaN;
      const headingChange=lastHeading?Math.abs(delta(gps,lastHeading.value)):NaN;
      const headingRate=lastHeading&&f.t>lastHeading.t?headingChange/((f.t-lastHeading.t)/1000):NaN;
      const speed=Math.max(0,num(s.speed_mps)||0);
      const maxMotion=valid(dt)&&dt>0?(speed+lastFix.speed)*0.5*dt+15:NaN;
      const flags=[];
      if(valid(headingRate)&&headingRate>25&&speed>=3&&
          (!valid(route.heading)||Math.abs(delta(gps,route.heading))>25))flags.push("heading");
      if(valid(jump)&&valid(maxMotion)&&jump>Math.max(25,maxMotion))flags.push("jump");
      if(valid(route.lateral)&&route.lateral>20)flags.push("route-window");
      data.push({i,t:f.t,lat,lon,gps,routeHeading:route.heading,
        headingMismatch:Math.abs(delta(gps,route.heading)),lateral:route.lateral,
        moved:jump,headingRate,speed,flags});
      // Repeated UI snapshots should not masquerade as repeated GPS fixes.
      if(!lastFix||moved>0.05){lastFix={t:f.t,position,speed};}
      if(!lastHeading||Math.abs(delta(gps,lastHeading.value))>0.05)
        lastHeading={t:f.t,value:gps};
    }
    return data;
  }
  const format=x=>valid(x)?x.toFixed(2):"";
  const coordinate=x=>valid(x)?x.toFixed(7):"";
  function csv(rows,observed){
    const cols=["t_ms","frame","snapshot_lat","snapshot_lon","heading_snapshot_deg",
      "heading_route_segment_deg","heading_mismatch_deg","heading_rate_deg_s",
      "distance_from_route_window_m","distance_between_fixes_m","speed_mps",
      "warning_flags","predicted_offset_off_m","predicted_offset_on_m",
      "geometry_age_since_distinct_route_ms","predicted_along_heading_m",
      "predicted_lateral_heading_m","predicted_offset_curve_m",
      "predicted_curve_along_heading_m","predicted_curve_lateral_heading_m"];
    return [cols.join(","),...rows.map(r=>{
      const o=observed?.get(r.i)||{};
      return [r.t,r.i+1,coordinate(r.lat),coordinate(r.lon),format(r.gps),
        format(r.routeHeading),format(r.headingMismatch),format(r.headingRate),
        format(r.lateral),format(r.moved),format(r.speed),r.flags.join("|"),
        valid(o.off)?format(o.off):"",valid(o.on)?format(o.on):"",
        valid(o.geometryAgeMs)?Math.round(o.geometryAgeMs):"",
        valid(o.along)?format(o.along):"",
        valid(o.lateral)?format(o.lateral):"",
        valid(o.curve)?format(o.curve):"",
        valid(o.curveAlong)?format(o.curveAlong):"",
        valid(o.curveLateral)?format(o.curveLateral):""].join(",");
    })].join("\r\n");
  }
  return {analyze,nearestRoute,delta,metres,csv};
});
