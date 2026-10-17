/* Bound TTS requests without silently truncating paragraphs or splitting surrogate pairs. */
(function(root){
 function splitText(value,limit=2800){
  limit=Math.max(2,Math.floor(limit));let remaining=String(value||'').trim();const parts=[];
  while(remaining.length>limit){let end=limit;if(/[\uD800-\uDBFF]/.test(remaining[end-1]))end--;
   const prefix=remaining.slice(0,end);let boundary=-1;for(const mark of ['。','！','？','；','\n','. ','! ','? '])boundary=Math.max(boundary,prefix.lastIndexOf(mark)+mark.length);
   if(boundary>end/2)end=boundary;
   const piece=remaining.slice(0,end).trim();if(piece)parts.push(piece);remaining=remaining.slice(end).trim();
  }
  if(remaining)parts.push(remaining);return parts;
 }
 const api={splitText};if(typeof module==='object')module.exports=api;else root.InkSpeech=api;
})(typeof globalThis==='object'?globalThis:this);
