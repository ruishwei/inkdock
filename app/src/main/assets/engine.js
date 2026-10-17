/* Index complete top-level Markdown tokens. Code, math, lists and tables stay whole. */
(function(root) {
  const parser=typeof module==='object'?require('./vendor/marked.js'):root.marked;
  function mathToken(src,display) {
    const re=display?/^(?:\$\$([\s\S]*?)\$\$|\\\[([\s\S]*?)\\\])(?:\n|$)/:/^(?:\$([^$\n]+?)\$|\\\(([\s\S]*?)\\\))/;
    const m=src.match(re);if(m)return {type:display?'displayMath':'inlineMath',raw:m[0],text:m[1]??m[2],display};
  }
  for(const display of [true,false])parser.use({extensions:[{name:display?'displayMath':'inlineMath',level:display?'block':'inline',start:src=>{const i=src.search(display?/\$\$|\\\[/:/\$|\\\(/);return i<0?undefined:i;},tokenizer(src){return mathToken(src,display);},renderer(token){return token.raw;}}]});
  function indexDocument(source,batchSize=40) {
    const tokens=parser.lexer(source.replace(/\r\n?/g,'\n'));
    const blocks=[],headings=[];
    for(const token of tokens){if(token.type==='space')continue;const block=blocks.length;
      if(token.type==='heading')headings.push({level:token.depth,title:token.text,block});
      blocks.push({id:`p-${block}`,text:token.raw.trimEnd(),token});
    }
    const chunks=[];for(let start=0;start<blocks.length;start+=batchSize)chunks.push({start,end:Math.min(start+batchSize,blocks.length)});
    return {blocks,headings,chunks,links:tokens.links};
  }
  // Global heading predecessor: handles lazy groups beginning in an earlier section.
  function headingAtBlock(headings,block){
    if(!Number.isInteger(block)||block<0)return null;
    let lo=0,hi=headings.length;
    while(lo<hi){const mid=(lo+hi)>>>1;if(headings[mid].block<=block)lo=mid+1;else hi=mid;}
    return lo?headings[lo-1]:null;
  }
  const api={indexDocument,headingAtBlock};if(typeof module==='object')module.exports=api;else root.InkEngine=api;
})(typeof globalThis==='object'?globalThis:this);
