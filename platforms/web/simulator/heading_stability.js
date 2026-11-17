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
      const input=Number(s.heading_deg),speed=Number(s.speed_mps);
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
        const rate=finite(prevInput)?Math.abs(diff(raw,prevInput))/(gap/1000):0;
        const turning=s.maneuver==="roundabout"||rate>4;
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
  return {build,at,diff,interpolate,norm};
});
