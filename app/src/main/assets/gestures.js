/* No swipe actions. A deliberate stationary tap may toggle reading controls. */
(function(root){
 function isTap({distance=0,duration=0,sinceScroll=Infinity,blocked=false,selected=false,multi=false}){
  return !blocked&&!selected&&!multi&&distance<=10&&duration>=0&&duration<=350&&sinceScroll>=450;
 }
 const api={isTap};if(typeof module==='object')module.exports=api;else root.InkGestures=api;
})(typeof globalThis==='object'?globalThis:this);
