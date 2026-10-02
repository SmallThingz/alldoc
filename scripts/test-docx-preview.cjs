const {chromium}=require('playwright');
const fs=require('fs');const path=require('path');const assert=require('assert');
(async()=>{
 const root=path.resolve(__dirname,'..');const assets=path.join(root,'app/src/main/assets/preview');
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:412,height:892},deviceScaleFactor:1});
 const external=[];const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.route('**/*',async route=>{
  const u=new URL(route.request().url());
  if(u.hostname!=='alldoc.invalid'){external.push(u.href);return route.abort();}
  if(u.pathname==='/document.docx')return route.fulfill({body:fs.readFileSync(path.join(root,'fixtures/field-notes.docx')),contentType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document'});
  const filename=path.basename(u.pathname);
  if(!['index.html','preview.js','docx-preview.min.js','jszip.min.js'].includes(filename))return route.fulfill({status:403,body:''});
  await route.fulfill({body:fs.readFileSync(path.join(assets,filename)),contentType:filename.endsWith('.js')?'text/javascript':'text/html'});
 });
 await page.goto('https://alldoc.invalid/preview/index.html');
 await page.waitForSelector('section.docx');
 await page.waitForFunction(()=>!document.getElementById('status'));
 assert((await page.locator('section.docx').count())>=2,'Explicit page break must render at least two sections');
 assert((await page.locator('table').count())>=1,'Table must render');
 assert((await page.locator('body').innerText()).includes('العربية'),'Unicode content must survive');
 assert.deepStrictEqual(external,[],'No external network requests');assert.deepStrictEqual(errors,[],'No JS errors');
 fs.mkdirSync(path.join(root,'build/screenshots'),{recursive:true});
 await page.screenshot({path:path.join(root,'build/screenshots/docx-mobile.png'),fullPage:true});
 await page.setViewportSize({width:1024,height:768});await page.screenshot({path:path.join(root,'build/screenshots/docx-tablet.png'),fullPage:true});
 console.log('PASS: mobile/tablet DOCX preview, table, explicit page breaks, Unicode, no external requests, no JS errors');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
