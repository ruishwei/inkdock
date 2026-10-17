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
 // A short first utterance reduces work before an engine can report playback start.
 // Later utterances keep sentence groups bounded without changing cloud segmentation.
 // Preserve source whitespace here; callers skip whitespace-only slices before TTS.
 function splitOfflineText(value,firstChunk=true){
  const text=String(value??'');if(!text.trim())return [];const parts=[];let start=0;
  while(start<text.length){
   const limit=firstChunk?120:360;let end=Math.min(text.length,start+limit);
   if(end<text.length&&/[\uD800-\uDBFF]/.test(text[end-1])&&/[\uDC00-\uDFFF]/.test(text[end]))end--;
   if(firstChunk||end<text.length){
    // One character of lookahead prevents a capped prefix from turning a decimal
    // or a period inside a word into an English sentence boundary.
    const prefix=text.slice(start,Math.min(text.length,end+1));
    const boundaries=/[。！？；]+[”’"'」』】）》）\])}]*|[.!?;]+[”’"'」』】）》）\])}]*(?=\s|$)|\r?\n/g;
    let match,boundary=0;
    while((match=boundaries.exec(prefix))){
     let candidate=start+match.index+match[0].length;if(candidate>end)break;
     while(candidate<end&&/\s/.test(text[candidate]))candidate++;
     boundary=candidate;if(firstChunk)break;
    }
    if(boundary>start)end=boundary;
   }
   const part=text.slice(start,end);parts.push(part);start=end;
   if(part.trim())firstChunk=false;
  }
  return parts;
 }
 const api={splitText,splitOfflineText};if(typeof module==='object')module.exports=api;else root.InkSpeech=api;
})(typeof globalThis==='object'?globalThis:this);
