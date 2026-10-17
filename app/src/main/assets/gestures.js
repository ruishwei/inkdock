/* Pure direction classifier. Never captures native scrolling or edge-back gestures. */
(function(root){
 function classify({dx,dy,duration,startX,width,blocked=false,selected=false,multi=false}){
  if(blocked||selected||multi||duration>700||duration<0)return null;
  if(Math.abs(dy)>=48&&Math.abs(dy)>Math.abs(dx)*1.5)return dy>0?'show':'hide';
  if(startX>=32&&startX<=width-32&&dx>=80&&dx>Math.abs(dy)*2&&Math.abs(dy)<50)return 'toc';
  return null;
 }
 const api={classify};if(typeof module==='object')module.exports=api;else root.InkGestures=api;
})(typeof globalThis==='object'?globalThis:this);
