(() => {
  'use strict';
  const sessions = new Map();
  const prompts = {
    EXPLORE: 'What does this snapshot establish about the architecture, and what remains uncertain?',
    DESIGN: 'Which design changes are worth considering here? Explain the trade-offs and how to test them.',
    REPAIR: 'What is the smallest architecture change to address this finding, and how should I verify it?'
  };
  const taskTitles = {EXPLORE:'Understand the structure',DESIGN:'Review a design choice',REPAIR:'Plan a repair'};
  const make = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = String(text);
    return node;
  };
  const button = (text, action, style = 'button') => {
    const node = make('button',style,text);
    node.type = 'button';
    node.addEventListener('click',action);
    return node;
  };
  const label = (text, control) => {
    const node = make('label','architecture-chat-field');
    node.append(make('span','',text),control);
    return node;
  };

  window.mountEvoArchitectureChat = function mount(scope) {
    const key = JSON.stringify([scope.graph,scope.context,scope.finding]);
    const session = sessions.get(key) || {turns:[],mode:scope.finding ? 'REPAIR' : 'EXPLORE',focus:'',draft:''};
    sessions.delete(key);
    sessions.set(key,session);
    if (sessions.size > 8) sessions.delete(sessions.keys().next().value);

    const node = make('section','architecture-chat');
    const head = make('header','architecture-chat-header');
    const title = make('div');
    title.append(make('h2','','Architecture chat'),make('p','muted',scope.contextLabel));
    const fresh = button('New conversation',reset);
    head.append(title,fresh);
    const pins = make('div','architecture-chat-pins');
    const snapshotKey = scope.snapshot?.split('sha256:').at(-1);
    const snapshot = make('code','',snapshotKey?.length === 64 ? 'Snapshot ' + snapshotKey.slice(0,8) : 'Captured snapshot');
    snapshot.title = scope.snapshot || scope.graph;
    pins.append(snapshot);
    if (scope.finding) {
      pins.append(button(scope.findingLabel || 'Attached finding',scope.openFinding),
        button('Detach finding',scope.clearFinding,'button subtle'));
    }

    const layout = make('div','architecture-chat-layout');
    const conversation = make('div','architecture-chat-conversation');
    const log = make('div','architecture-chat-log');
    log.setAttribute('role','log');
    log.setAttribute('aria-label','Conversation');
    log.tabIndex = 0;
    const status = make('p','architecture-chat-status muted');
    status.setAttribute('role','status');
    const form = make('form','architecture-chat-form');
    const settings = make('div','architecture-chat-settings');
    const mode = make('select');
    for (const [value,text] of Object.entries({EXPLORE:'Explore', DESIGN:'Design review', REPAIR:'Plan a repair'})) {
      const option = make('option','',text);
      option.value = value;
      mode.append(option);
    }
    mode.value = session.mode;
    mode.addEventListener('change',() => { session.mode = mode.value; });
    const focus = make('input');
    focus.maxLength = 256;
    focus.value = session.focus;
    focus.placeholder = 'Class, package or symbol ID';
    focus.addEventListener('input',() => { session.focus = focus.value; });
    settings.append(label('Task',mode),label('Focus symbol (optional)',focus));
    const input = make('textarea');
    input.maxLength = 2400;
    input.rows = 3;
    input.required = true;
    input.placeholder = 'Ask about dependencies, design trade-offs, or a repair…';
    input.value = session.draft;
    input.addEventListener('input',() => { session.draft = input.value; });
    const modelState = make('span','architecture-chat-model-state',
      scope.provider ? (scope.provider.remote ? 'Remote AI' : 'Local AI') : 'Local evidence');
    modelState.title = scope.provider?.model || 'Connect an AI provider to generate conversational answers.';
    pins.append(modelState);
    const consent = make('input');
    consent.type = 'checkbox';
    consent.name = 'allowRemote';
    const source = make('input');
    source.type = 'checkbox';
    source.name = 'includeSource';
    source.checked = Boolean(scope.finding && scope.provider);
    const sourceOption = make('label','architecture-chat-consent');
    sourceOption.append(source,make('span','','Include captured source code for this finding'));
    const approval = make('label','architecture-chat-consent');
    const disclosure = make('span');
    approval.append(consent,disclosure);
    function describeDisclosure() {
      const context = source.checked ? 'architecture evidence and relevant captured code' : 'architecture metadata';
      disclosure.textContent = `Send this question, recent chat history, and ${context} to ${scope.provider?.endpoint}`;
      consent.checked = false;
    }
    source.addEventListener('change',describeDisclosure);
    describeDisclosure();
    const controls = make('div','architecture-chat-controls');
    const usePrompt = button('Use a sample question',() => startQuestion(mode.value),'button subtle');
    const stop = button('Stop waiting',() => pending?.abort(),'button subtle');
    stop.hidden = true;
    const submit = make('button','button primary',scope.provider ? 'Send question' : 'Read evidence');
    submit.type = 'submit';
    const local = make('button','button subtle','Read evidence only');
    local.type = 'submit';
    controls.append(usePrompt,stop,submit);
    if (scope.provider) controls.insertBefore(local,submit);
    const contextOptions = make('details','architecture-chat-context-options');
    contextOptions.append(make('summary','','Task and symbol focus'),settings);
    const shortcut = make('span','architecture-chat-shortcut muted','Ctrl/⌘ + Enter');
    shortcut.setAttribute('aria-hidden','true');
    controls.prepend(shortcut);
    form.append(label('Question',input),contextOptions);
    if (scope.finding && scope.provider) form.append(sourceOption);
    if (scope.provider?.remote) form.append(approval);
    form.append(controls);
    const evidence = make('aside','architecture-chat-evidence');
    evidence.setAttribute('aria-label','Captured architecture evidence');
    conversation.append(log,status,form);
    layout.append(conversation,evidence);
    node.append(head,pins,layout);
    let pending = null;

    function startQuestion(task) {
      mode.value = session.mode = task;
      input.value = session.draft = prompts[task];
      input.focus();
    }

    function renderRecord(details,row) {
      const data = row.data;
      const summary = row.kind === 'SUMMARY' && data.counts?.[0];
      if (summary) {
        const counts = make('dl','architecture-chat-counts');
        for (const [kind,label] of [['MODULE','Modules'],['PACKAGE','Packages'],['TYPE','Types']]) {
          if (summary.nodeCounts?.[kind] !== undefined)
            counts.append(make('dt','',label),make('dd','',summary.nodeCounts[kind].toLocaleString()));
        }
        details.append(make('p','',data.status + ' · ' + data.gapCount.toLocaleString() + ' reported gaps'));
        if (counts.childElementCount) details.append(counts);
      }
      const rows = data.matches || data.modules || data.edges;
      if (rows) {
        const list = make('ul','architecture-chat-evidence-list');
        for (const item of rows) list.append(make('li','',item.name || item.label || item.target));
        details.append(list.childElementCount ? list : make('p','muted','No matching records in this sample.'));
      }
      if (row.kind === 'FINDING') {
        details.append(make('p','',data.classification + ' · ' + data.status));
        if (scope.openFinding) details.append(button('Open finding',scope.openFinding));
      }
      if (row.kind === 'SOURCE') details.append(button('Open source evidence',() => scope.openSource(data)));
      const raw = make('details','architecture-chat-raw');
      raw.append(make('summary','','Evidence data'),make('pre','',JSON.stringify(data,null,2)));
      details.append(raw);
    }

    function renderEvidence(turn, selected) {
      const heading = make('header','architecture-chat-evidence-header');
      heading.append(make('h3','','Evidence'));
      evidence.replaceChildren(heading);
      if (!turn) {
        evidence.append(make('p','muted','Open cited records here after a reply, or read the evidence directly.'));
        return;
      }
      const result = turn.result;
      const digest = make('code','architecture-chat-digest',window.EvoModel.shortDigest(result.evidenceIdentity));
      digest.title = result.evidenceIdentity;
      if (result.evidence.length) heading.append(digest);
      for (const row of result.evidence) {
        const details = make('details','architecture-chat-record');
        details.open = row.id === selected || (!selected && row === result.evidence[0]);
        const recordTitle = make('summary');
        recordTitle.append(make('span','architecture-chat-ref',row.id),make('span','',row.label));
        details.append(recordTitle);
        renderRecord(details,row);
        evidence.append(details);
        if (row.id === selected) {
          details.firstElementChild.focus({preventScroll:true});
          details.scrollIntoView({block:'nearest'});
        }
      }
      if (!result.evidence.length) evidence.append(make('p','muted','Ask an architecture question to see snapshot evidence here.'));
      if (result.model) {
        const receipt = make('details','architecture-chat-record');
        receipt.append(make('summary','','Model receipt'),make('pre','',JSON.stringify(result.model,null,2)));
        evidence.append(receipt);
      }
    }

    function appendTurn(turn) {
      const question = make('article','architecture-chat-message architecture-chat-user');
      question.append(make('h3','','You'),make('p','architecture-chat-copy',turn.question));
      const result = turn.result;
      const reply = make('article','architecture-chat-message architecture-chat-assistant');
      const labels = {ADVICE:'Architecture advice', NEEDS_EVIDENCE:'More evidence needed',
        UNVERIFIED_ADVICE:'Unverified model suggestion',EVIDENCE_ONLY:'Captured evidence',OUT_OF_SCOPE:'Outside architecture scope'};
      if (result.status === 'OUT_OF_SCOPE') reply.classList.add('architecture-chat-redirect');
      reply.append(make('h3','',labels[result.status] || 'Architecture response'),
        make('p','architecture-chat-copy',result.answer || 'The evidence panel shows the records retrieved from this snapshot.'));
      const references = make('div','architecture-chat-links');
      for (const id of result.citations) {
        const row = result.evidence.find(item => item.id === id);
        if (row) references.append(button(id + ' · ' + row.label,() => renderEvidence(turn,id),'button subtle'));
      }
      if (scope.openRepair && result.status !== 'OUT_OF_SCOPE') references.append(button('Open repair workflow',scope.openRepair));
      if (references.childElementCount) reply.append(references);
      if (result.limitations.length) {
        const list = make('ul','architecture-chat-limitations');
        result.limitations.forEach(text => list.append(make('li','',text)));
        reply.append(list);
      }
      log.append(question,reply);
    }

    function renderConversation() {
      log.replaceChildren();
      if (!session.turns.length) {
        const empty = make('article','architecture-chat-welcome');
        empty.append(make('h3','','What would you like to understand?'),
          make('p','muted',scope.finding ? 'Trace the attached finding and outline a change.' :
            'Explore dependencies, test a design idea, or plan a repair from this snapshot.'));
        const choices = make('div','architecture-chat-starters');
        for (const [task,title] of Object.entries(taskTitles)) choices.append(button(title,() => startQuestion(task),'button'));
        empty.append(choices);
        log.append(empty);
      }
      session.turns.forEach(appendTurn);
      log.scrollTop = log.scrollHeight;
      renderEvidence(session.turns.at(-1));
    }

    function reset() {
      session.turns = [];
      session.draft = session.focus = '';
      session.mode = scope.finding ? 'REPAIR' : 'EXPLORE';
      mode.value = session.mode;
      input.value = focus.value = '';
      source.checked = Boolean(scope.finding && scope.provider);
      describeDisclosure();
      status.textContent = 'New conversation started.';
      renderConversation();
      input.focus();
    }

    form.addEventListener('submit',async event => {
      event.preventDefault();
      const question = input.value.trim();
      if (pending || !question) return;
      const evidenceOnly = !scope.provider || event.submitter === local;
      if (!evidenceOnly && scope.provider?.remote && !consent.checked) {
        status.textContent = 'Approve this turn before sending to the remote provider.';
        consent.focus();
        return;
      }
      const messages = session.turns.filter(turn => turn.result.answer && turn.result.status !== 'OUT_OF_SCOPE').slice(-3).flatMap(turn =>
        [{role:'user',content:turn.question},{role:'assistant',content:shortHistory(turn.result.answer)}]);
      messages.push({role:'user',content:question});
      const query = {context:scope.context,mode:mode.value,messages,
        ...(focus.value.trim() ? {focus:focus.value.trim()} : {}),
        ...(scope.finding ? {finding:scope.finding} : {}),
        ...(!evidenceOnly && source.checked ? {includeSource:true} : {}),
        ...(evidenceOnly ? {evidenceOnly:true} : scope.provider?.remote ? {allowRemote:true} : {})};
      consent.checked = false;
      pending = new AbortController();
      const request = pending;
      const signal = AbortSignal.any([scope.signal,request.signal]);
      submit.disabled = local.disabled = fresh.disabled = mode.disabled = focus.disabled = usePrompt.disabled = true;
      source.disabled = consent.disabled = true;
      input.readOnly = true;
      stop.hidden = false;
      status.textContent = evidenceOnly ? 'Reading snapshot evidence…' : 'Reading snapshot evidence and waiting for the model…';
      try {
        const result = await scope.send(query,signal);
        if (signal.aborted) return;
        if (result.pin.graph !== scope.graph || result.pin.context !== scope.context || result.pin.snapshot !== scope.snapshot ||
            ['identity','bundle','report'].some(field => result.pin.finding?.[field] !== scope.finding?.[field]))
          throw new Error('The reply belongs to a different snapshot, context, or finding.');
        session.turns.push({question,result});
        if (session.turns.length > 12) session.turns.shift();
        input.value = session.draft = '';
        renderConversation();
        status.textContent = result.status === 'OUT_OF_SCOPE' ? 'Try a software architecture question.'
          : evidenceOnly ? 'Captured evidence loaded.' : 'Reply received.';
      } catch (error) {
        if (!scope.signal.aborted) status.textContent = request.signal.aborted ? 'Stopped waiting. The question is ready to retry.' : error.message;
      } finally {
        pending = null;
        if (!scope.signal.aborted) {
          submit.disabled = local.disabled = fresh.disabled = mode.disabled = focus.disabled = usePrompt.disabled = input.readOnly = false;
          source.disabled = consent.disabled = false;
          const restore = document.activeElement === stop;
          stop.hidden = true;
          if (restore) input.focus();
        }
      }
    });

    function shortHistory(answer) {
      if (answer.length <= 4000) return answer;
      return answer.slice(0,3980).replace(/[\uD800-\uDBFF]$/,'') + '\n[Reply excerpt]';
    }
    input.addEventListener('keydown',event => {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
        event.preventDefault();
        if (!pending) form.requestSubmit(submit);
      }
    });
    renderConversation();
    return node;
  };
})();
