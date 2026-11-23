'use strict';

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {readFile} = require('node:fs/promises');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

async function main([browser, output]) {
  const graph = 'sha256:' + 'a'.repeat(64);
  const snapshot = 'snapshot:sample';
  const contexts = [snapshot,'analysis:sample'];
  const report = 'sha256:' + 'b'.repeat(64);
  const finding = {identity:'sha256:' + 'c'.repeat(64),bundleIdentity:'sha256:' + 'd'.repeat(64),
    result:{classification:'MUST',kind:'FORBIDDEN_DEPENDENCY',rule:{id:'layer.no-store',severity:'ERROR'},
      bundle:{path:[],sources:[],region:{}}}};
  const date = new Date(0).toISOString();
  const job = {id:'job',setup:'sample',state:'PARTIAL',stage:'FINISHED',revision:1,mode:'FULL',
    submittedAt:date,finishedAt:date,events:[],result:{graph,snapshot,contexts,reports:[report],completion:'PARTIAL',gapCount:1}};
  const chatRequests = [];
  const reviewerRequests = [];
  const repairRequests = [];
  const directory = path.resolve(__dirname,'../../main/resources/web');
  const mime = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.woff2':'font/woff2'};
  const server = createServer(async (req,res) => {
    const route = req.url === '/' ? '/index.html' : req.url;
    try {
      if (!route.startsWith('/api/')) {
        res.setHeader('Content-Type',mime[path.extname(route)]);
        res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'");
        res.end(await readFile(path.join(directory,route.slice(1))));
        return;
      }
      let body = '';
      for await (const chunk of req) body += chunk;
      const input = body ? JSON.parse(body) : null;
      let data;
      if (route === '/api/v1') data = {analysisJobsEnabled:true,analysisCoverageEnabled:true,repositoryConnectionsEnabled:true,
        architectureChatEnabled:true,architectureChatProvider:{model:'fixture-model',remote:true,
          endpoint:'https://example.invalid/v1/chat/completions'}};
      else if (route.endsWith('/setups')) data = {items:[{id:'sample',artifactPolicy:graph}]};
      else if (route.endsWith('/connections')) data = {items:[{setup:'sample',label:'Example repository',kind:'LOCAL'}]};
      else if (route.endsWith('/history')) data = {items:[job]};
      else if (route.endsWith('/jobs/job')) data = job;
      else if (route.endsWith('/jobs/job/coverage')) data = {completion:'PARTIAL',totalGaps:1,ledgerTotal:1,counted:1,
        topReasons:[{reason:'GRADLE_EFFECTIVE_CONTEXT_REQUIRED',count:1}]};
      else if (route.endsWith(graph)) data = {graph,snapshot,contexts:contexts.map(identity => ({identity})),reports:[report]};
      else if (route.endsWith('/reports/' + report)) data = {identity:report,context:contexts[1],findingCount:1};
      else if (route.endsWith('/findings')) data = {items:[finding]};
      else if (route.endsWith('/diagnosis')) data = {status:'CITATIONS_VALIDATED',mode:'LOCAL_REPLAY',
        input:'diagnosis-pin',explanation:'Captured diagnosis.',citations:[]};
      else if (route.endsWith('/repair-options')) data = {recipes:[{id:'java.remove-unused-private-field',title:'Remove unused field'}],
        ai:{input:'repair-pin',provider:{endpoint:'https://example.invalid/v1/chat/completions',model:'repair-model'},
          files:[{path:'src/main/java/Controller.java'}]}};
      else if (route.endsWith('/repair-proposal')) {
        repairRequests.push(input.arguments);
        data = {status:'PROPOSED',proposal:{candidate:{strategy:input.arguments.strategy,
          edits:[{path:'src/main/java/Controller.java'}],patch:'-private Store store;'}}};
      }
      else if (route.endsWith('/provenance')) data = {items:[{family:'analysis.manifest',metadata:{modulePath:'sample'}}]};
      else if (route.endsWith('/architecture-chat')) {
        (input.reviewer ? reviewerRequests : chatRequests).push(input);
        if (input.reviewer && input.messages.at(-1).content === 'Review the architecture evidence.')
          await new Promise(resolve => setTimeout(resolve,800));
        const unrelated = input.messages.at(-1).content === 'Tell me a joke.';
        const overview = input.messages.at(-1).content === 'Bạn cho tôi biết context hiện tại của dự án này.';
        data = {status:unrelated ? 'OUT_OF_SCOPE' : (overview || input.evidenceOnly) ? 'EVIDENCE_ONLY' : 'ADVICE',
          pin:{graph,snapshot,context:input.context,finding:input.finding || null},evidenceIdentity:graph,conversationTitle:'Architecture overview',
          answer:unrelated ? 'Ask about a dependency, a finding, or how to verify a proposed change.' :
            input.evidenceOnly ? null : '## Review\n\nReview **upstream** dependencies and `Service`. <img src=x onerror=alert(1)>\n\n- First\n- Second\n\n---\n\n| Component | Role |\n| --- | --- |\n| `backend` | Maven \\| Java |\n| mobile | [External](https://example.com) |\n\n| Mode | Status | | --- | --- | | FAST_ARCHITECTURE | PARTIAL |\n\n```java\nclass Safe {}\n```',
          citations:unrelated || input.evidenceOnly ? [] : ['E1'],limitations:unrelated ? [] : ['Current analysis is partial.'],
          evidence:unrelated ? [] : [{id:'E1',kind:'SUMMARY',label:'Captured architecture summary',
            data:{status:'PARTIAL',gapCount:1,counts:[{nodeCounts:{TYPE:2}}]}}]};
      } else throw new Error('Unexpected route ' + route);
      res.setHeader('Content-Type','application/json');
      res.end(JSON.stringify({data}));
    } catch (error) {
      res.writeHead(500,{'Content-Type':'application/json'});
      res.end(JSON.stringify({error:{message:error.message}}));
    }
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  let page;
  try {
    page = await edge(browser,output);
    const evaluate = expression => page.evaluate(expression);
    const click = text => evaluate(`[...document.querySelectorAll('.architecture-chat button')]
      .find(button => button.textContent === ${JSON.stringify(text)}).click()`);
    await page.send('Emulation.setDeviceMetricsOverride',{width:1280,height:800,deviceScaleFactor:1,mobile:false});
    await page.send('Page.navigate',{url:`http://127.0.0.1:${server.address().port}`});
    await until('Published workbench',() => evaluate("document.querySelector('#analysis-workspace')?.hidden === false"));
    await until('Overview actions',() => evaluate("Boolean(document.querySelector('.workbench-next-actions button.primary'))"));
    await until('Coverage appears on Overview',() => evaluate("Boolean(document.querySelector('.job-coverage details'))"));
    assert.equal(await evaluate("document.querySelector('#artifact-recovery-note').hidden"),false);
    await evaluate("document.querySelector('.job-coverage summary').click()");
    await until('Coverage reports the job reason',() => evaluate("document.querySelector('.job-coverage').textContent.includes('Gradle effective context required')"));
    await evaluate("document.querySelector('.workbench-next-actions button.primary').click()");
    await until('Chat mounted from the Overview action',() => evaluate("Boolean(document.querySelector('.architecture-chat textarea'))"));
    assert.equal(await evaluate("document.querySelector('input[name=reviewer]')"),null);
    assert.equal(await evaluate("document.querySelector('.architecture-chat-shortcut')"),null);
    assert.equal(await evaluate("[...document.querySelectorAll('.architecture-chat button')].some(b => ['Use a sample question','Read evidence only'].includes(b.textContent))"),false);
    await click('Understand the structure');
    assert.equal(await evaluate("document.querySelector('input[name=allowRemote]')"),null);
    assert.equal(chatRequests.length,0);
    await click('Send question');
    await until('Initial reply',() => evaluate("document.querySelector('.architecture-chat-status').textContent === 'Reply received.'"));
    assert.equal(reviewerRequests.length,1);
    await evaluate("document.querySelector('.architecture-chat textarea').focus()");
    await page.send('Input.insertText',{text:'First line'});
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13,modifiers:8});
    await page.send('Input.dispatchKeyEvent',{type:'char',text:'\r',unmodifiedText:'\r',modifiers:8});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13,modifiers:8});
    await page.send('Input.insertText',{text:'Second line'});
    assert.equal(await evaluate("document.querySelector('.architecture-chat textarea').value"),'First line\nSecond line');
    assert.equal(chatRequests.length,1);
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13});
    await until('Enter sends the question',() => chatRequests.length === 2);
    assert.equal(chatRequests[1].messages.at(-1).content,'First line\nSecond line');
    await until('Keyboard reply',() => evaluate("document.querySelector('.architecture-chat-status').textContent === 'Reply received.'"));
    await click('New conversation');
    chatRequests.length = 1;
    const ask = async (question, feedback = 'Reply received.') => {
      await evaluate(`document.querySelector('.architecture-chat textarea').value = ${JSON.stringify(question)}`);
      await click('Send question');
      await until('Chat response',() => evaluate(`document.querySelector('.architecture-chat-status').textContent === ${JSON.stringify(feedback)}`));
    };
    await ask('Explain this captured architecture.');
    assert.equal(chatRequests[1].allowRemote,true);
    assert.equal(chatRequests[1].job,'job');
    assert.equal(await evaluate("document.querySelectorAll('.architecture-chat-log img').length"),0);
    assert.equal(await evaluate("document.querySelector('.architecture-chat-markdown strong')?.textContent"),'upstream');
    assert.equal(await evaluate("document.querySelectorAll('.architecture-chat-assistant > .architecture-chat-markdown li').length"),2);
    assert.equal(await evaluate("document.querySelector('.architecture-chat-markdown pre code')?.textContent"),'class Safe {}');
    assert.equal(await evaluate("document.querySelector('.architecture-chat-assistant table tbody td code')?.textContent"),'backend');
    assert.equal(await evaluate("document.querySelector('.architecture-chat-assistant table tbody td:nth-child(2)')?.textContent"),'Maven | Java');
    assert.equal(await evaluate("document.querySelector('.architecture-chat-assistant th')?.getAttribute('scope')"),'col');
    assert.equal(await evaluate("document.querySelectorAll('.architecture-chat-assistant > .architecture-chat-markdown hr').length"),1);
    assert.equal(await evaluate("document.querySelectorAll('.architecture-chat-assistant > .architecture-chat-markdown table')[1]?.tBodies[0].rows[0].cells[1].textContent"),'PARTIAL');
    await evaluate("document.querySelector('.architecture-chat-links button').click()");
    assert.equal(await evaluate("document.querySelector('.architecture-chat-record').open"),true);
    assert.equal(await evaluate("document.activeElement === document.querySelector('.architecture-chat-record summary')"),true);
    await ask('Tell me a joke.','Try a software architecture question.');
    assert.equal(await evaluate("document.querySelector('.architecture-chat-redirect h3')?.textContent"),'Outside architecture scope');
    await ask('What would you change?');
    assert.deepEqual(chatRequests[3].messages.map(message => message.role),['user','assistant','user']);
    assert.equal(chatRequests[3].context,snapshot);
    assert.equal(chatRequests[3].messages.some(message => message.content === 'Tell me a joke.'),false);
    await ask('Review the architecture evidence.');
    assert.equal(chatRequests.length,5);
    assert.equal(reviewerRequests.at(-1).reviewer,true);
    assert.equal(await evaluate("Boolean(document.querySelector('.architecture-chat-assistant:last-child .architecture-chat-review'))"),false);
    await until('Reviewer arrives after the primary answer',() => evaluate("Boolean(document.querySelector('.architecture-chat-assistant:last-child .architecture-chat-review'))"));
    chatRequests.length=4;
    await evaluate("document.querySelector('#tab-overview').click()");
    await evaluate("document.querySelector('#tab-architecture-chat').click()");
    await until('Conversation restored after changing views',() => evaluate("document.querySelector('.architecture-chat-log')?.textContent.includes('What would you change?')"));
    await click('New conversation');
    assert.equal(await evaluate("document.querySelector('.architecture-chat-welcome') !== null"),true);
    assert.ok(await evaluate("[...document.querySelectorAll('.architecture-chat select')].some(s => s.getAttribute('aria-label') === 'Conversation history')"));
    await evaluate("document.querySelector('select[aria-label=\"Conversation history\"]').selectedIndex = 1; document.querySelector('select[aria-label=\"Conversation history\"]').dispatchEvent(new Event('change'))");
    assert.match(await evaluate("document.querySelector('.architecture-chat-log').textContent"),/What would you change/);
    await page.send('Page.reload');
    await until('Workbench reopens',() => evaluate("Boolean(document.querySelector('#tab-architecture-chat') && !document.querySelector('#analysis-workspace').hidden)"));
    await evaluate("document.querySelector('#tab-architecture-chat').click()");
    await until('Conversation restored after reload',() => evaluate("document.querySelector('.architecture-chat-log')?.textContent.includes('What would you change?')"));
    assert.equal(await evaluate("document.querySelector('select[aria-label=\"Conversation history\"]').selectedOptions[0].textContent"),'Architecture overview');
    assert.match(await evaluate("[...document.querySelector('select[aria-label=\"Conversation history\"]').options].map(o => o.textContent).join('|')"),/New conversation/);
    await evaluate("document.querySelector('#theme-toggle').click()");
    assert.equal(await evaluate("document.documentElement.dataset.theme"),'light');
    await page.send('Emulation.setDeviceMetricsOverride',{width:320,height:650,deviceScaleFactor:1,mobile:true});
    assert.equal(await evaluate("document.querySelector('.architecture-chat').scrollWidth <= document.querySelector('.architecture-chat').clientWidth"),true,
      await evaluate("JSON.stringify([...document.querySelectorAll('.architecture-chat *')].filter(e => e.getBoundingClientRect().right > document.querySelector('.architecture-chat').getBoundingClientRect().right + 1).map(e => [e.tagName,e.className,e.scrollWidth,e.clientWidth]).slice(0,10))"));
    assert.equal(await evaluate("getComputedStyle(document.querySelector('#sidebar')).display"),'none');
    await evaluate("document.querySelector('#context').value = 'analysis:sample'; document.querySelector('#context').dispatchEvent(new Event('change'))");
    await until('Context changes start a separate conversation',() => evaluate("Boolean(document.querySelector('.architecture-chat-welcome'))"));
    assert.equal(await evaluate("document.querySelectorAll('.architecture-chat-message').length"),0);
    await evaluate("document.querySelector('#tab-policy').click()");
    await until('Finding opened',() => evaluate("document.querySelector('#panel-policy .entity-row')?.textContent.includes('layer.no-store')"));
    await evaluate("document.querySelector('#panel-policy .entity-row').click()");
    const clickInspector = text => evaluate(`[...document.querySelectorAll('#inspector-body button')]
      .find(button => button.textContent.startsWith(${JSON.stringify(text)})).click()`);
    await clickInspector('Ask architecture chat');
    await until('Finding is attached to chat',() => evaluate("document.querySelector('.architecture-chat-pins')?.textContent.includes('layer.no-store')"));
    assert.equal(await evaluate("document.querySelector('.architecture-chat-settings select').value"),'REPAIR');
    await ask('Explain the finding using its code.');
    assert.deepEqual(chatRequests[4].finding,{report,identity:finding.identity,bundle:finding.bundleIdentity});
    assert.equal(chatRequests[4].includeSource,true);
    assert.equal(await evaluate("document.querySelector('input[name=includeSource]').checked"),true);
    await evaluate("document.querySelector('input[name=includeSource]').click()");
    await ask('Summarize the finding from metadata.');
    assert.equal(chatRequests[5].includeSource,undefined);
    await click('Open repair workflow');
    await until('Repair options disclose source paths',() => evaluate("document.querySelector('#inspector-body').textContent.includes('src/main/java/Controller.java')"));
    await clickInspector('Propose with AI');
    assert.equal(repairRequests.length,0);
    await evaluate("document.querySelector('#inspector-body input[type=checkbox]').click()");
    await clickInspector('Propose with AI');
    await until('Patch preview retains the unverified status',() => evaluate("document.querySelector('#inspector-body').textContent.includes('Unverified candidate')"));
    assert.deepEqual(repairRequests,[{identity:finding.identity,bundle:finding.bundleIdentity,context:contexts[1],
      diagnosisInput:'diagnosis-pin',strategy:'AI',modelInput:'repair-pin'}]);
    const reviewsBeforeOverview = reviewerRequests.length;
    await ask('Bạn cho tôi biết context hiện tại của dự án này.');
    assert.equal(reviewerRequests.length,reviewsBeforeOverview);
    console.log('PASS Architecture chat, finding attachment, repair review and consent, offline read, citations, scope, and 320px layout');
  } finally {
    await page?.close();
    server.closeAllConnections();
    server.close();
  }
}

main(process.argv.slice(2)).catch(error => { console.error(error); process.exitCode = 1; });
