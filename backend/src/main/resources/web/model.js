'use strict';
// Presentation helpers shared by the browser and bounded Node regression checks.
(function (root) {
  const terminal = job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.state);
  const label = row => row.qualifiedName || row.spelling || row.canonicalName || row.label || row.kind || row.identity;
  const pageInfo = result => {
    const shown = result.items?.length || 0;
    const total = result.exactTotal ?? result.total ?? result.returnedTotal;
    const status = result.status || result.evaluationStatus;
    const incomplete = status && status !== 'COMPLETE' || result.bounds?.truncated || result.operation && result.operation !== 'COMPLETED';
    return `${shown}${total == null ? ' shown' : ` of ${total}${result.returnedTotal == null ? '' : ' observed'}`} · ${incomplete ? 'Incomplete evidence' : 'Available results'}${result.continuation ? ' · More available' : ''}`;
  };
  const request = context => ({context, scope: 'TYPE', projection: 'BOUNDED_UNION', direction: 'OUTGOING', relationshipKinds: ['graph.depends-on']});
  const assessment = value => value?.score == null ? 'Unavailable' : `${value.score} / 100`;
  const apiError = code => ({
    INVALID_REQUEST: 'Check the input. Use an existing local folder or a public HTTPS repository URL on GitHub, GitLab or Bitbucket.',
    CONNECTIONS_UNAVAILABLE: 'This server does not enable repository connections.',
    JOBS_UNAVAILABLE: 'Analysis is unavailable on this read-only server.',
    JOB_HISTORY_FULL: 'The server has reached its retained analysis limit. Restart it to begin a new session.',
    JOB_QUEUE_FULL: 'The analysis queue is full. Wait for a running analysis to finish.',
    BUSY: 'The server is busy. Try again shortly.',
    RESPONSE_TOO_LARGE: 'The result is too large. Narrow the search or select a smaller graph scope.',
    REPORT_ARTIFACT_UNAVAILABLE: 'This analysis has no published artifact for this view.',
    GRAPH_STORE_UNAVAILABLE: 'The verified analysis snapshot could not be read.',
    REMOTE_FETCH_FAILED: 'The public repository could not be downloaded. Check the URL, network and Git installation. Private repositories require a local checkout.',
    REMOTE_TIMEOUT: 'Repository download exceeded two minutes. Use a local checkout.',
    REMOTE_SIZE_LIMIT: 'Repository download exceeded the input limit. Use a smaller local selection.'
  })[code] || `The request could not be completed (${code || 'connection unavailable'}).`;
  const model = {terminal, label, pageInfo, request, assessment, apiError};
  if (typeof module !== 'undefined') module.exports = model;
  else root.EvoModel = model;
})(globalThis);
