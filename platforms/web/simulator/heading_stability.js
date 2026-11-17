/* Experimental replay heading policies. No GNSS, BLE, IMU or firmware changes. */
(function(root,factory) {
  const model=factory();
  if(typeof module==="object"&&module.exports)module.exports=model;
  if(root)root.MotoHeadingStability=model;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";
  const finite=Number.isFinite;
  const norm=a=>((a%360)+360)%360;
  const diff=(a,b)=>((a-b+540)%360)-180;
  const interpolate=(a,b,u)=>norm(a+diff(b,a)*Math.max(0,Math.min(1,u)));
  function build(frames){
    if(!frames.length)return [];
    const result=new Array(frames.length);
    let heading=NaN,prevInput=NaN,prevT=NaN,prevRoute=null;
    for(let i=0;i<frames.length;i++){
      const {snapshot:s,t,route}=frames[i];
      const input=Number(s.heading_deg);
      if(!finite(input)){result[i]=finite(heading)?heading:0;continue;}
      const raw=norm(input);
      const gap=t-prevT;
      // Seek-independent replay. Explicitly reanchor after time gaps, a new
      // route identity or non-navigating states, avoiding a stale direction.
      if(!finite(heading)||!finite(prevT)||gap<0||gap>3000||
          (prevRoute && route && (route.token!==prevRoute.token||
           route.generation!==prevRoute.generation))||
          s.state!=="navigating"){
        heading=raw;
      } else if(gap>0){
        // Do not classify noise in recorded heading as a real turn.
        // A fast turn is explicit only when maneuver context supports it.
        const turning=s.maneuver==="roundabout"||s.maneuver==="u_turn";
        const tau=turning?0.12:1.1; // fast turns, calm straight lines
        const alpha=1-Math.exp(-gap/1000/tau);
        const error=diff(raw,heading);
        // Limit tiny map oscillations only on a straight, while allowing
        // sustained slow rotations to accumulate instead of sticking forever.
        const deadband=turning?0:0.7;
        const move=Math.sign(error)*Math.max(0,Math.abs(error)-deadband)*alpha;
        heading=norm(heading+move);
      }
      result[i]=heading;prevInput=raw;prevT=t;prevRoute=route;
    }
    return result;
  }
  function at(frames,values,t){
    if(!frames.length||!values.length)return NaN;
    let low=0,high=frames.length-1;
    while(low<high){const mid=(low+high+1)>>>1;
      if(frames[mid].t<=t)low=mid;else high=mid-1;}
    if(low===frames.length-1)return values[low];
    const next=low+1,dt=frames[next].t-frames[low].t;
    if(dt<=0||dt>3000)return values[low];
    return interpolate(values[low],values[next],(t-frames[low].t)/dt);
  }

  // Causal cross-check against movement across distinct GPS fixes. Heading is
  // accepted as a fast turn only if the GNSS track itself curves. Repeated UI
  // snapshots never count as new GPS observations.
  function buildCourseValidated(frames){
    const output=[];
    let applied=NaN,previousFix=null,previousCourse=NaN,previousT=NaN,
      previousRouteKey=null,straightCourse=NaN;
    for(const f of frames){
      const s=f.snapshot,rawInput=Number(s.heading_deg),lat=Number(s.lat),
        lon=Number(s.lon),speed=Number(s.speed_mps);
      const raw=finite(rawInput)?norm(rawInput):NaN;
      const routeKey=f.route?String(f.route.token)+":"+f.route.generation:"none";
      const gap=f.t-previousT;
      if(!finite(raw)){output.push(finite(applied)?applied:0);continue;}
      if(!finite(applied)||!finite(previousT)||gap<0||gap>3000||
          routeKey!==previousRouteKey||s.state!=="navigating"){
        applied=raw;previousFix=null;previousCourse=NaN;straightCourse=NaN;
      }
      if(finite(lat)&&finite(lon)){
        if(!previousFix){
          previousFix={lat,lon,t:f.t};
        }else{
          const north=(lat-previousFix.lat)*111195;
          const east=(lon-previousFix.lon)*111195*
            Math.cos(lat*Math.PI/180);
          const meters=Math.hypot(north,east);
          const elapsed=f.t-previousFix.t;
          if(meters>=6 && elapsed>=150){
            const course=norm(Math.atan2(east,north)*180/Math.PI);
            const measuredSpeed=meters/(elapsed/1000);
            const plausible=elapsed<=3000 && measuredSpeed<=
              Math.max(40,finite(speed)?speed*2+12:40);
            const bend=finite(previousCourse)?
              Math.abs(diff(course,previousCourse)):Infinity;
            // A straight is established by two consecutive independent
            // displacement vectors, not by a possibly noisy raw bearing.
            const straight=plausible && bend<9 &&
              finite(speed)&&speed>=6 && s.maneuver!=="roundabout" &&
              s.maneuver!=="u_turn";
            const turn=plausible && finite(previousCourse)&&bend>=9;
            if(straight){
              straightCourse=finite(straightCourse)?
                interpolate(straightCourse,course,0.35):course;
              // GNSS displacement course has its own uncertainty; reject
              // it if it disagrees drastically with both current and previous
              // heading, e.g. at a lane split or a GPS jump.
              const agreement=Math.abs(diff(course,raw))<=22;
              const target=agreement?straightCourse:applied;
              const dt=finite(previousT)?Math.max(0,f.t-previousT):0;
              const alpha=1-Math.exp(-dt/1100);
              applied=interpolate(applied,target,alpha);
            }else if(turn){
              straightCourse=NaN;
              // Track confirms that direction changed; permit prompt turn.
              applied=interpolate(applied,raw,0.72);
            }else{
              straightCourse=NaN;
              applied=interpolate(applied,raw,0.20);
            }
            if(plausible)previousCourse=course;
            previousFix={lat,lon,t:f.t};
          }
        }
      }
      // On repeated coordinates, hold the validated heading; an isolated
      // raw heading change cannot rotate the map independently of movement.
      output.push(applied);
      previousT=f.t;previousRouteKey=routeKey;
    }
    return output;
  }
  return {build,buildCourseValidated,at,diff,interpolate,norm};
});
