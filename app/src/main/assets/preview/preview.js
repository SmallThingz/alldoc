'use strict';
(async()=>{
 const status=document.getElementById('status');
 try {
  const response=await fetch('/document.docx');
  if(!response.ok)throw new Error('Document data is unavailable');
  const data=await response.arrayBuffer();
  await docx.renderAsync(data,document.getElementById('document'),null,{
   className:'docx',inWrapper:true,breakPages:true,ignoreWidth:false,ignoreHeight:false,
   ignoreFonts:true,renderAltChunks:false,renderComments:false,renderChanges:false,
   renderFootnotes:true,renderEndnotes:true,useBase64URL:true,experimental:false,
   ignoreLastRenderedPageBreak:true
  });
  status.remove();
  const fit=()=>{
   const pages=[...document.querySelectorAll('section.docx')];
   const widest=Math.max(600,...pages.map(p=>p.offsetWidth));
   document.getElementById('document').style.zoom=Math.min(1,(window.innerWidth-12)/(widest+40));
  };
  fit();window.addEventListener('resize',fit);
 } catch(error){status.textContent='This formatted preview could not be rendered. Reveal controls and choose Edit text to inspect or edit the document.';}
})();
