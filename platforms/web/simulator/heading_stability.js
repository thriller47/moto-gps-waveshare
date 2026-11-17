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
        // A maneuver is the NEXT instruction, not the current road shape.
        // "roundabout" may appear kilometres before the actual junction.
        // Use a fast response only close to the maneuver, never just
        // because the next Valhalla instruction happens to be a roundabout.
        const turnMetres=Number(s.turn_m);
        const nearManeuver=finite(turnMetres) && turnMetres>=0 &&
          turnMetres<=35;
        const turning=nearManeuver &&
          (s.maneuver==="roundabout"||s.maneuver==="u_turn");
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
            // Classify the actual track, independent of the NEXT maneuver.
            // A roundabout 2 km ahead must not disable straight recognition.
            const straight=plausible && bend<9 &&
              finite(speed)&&speed>=6;
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

  // Causal diagnostic: estimate course from several distinct GPS points.
  // Distance is mandatory because fixes at low speed can be only 2-3m apart.
  function buildDistanceConsensus(frames){
    const values=[],quality=[],rad=Math.PI/180;
    let applied=NaN,prevT=NaN,lastRouteKey=null,fixes=[];
    function estimate(){
      const count=fixes.length;
      if(count<4)return null;
      const first=fixes[0],span=(fixes.at(-1).t-first.t)/1000;
      if(span<2.4||span>6.5)return null;
      const scale=111195*Math.cos(first.lat*rad);
      const pts=fixes.map(x=>({t:(x.t-first.t)/1000,
        n:(x.lat-first.lat)*111195,e:(x.lon-first.lon)*scale}));
      let mt=0,mn=0,me=0;
      for(const x of pts){mt+=x.t/count;mn+=x.n/count;me+=x.e/count;}
      let den=0,cn=0,ce=0;
      for(const x of pts){const dt=x.t-mt;den+=dt*dt;
        cn+=dt*(x.n-mn);ce+=dt*(x.e-me);}
      if(den<0.3)return null;
      const vn=cn/den,ve=ce/den,v=Math.hypot(vn,ve);
      const baseline=v*span,net=Math.hypot(pts.at(-1).n,pts.at(-1).e);
      if(baseline<16||net<14||v<2||v>36)return null;
      let sum=0;
      for(const x of pts){const cross=(x.n-mn)*(-ve/v)+(x.e-me)*(vn/v);
        sum+=cross*cross/count;}
      const rms=Math.sqrt(sum);
      if(rms>4.5)return null;
      return {course:norm(Math.atan2(ve,vn)*180/Math.PI),baseline,rms};
    }
    function trackTurn(){
      if(fixes.length<4)return false;
      const points=fixes.slice(-4),segments=[];
      for(let i=1;i<points.length;i++){
        const a=points[i-1],b=points[i],dt=b.t-a.t;
        const n=(b.lat-a.lat)*111195,e=(b.lon-a.lon)*111195*
          Math.cos(a.lat*rad),m=Math.hypot(n,e);
        if(dt<400||dt>2500||m<7||m>65)return false;
        segments.push(norm(Math.atan2(e,n)*180/Math.PI));
      }
      const d0=diff(segments[1],segments[0]),
        d1=diff(segments[2],segments[1]);
      const evolving=Math.sign(d0)===Math.sign(d1)&&Math.abs(d0)>=8&&
        Math.abs(d1)>=8&&Math.abs(d0+d1)>=20;
      const newLeg=Math.abs(d1)<=14&&
        Math.abs(diff(segments[1],applied))>=20&&
        Math.abs(diff(segments[2],applied))>=20;
      return evolving||newLeg;
    }
    for(const f of frames){
      const s=f.snapshot,rawInput=Number(s.heading_deg),
        lat=Number(s.lat),lon=Number(s.lon),speed=Number(s.speed_mps);
      const raw=finite(rawInput)?norm(rawInput):NaN;
      const key=f.route?f.route.token+":"+f.route.generation:"none";
      const dt=f.t-prevT;
      if(!finite(raw)){values.push(finite(applied)?applied:0);
        quality.push({status:"heading-assente",metres:0,course:NaN});continue;}
      const reset=!finite(applied)||!finite(prevT)||dt<0||dt>3000||
        key!==lastRouteKey||s.state!=="navigating";
      if(reset){applied=raw;fixes=[];}
      let distinct=false;
      if(finite(lat)&&finite(lon)){
        const last=fixes.at(-1);
        const move=last?Math.hypot((lat-last.lat)*111195,
          (lon-last.lon)*111195*Math.cos(lat*rad)):0;
        if(!last||(move>=0.5&&f.t-last.t>=120)){
          if(last&&(move>100||f.t-last.t>3000))fixes=[];
          fixes.push({lat,lon,t:f.t});distinct=true;
          while(fixes.length>1&&f.t-fixes[0].t>6000)fixes.shift();
        }
      }
      const est=estimate(),turn=distinct&&trackTurn();
      let status="heading-in-attesa";
      if(turn){
        applied=interpolate(applied,raw,0.78);
        status="curva-confermata";
      }else if(est&&finite(speed)&&speed>=3){
        applied=interpolate(applied,est.course,1-Math.exp(-Math.max(0,dt)/450));
        status="course-multifix";
      }else if(reset)status="nuova-ancora";
      else if(finite(speed)&&speed>=3&&Math.abs(diff(raw,applied))<=5){
        applied=interpolate(applied,raw,1-Math.exp(-Math.max(0,dt)/2000));
        status="piccola-correzione";
      }
      values.push(applied);
      quality.push({status,metres:est?.baseline??0,
        course:est?.course??NaN,rms:est?.rms??NaN,fixes:fixes.length});
      prevT=f.t;lastRouteKey=key;
    }
    return {values,quality};
  }
  return {build,buildCourseValidated,buildDistanceConsensus,at,diff,interpolate,norm};
});
