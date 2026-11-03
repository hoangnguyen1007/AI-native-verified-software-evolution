(function (root, factory) {
  const api = factory(); if (typeof module === 'object' && module.exports) module.exports = api; else root.EvoBridgeModel = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const sections = ['PARTITIONS','ENDPOINTS','REQUIREMENTS','RELATIONS','GAPS','FACTS','NATIVE_GAPS','ARTIFACTS','DOCUMENTS'];
  const native = section => ['FACTS','NATIVE_GAPS','ARTIFACTS','DOCUMENTS'].includes(section);
  const opaque = value => typeof value !== 'string' || /^(sha256:|@generated\/)|^[a-f0-9]{32,}$/i.test(value);
  const readable = (value, fallback = '') => !opaque(value) && value !== 'NOT_RECORDED' ? value : fallback;
  const languages = {frontendarchitecture:'Frontend / config',java:'Java',kotlin:'Kotlin',scala:'Scala',python:'Python',typescript:'TypeScript',javascript:'JavaScript',c:'C',cpp:'C++',csharp:'C#'};
  const categories = {A01:'Membership',A02:'Declarations',A03:'References',A04:'Types',A05:'Hierarchy',A06:'Calls',A07:'Data access',A08:'Generated / implicit',A09:'Module boundaries',A10:'Guards',A11:'Diagnostics',A12:'Accounting',B01:'JVM interop',B02:'HTTP contracts',B03:'Schema services',B04:'Messaging',B05:'Persistence',B06:'Native ABI',B07:'IPC / workers',B08:'Generated lineage',F01:'Modules',F02:'Components',F03:'Routes',F04:'State / services',F05:'API contracts',F06:'Execution',F07:'Assets / styles',F08:'Build / source maps',F09:'Embedded / microfrontends',F10:'Configuration',F11:'Tests / generated code',F12:'Dynamic / recovery'};
  const kinds = {'vue-script':'Vue script','vue-render':'Vue render','vue-script-map':'Vue script map','vue-render-map':'Vue render map',COMPONENT_DECLARATION:'Component',COMPONENT_USE:'Component use',NATIVE_ROUTE_DECLARATION:'Route',ROUTE_DECLARATION:'Route',API_OPERATION:'API operation',CLIENT_API_USE:'API call',NETWORK_REQUEST:'Network request',MODULE_REFERENCE:'Module reference',SFC_MODULE_REFERENCE:'Component import',RESOURCE_REFERENCE:'Resource reference',CALLABLE_OWNER:'Callable',SOURCE_MAP_BINDING:'Source map binding',SOURCE_MAP_DOCUMENT:'Source map',SOURCE_MAP_CHAIN:'Source map chain',CONFIG_DOCUMENT:'Configuration',CONFIG_KEY:'Config entry',API_RESPONSE:'API response',API_PARAMETER:'API parameter',API_SERVER:'Declared server',API_REQUEST_BODY:'Request body',PACKAGE_DECLARATION:'Package',PACKAGE_EXPORT:'Package export',WORKER_BOUNDARY:'Worker boundary',STATE_DECLARATION:'State declaration',STATE_USE:'State use',EVENT_HANDLER:'Event handler',GENERATED_FROM:'Generated from',SYMBOL_CORRESPONDENCE:'Symbol correspondence',CONSUMES_CONTRACT:'Consumes contract',EXPOSES_CONTRACT:'Exposes contract',ABI_BINDING:'Native binding',PUBLISHES_TO:'Publishes to',SUBSCRIBES_TO:'Subscribes to',READS:'Reads',WRITES:'Writes',READS_WRITES:'Reads and writes',DATA_ACCESS:'Data access',SENDS_TO:'Sends to'};
  const states = {
    RESOLVED:['Resolved','match'],BOUND:['Matched','match'],COMPLETE:['Captured','neutral'],PARTIAL:['Partial','warning'],FAILED:['Failed','warning'],MISSING_PROVIDER:['Missing provider','warning'],CONTRACT_ONLY:['Contract only','warning'],CANDIDATE:['Candidate','warning'],CANDIDATES:['Candidate','warning'],AMBIGUOUS:['Ambiguous','warning'],CONDITIONAL:['Conditional','warning'],UNRESOLVED:['Unresolved','warning'],UNKNOWN:['Unresolved','warning'],UNSUPPORTED:['Unsupported','warning'],ERROR:['Error','warning'],REOPENED:['Reopened','warning'],INFEASIBLE:['Infeasible','neutral'],NOT_APPLICABLE:['Not applicable','neutral'],APPLICABLE:['Evidence required','neutral'],CAPTURED:['Captured','neutral']
  };
  const reasons = {
    BRIDGE_METHOD_UNQUALIFIED:['Method unqualified','The provider produced evidence; its method is not independently qualified for this scope.','Method qualification is required before architectural assessment.'],
    BRIDGE_CONTEXT_SELECTION_INCOMPLETE:['Incomplete build context','Build root, module, source set or variant has not been recorded.','Capture the missing build-selection metadata.'],
    BRIDGE_CHILD_PARTIAL:['Partial context','Usable facts are retained; unresolved boundaries remain explicit.','Inspect this context’s source facts and outstanding requirements.'],
    BRIDGE_CHILD_MISSING_PROVIDER:['Provider unavailable','No matching provider result is available for this context.','Supply the matching provider result; other contexts remain readable.'],
    BRIDGE_CHILD_FAILED:['Capture failed','Capture failed. This does not establish an architectural defect.','Inspect the capture failure and supply valid evidence.'],
    BRIDGE_REOPENED:['Evidence withdrawn','Supporting evidence was withdrawn; dependent bindings have reopened.','Supply current evidence and reconcile again.'],
    BRIDGE_CENSUS_MISSING:['Untracked capability','The capability census is incomplete; absence cannot be inferred.','Complete the context/category census.']
  };
  function request(selection, cursor) {
    if (!/^sha256:[0-9a-f]{64}$/.test(selection.scope) || !sections.includes(selection.section)) throw new Error('Select a saved analysis.');
    const body = {scope:selection.scope,section:selection.section,size:20};
    for (const key of ['category','partition','symbol','document','search']) if (selection[key]) body[key] = selection[key];
    if (native(body.section) && !body.partition) throw new Error('Select a context.');
    if (body.category && !(native(body.section) ? /^[AF](0[1-9]|1[0-2])$/ : /^B0[1-8]$/).test(body.category) || ['ARTIFACTS','DOCUMENTS'].includes(body.section) && body.category) throw new Error('Select a category supported by this view.');
    if (body.symbol && !/^sha256:[0-9a-f]{64}$/.test(body.symbol)) throw new Error('Invalid symbol / record ID.');
    if (body.document && !native(body.section) && body.section !== 'ENDPOINTS') throw new Error('File filters apply to source and endpoint views.');
    if (cursor) body.cursor = cursor;
    return body;
  }
  function formatCount(value) { if(typeof value==='number'&&!Number.isSafeInteger(value))return 'Exact count unavailable'; return /^\d+$/.test(String(value)) ? new Intl.NumberFormat('en-US').format(BigInt(value)) : 'Unavailable'; }
  function moduleName(row) { const f=row.fields||{}; return readable(f.module,readable(row.key,languages[f.language]||'Unnamed context')); }
  function moduleContext(row) { const f=row.fields||{}; return [languages[f.language]||readable(f.language),readable(f.sourceSet),readable(f.variant),f.root!=='.'?readable(f.root):''].filter(Boolean).join(' · '); }
  function sourceName(path) { return readable(path,'Generated artifact'); }
  function reason(code) { return reasons[code] || [String(code||'').includes('CENSUS')?'Untracked capability':'Evidence required','Evidence is insufficient to establish the relation in this context.','Inspect the captured source and outstanding evidence requirements.']; }
  function descriptorName(d,kind,source){
    const action={READS:'Reads',WRITES:'Writes',READS_WRITES:'Reads and writes',PUBLISHES:'Publishes',SUBSCRIBES:'Subscribes'};
    return [readable(d.binaryOwner),readable(d.member),readable(d.method),readable(d.path),readable(d.service),readable(d.operation),d.object?[readable(d.schema),readable(d.object)].filter(Boolean).join('.'):null,readable(d.channel),action[d.access||d.bindingRole]].filter(Boolean).join(' · ')||kind+' · '+sourceName(source);
  }
  function describe(row) {
    const f=row.fields||{}, a=f.attributes||{}, source=f.path||(row.kind==='DOCUMENT'?row.key:'')||(f.source&&f.source.path)||(typeof f.source==='string'?f.source:''), state=f.state||f.status||f.applicability||f.reasonCode||f.reason||'CAPTURED';
    let title, kind=kinds[f.kind]||kinds[f.relation]||categories[f.profile||f.category]||'Record';
    if (f.language) title=moduleName(row);
    else if (!f.reasonCode&&!f.reason&&/^A(0[1-9]|1[0-2])$/.test(f.category||'')) title=categories[f.category]+' · '+(readable(f.symbols&&f.symbols[0]&&f.symbols[0].name)||readable(f.targets&&typeof f.targets[0]==='object'&&f.targets[0].name)||sourceName(source));
    else if (f.reasonCode||f.reason) title=reason(f.reasonCode||f.reason)[0];
    else if (f.from) title=kind+' · '+sourceName(f.from.source&&f.from.source.path)+(f.targets&&f.targets.length?' → '+f.targets.map(target=>sourceName(target.source&&target.source.path)).join(', '):' → unresolved target');
    else if (f.kind==='CONFIG_KEY'&&Array.isArray(a.keyPath)&&a.keyPath.every(key=>typeof key==='string')) title=kind+' · /'+a.keyPath.map(key=>key.replaceAll('~','~0').replaceAll('/','~1')).join('/');
    else if (f.kind==='API_OPERATION') title=[a.method,a.route].filter(Boolean).join(' ')||kind;
    else if (f.kind==='API_RESPONSE'&&typeof a.statusCode==='string') title=kind+' · '+a.statusCode;
    else if (f.kind==='SFC_MODULE_REFERENCE'&&!opaque(f.target)) title=kind+' · '+f.target;
    else if (f.descriptor||a.descriptor) title=descriptorName(f.descriptor||a.descriptor,kind,source);
    else if (f.kind) title=kind+' · '+(readable(a.name)||readable(a.declaredName)||readable(a.route)||sourceName(source).split('/').pop());
    else if (f.applicability) title=categories[f.profile]||'Evidence requirement';
    else title=sourceName(source||row.key);
    const label=states[state]||['Inspect evidence','warning'];let location=source?sourceName(source):'';
    const precision=a.rangePrecision||f.source&&f.source.precision||f.descriptor&&f.descriptor.sourcePrecision, span=f.span;
    if (source&&precision==='DOCUMENT_SUPPORT') location+=' · whole file';
    else if (source&&span&&span.startLine) location+=' · line '+span.startLine+(span.endLine!==span.startLine?'–'+span.endLine:'');
    return {title,kind,state,statusLabel:label[0],tone:label[1],partition:f.partition||(f.from&&f.from.partition)||'',source,location,precision:precision==='DOCUMENT_SUPPORT'?'Document':precision==='ORIGINAL_SOURCE_RANGE'?'Source range':'Not recorded',category:categories[f.category||f.profile]||'',explanation:f.reasonCode||f.reason?reason(f.reasonCode||f.reason)[1]:state==='BOUND'?'Captured operands match in the selected context. Method qualification and runtime observation are separate.':state==='RESOLVED'?'The provider resolved this declaration or relation in captured source.':state==='NOT_APPLICABLE'?'Explicit non-applicability with retained reason and evidence.':'The binding remains conditional or incomplete. Inspect operands and source evidence.'};
  }
  function stats(summary) {
    const outcomes=summary.accounting?summary.accounting.bindingOutcomes:summary.outcomes||{}, pending=Object.entries(outcomes).filter(([key])=>!['BOUND','NOT_APPLICABLE','INFEASIBLE'].includes(key)).reduce((sum,[,value])=>sum+BigInt(value),0n).toString();
    return [
      {key:'parts',label:'Contexts',value:summary.partitions,help:'Selected module / source-set / build-variant partitions.',section:'PARTITIONS'},
      {key:'matched',label:'Matched',value:outcomes.BOUND||'0',help:'Matched requirement records; not method qualification.',section:'RELATIONS'},
      {key:'pending',label:summary.accounting?'Pending bindings':'Unresolved',value:pending,help:'Unresolved, candidate, conditional or reopened bindings.',section:'REQUIREMENTS'},
      summary.accounting?{key:'coverage',label:'Coverage checks',value:summary.accounting.evidenceObligations,help:'Evidence obligations without a concrete source endpoint. These remain open; they are not failed bindings.',section:'REQUIREMENTS'}:{key:'missing',label:'Untracked',value:summary.missingCensus,help:'Missing context/category census records; not checked or non-applicable.',section:'GAPS'}
    ];
  }
  function error(code) { if (/CURSOR|STALE/.test(code)) return 'Evidence changed. Refresh this view.';if (/POLICY/.test(code))return 'Read policy unavailable or revoked.';if (/NOT_FOUND/.test(code))return 'Not found in the current analysis.';if (/CANCELLED|AbortError/.test(code))return 'Cancelled.';return 'Unable to load evidence. Retry or inspect metadata.'; }
  function structure(partition){return partition&&partition.fields&&partition.fields.child?'FACTS':'DOCUMENTS';}
  function impactReference(row){const f=row.fields||{};return [...(Array.isArray(f.targets)?f.targets:[]),f.from].find(ref=>ref&&typeof ref==='object'&&/^sha256:[a-f0-9]{64}$/.test(ref.identity||''))||null;}
  function evidenceLabel(evidence,row,partitions,documents,index){
    const digest=typeof evidence==='string'?evidence:evidence.digest,f=row.fields||{};
    for(const document of documents)if(document.fields.content&&document.fields.content.digest===digest)return document.key;
    if(f.source&&f.source.content&&f.source.content.digest===digest)return sourceName(f.source.path);
    for(const partition of partitions){const p=partition.fields;if(p.capture&&p.capture.digest===digest)return 'Capture · '+moduleName(partition);if(p.child&&p.child.request.digest===digest)return 'Capture request · '+moduleName(partition);}
    const roles={contract:'API contract',parameters:'Request schemas',source:'Source bytes',artifact:'Generated artifact',generator:'Generator identity',lineage:'Generation lineage',library:'Native library',registration:'Registration',database:'Database identity',connection:'Connection identity',routing:'Routing',payload:'Payload schema',schema:'Schema',base:'Base routing'};
    for(const [key,label] of Object.entries(roles))if((f.descriptor||f.attributes&&f.attributes.descriptor||{})[key]===digest)return label;
    return 'Supporting reference '+index;
  }
  return {request,describe,formatCount,moduleName,moduleContext,sourceName,reason,stats,error,native,categories,readable,opaque,structure,evidenceLabel,impactReference};
});
