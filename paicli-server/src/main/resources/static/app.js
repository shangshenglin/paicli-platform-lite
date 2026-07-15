const $ = id => document.getElementById(id);
const state = {
  sessions: [],
  groups: [],
  sessionId: localStorage.getItem('paicli_session') || '',
  runId: '',
  runStatus: '',
  stream: null,
  live: null,
  pendingText: '',
  textFrame: 0,
  reasoningActivity: null,
  reasoningChars: 0,
  thinkingMode: localStorage.getItem('paicli_thinking_mode') || 'disabled',
  reasoningEffort: localStorage.getItem('paicli_reasoning_effort') || 'high',
  pendingAttachments: [],
  detailOpen: innerWidth > 1000
};
const terminal = new Set(['COMPLETED', 'FAILED', 'CANCELED']);
const statusNames = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  WAITING_MODEL: '思考中',
  WAITING_TOOL: '执行工具',
  WAITING_APPROVAL: '等待确认',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
};

function headers(json = true) {
  const value = json ? {'Content-Type': 'application/json'} : {};
  const key = sessionStorage.getItem('paicli_api_key');
  if (key) value['X-API-Key'] = key;
  return value;
}

async function api(path, options = {}) {
  const form = options.body instanceof FormData;
  const response = await fetch(path, {
    ...options,
    headers: {...headers(options.body !== undefined && !form), ...(options.headers || {})}
  });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  return response.status === 204 ? null : response.json();
}

function element(tag, className, text) {
  const value = document.createElement(tag);
  if (className) value.className = className;
  if (text !== undefined) value.textContent = text;
  return value;
}

function showNotice(text, error = false) {
  $('notice').textContent = text;
  $('notice').className = `notice${error ? ' error' : ''}`;
}

function setStatus(value = '') {
  state.runStatus = value;
  $('status').textContent = statusNames[value] || '空闲';
  $('status').className = `status${value && !terminal.has(value) ? ' active' : ''}`
    + `${value === 'FAILED' ? ' error' : ''}`;
  $('composer').classList.toggle('running', Boolean(value && !terminal.has(value)));
  $('input').disabled = value === 'WAITING_APPROVAL';
}

function scrollBottom() {
  requestAnimationFrame(() => $('messages').scrollTop = $('messages').scrollHeight);
}

function renderEmpty() {
  const empty = element('div', 'empty');
  const content = element('div');
  content.append(
    element('div', 'logo', 'π'),
    element('h1', '', '今天想完成什么？'),
    element('p', '', '直接描述目标。工具调用、推理和审批会收纳在执行详情中。')
  );
  empty.append(content);
  $('stack').replaceChildren(empty);
}

function renderMessage(message) {
  if (message.archived || message.role === 'summary') return;
  if (message.role === 'tool') {
    const details = element('details', 'tool');
    details.append(
      element('summary', '', `工具结果 · ${message.toolCallId || 'tool'}`),
      element('pre', '', message.content || '')
    );
    $('stack').append(details);
    return;
  }
  const row = element('article', `message ${message.role}`);
  const avatar = element('div', 'avatar', message.role === 'user' ? '你' : 'π');
  const body = element('div', 'body');
  body.append(
    element('div', 'who', message.role === 'user' ? '你' : 'PaiCLI'),
    element('div', 'text', message.content || '')
  );
  if (message.reasoningContent) {
    const reasoning = element('details', 'reason');
    reasoning.append(
      element('summary', '', '查看思考过程'),
      element('pre', '', message.reasoningContent)
    );
    body.append(reasoning);
  }
  row.append(avatar, body);
  $('stack').append(row);
}

async function loadMessages() {
  if (!state.sessionId) return renderEmpty();
  const messages = await api(`/v1/sessions/${state.sessionId}/messages`);
  $('stack').replaceChildren();
  messages.forEach(renderMessage);
  if (!$('stack').children.length) renderEmpty();
  scrollBottom();
}

function renderSessions() {
  $('sessions').replaceChildren();
  const sections = [
    ...state.groups.map(group => ({id: group.id, name: group.name, removable: true})),
    {id: null, name: '未分组', removable: false}
  ];
  for (const group of sections) {
    const members = state.sessions.filter(session => (session.groupId || null) === group.id);
    if (!members.length && !group.removable) continue;
    const section = element('section', 'session-group');
    const heading = element('div', 'session-group-head');
    heading.append(element('span', '', `${group.name}  ${members.length}`));
    if (group.removable) {
      const remove = element('button', 'group-delete', '×');
      remove.title = '删除分组';
      remove.onclick = () => deleteGroup(group);
      heading.append(remove);
    }
    section.append(heading);
    members.forEach(session => section.append(renderSessionItem(session)));
    $('sessions').append(section);
  }
  if (!state.sessions.length) $('sessions').append(element('div', 'nothing', '暂无对话'));
}

function renderSessionItem(session) {
  const row = element('div', `session-row${session.id === state.sessionId ? ' active' : ''}`);
  const button = element('button', 'session');
  button.append(
    element('span', '', session.title || '未命名对话'),
    element('small', 'meta', session.projectKey || 'default')
  );
  button.onclick = () => selectSession(session.id);
  const more = element('button', 'session-more', '⋯');
  more.title = '对话操作';
  const menu = element('div', 'session-menu');
  const label = element('label', '', '移动到分组');
  const select = element('select');
  select.append(element('option', '', '未分组'));
  select.firstElementChild.value = '';
  for (const group of state.groups) {
    const option = element('option', '', group.name);
    option.value = group.id;
    select.append(option);
  }
  select.value = session.groupId || '';
  select.onchange = () => moveSession(session.id, select.value || null);
  const remove = element('button', 'delete-session', '删除对话');
  remove.onclick = () => deleteSession(session);
  label.append(select);
  menu.append(label, remove);
  more.onclick = event => {
    event.stopPropagation();
    document.querySelectorAll('.session-menu.open').forEach(value => {
      if (value !== menu) value.classList.remove('open');
    });
    menu.classList.toggle('open');
  };
  menu.onclick = event => event.stopPropagation();
  row.append(button, more, menu);
  return row;
}

async function refreshSessions() {
  try {
    [state.sessions, state.groups] = await Promise.all([
      api('/v1/sessions'), api('/v1/session-groups')
    ]);
    if (!state.sessions.some(item => item.id === state.sessionId)) {
      state.sessionId = state.sessions[0]?.id || '';
    }
    renderSessions();
    if (state.sessionId) await selectSession(state.sessionId, false);
    else renderEmpty();
  } catch (error) {
    showNotice(`连接失败：${error.message}`, true);
  }
}

async function selectSession(id, rerender = true) {
  state.stream?.abort();
  const previousSession = state.sessionId;
  if (previousSession && previousSession !== id && state.pendingAttachments.length) {
    await Promise.allSettled(state.pendingAttachments.map(attachment =>
      api(`/v1/sessions/${previousSession}/attachments/${attachment.id}`, {method: 'DELETE'})));
  }
  state.sessionId = id;
  state.runId = '';
  state.live = null;
  clearPendingAttachments();
  localStorage.setItem('paicli_session', id);
  if (rerender) renderSessions();
  const session = state.sessions.find(item => item.id === id);
  $('chatTitle').textContent = session?.title || '对话';
  $('runMeta').textContent = session ? `项目 · ${session.projectKey}` : '尚未开始';
  setStatus();
  clearEvents();
  await loadMessages();
  const runs = await api(`/v1/sessions/${id}/runs`);
  if (runs.length) {
    state.runId = runs[0].id;
    $('runMeta').textContent = state.runId;
    setStatus(runs[0].status);
    await loadApprovals();
    watch(state.runId);
  }
  $('sidebar').classList.remove('open');
}

async function createSession() {
  try {
    const currentGroup = state.sessions.find(item => item.id === state.sessionId)?.groupId || null;
    const session = await api('/v1/sessions', {
      method: 'POST',
      body: JSON.stringify({title: '新对话', projectKey: 'default', groupId: currentGroup})
    });
    state.sessions.unshift(session);
    await selectSession(session.id);
    renderSessions();
    $('input').focus();
  } catch (error) {
    showNotice(error.message, true);
  }
}

async function moveSession(sessionId, groupId) {
  try {
    await api(`/v1/sessions/${sessionId}`, {
      method: 'PATCH', body: JSON.stringify({groupId})
    });
    showNotice(groupId ? '已移动对话' : '已移到未分组');
    await refreshSessions();
  } catch (error) {
    showNotice(error.message, true);
  }
}

async function deleteSession(session) {
  if (!confirm(`删除“${session.title || '未命名对话'}”及其所有消息和执行记录？`)) return;
  try {
    await api(`/v1/sessions/${session.id}`, {method: 'DELETE'});
    if (state.sessionId === session.id) {
      state.stream?.abort();
      state.sessionId = '';
      state.runId = '';
      localStorage.removeItem('paicli_session');
      setStatus();
      clearEvents();
    }
    showNotice('对话已删除');
    await refreshSessions();
  } catch (error) {
    showNotice(error.message, true);
  }
}

async function deleteGroup(group) {
  if (!confirm(`删除分组“${group.name}”？组内对话会移到未分组。`)) return;
  try {
    await api(`/v1/session-groups/${group.id}`, {method: 'DELETE'});
    showNotice('分组已删除，对话已移到未分组');
    await refreshSessions();
  } catch (error) {
    showNotice(error.message, true);
  }
}

async function createGroup() {
  const name = $('groupName').value.trim();
  if (!name) return;
  try {
    await api('/v1/session-groups', {
      method: 'POST', body: JSON.stringify({name})
    });
    $('groupDialog').close();
    $('groupName').value = '';
    showNotice('分组已创建');
    await refreshSessions();
  } catch (error) {
    showNotice(error.message, true);
  }
}

function resizeInput() {
  $('input').style.height = 'auto';
  $('input').style.height = `${Math.min($('input').scrollHeight, 170)}px`;
}

async function sendMessage() {
  const text = $('input').value.trim() || (state.pendingAttachments.length ? '请分析这些附件。' : '');
  if (!text) return;
  if (state.runStatus && !terminal.has(state.runStatus)) {
    return showNotice('当前任务仍在运行', true);
  }
  try {
    if (!state.sessionId) await createSession();
    const run = await api(`/v1/sessions/${state.sessionId}/runs`, {
      method: 'POST',
      body: JSON.stringify({
        input: text,
        thinkingMode: state.thinkingMode,
        reasoningEffort: state.thinkingMode === 'enabled' ? state.reasoningEffort : '',
        attachmentIds: state.pendingAttachments.map(item => item.id)
      })
    });
    $('input').value = '';
    clearPendingAttachments();
    resizeInput();
    state.runId = run.id;
    $('runMeta').textContent = run.id;
    setStatus(run.status);
    await loadMessages();
    clearEvents();
    watch(run.id);
  } catch (error) {
    showNotice(error.message, true);
  }
}

function renderPendingAttachments() {
  $('attachmentPreview').replaceChildren();
  state.pendingAttachments.forEach((attachment, index) => {
    const chip = element('div', 'attachment-chip');
    const media = attachment.preview ? document.createElement('img') : element('div', 'document-icon', 'DOC');
    if (attachment.preview) {
      media.src = attachment.preview;
      media.alt = attachment.name;
    }
    const label = element('span', '', attachment.name);
    const remove = element('button', '', '×');
    remove.onclick = async () => {
      try {
        await api(`/v1/sessions/${state.sessionId}/attachments/${attachment.id}`, {method: 'DELETE'});
      } catch (error) {
        return showNotice(`附件移除失败：${error.message}`, true);
      }
      if (attachment.preview) URL.revokeObjectURL(attachment.preview);
      state.pendingAttachments.splice(index, 1);
      renderPendingAttachments();
    };
    chip.append(media, label, remove);
    $('attachmentPreview').append(chip);
  });
}

function clearPendingAttachments() {
  state.pendingAttachments.forEach(item => { if (item.preview) URL.revokeObjectURL(item.preview); });
  state.pendingAttachments = [];
  if ($('attachmentPreview')) renderPendingAttachments();
}

async function uploadAttachments(files) {
  if (!files.length) return;
  if (!state.sessionId) await createSession();
  for (const file of files) {
    if (state.pendingAttachments.length >= 8) {
      showNotice('每次最多添加 8 个附件（图片和文档各最多 4 个）', true);
      break;
    }
    const image = file.type.startsWith('image/');
    const sameKind = state.pendingAttachments.filter(item => item.kind === (image ? 'image' : 'document')).length;
    if (sameKind >= 4) {
      showNotice(image ? '每次最多添加 4 张图片' : '每次最多添加 4 个文档', true);
      continue;
    }
    const form = new FormData();
    form.append('file', file);
    try {
      const endpoint = image ? 'images' : 'documents';
      const attachment = await api(`/v1/sessions/${state.sessionId}/attachments/${endpoint}`, {
        method: 'POST', body: form
      });
      state.pendingAttachments.push({...attachment, kind: image ? 'image' : 'document',
        preview: image ? URL.createObjectURL(file) : null});
      renderPendingAttachments();
      const visualPdf = attachment.mimeType === 'application/vnd.paicli.visual-pdf';
      showNotice(image ? `图片已添加：${file.name}`
        : visualPdf ? `扫描 PDF 已按页面图像添加；回答模型需要支持视觉：${file.name}`
        : `文档已索引并添加到本轮：${file.name}`);
    } catch (error) {
      showNotice(`${image ? '图片' : '文档'}上传失败：${error.message}`, true);
    }
  }
  $('attachmentInput').value = '';
}

function currentProjectKey() {
  return state.sessions.find(item => item.id === state.sessionId)?.projectKey || 'default';
}

async function openCapabilities() {
  const project = currentProjectKey();
  $('capabilityProject').textContent = `当前项目：${project}`;
  $('capabilityDialog').showModal();
  await refreshCapabilities();
}

async function refreshCapabilities() {
  const project = encodeURIComponent(currentProjectKey());
  try {
    const [skills, documents, status] = await Promise.all([
      api(`/v1/skills?projectKey=${project}`),
      api(`/v1/knowledge/documents?projectKey=${project}`),
      api(`/v1/capabilities/status?projectKey=${project}`)
    ]);
    renderCapabilityStatus(status);
    $('skillList').replaceChildren(...skills.map(skill => managedItem(
      skill.name, `${skill.source} · ${skill.description || '无描述'}`,
      () => deleteSkill(skill.name, skill.source === 'global')
    )));
    if (!skills.length) $('skillList').append(element('div', 'hint', '尚未安装 Skill'));
    $('knowledgeList').replaceChildren(...documents.map(document => managedItem(
      document.name, `${Math.ceil(document.size / 1024)} KB · 已完成分块向量索引`,
      () => deleteKnowledge(document.name)
    )));
    if (!documents.length) $('knowledgeList').append(element('div', 'hint', '尚未上传知识文档'));
  } catch (error) {
    showNotice(`能力列表加载失败：${error.message}`, true);
  }
}

function renderCapabilityStatus(status) {
  const labels = [
    ['RAG', `${status.rag.automaticRetrieval ? '自动召回' : '手动召回'} · ${status.rag.semanticEmbedding ? '语义向量' : '离线词法降级'}`],
    ['Memory', `${status.memory.automaticExtraction ? '自动提取' : '仅手动'} · ${status.memory.layers}`],
    ['模型治理', `重试 ${status.model.retryAttempts} 次 · ${status.model.maxRunSteps} 步预算`],
    ['联网', status.web.enabled ? '已启用 · SSRF 防护' : '未启用'],
    ['MCP', `${status.mcp.ready}/${status.mcp.servers} 就绪`],
    ['多模态', status.multimodal.images ? '图片与文档' : '未启用']
  ];
  $('capabilityStatus').replaceChildren(...labels.map(([name, value]) => {
    const item = element('div', 'capability-status-item');
    item.append(element('strong', '', name), element('small', '', value));
    return item;
  }));
}

function managedItem(title, subtitle, removeAction) {
  const item = element('div', 'managed-item');
  const main = element('div', 'managed-main');
  main.append(element('strong', '', title), element('small', '', subtitle));
  const remove = element('button', 'secondary', '删除');
  remove.onclick = removeAction;
  item.append(main, remove);
  return item;
}

async function importSkill() {
  const gitUrl = $('skillGitUrl').value.trim();
  if (!gitUrl) return showNotice('请输入 Skill Git 地址', true);
  const global = $('skillGlobal').checked;
  try {
    showNotice('正在下载并校验 Skill…');
    await api('/v1/skills/imports', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), gitUrl, name: $('skillName').value.trim() || null, global
    })});
    $('skillGitUrl').value = '';
    $('skillName').value = '';
    $('skillGlobal').checked = false;
    showNotice(`${global ? '全局' : '项目'} Skill 已安装；后续对话会自动看到它的索引`);
    await refreshCapabilities();
  } catch (error) { showNotice(`Skill 安装失败：${error.message}`, true); }
}

async function deleteSkill(name, global) {
  if (!confirm(`删除${global ? '全局' : '项目'} Skill“${name}”？`)) return;
  try {
    await api(`/v1/skills/${encodeURIComponent(name)}?projectKey=${encodeURIComponent(currentProjectKey())}&global=${global}`,
      {method: 'DELETE'});
    await refreshCapabilities();
  } catch (error) { showNotice(error.message, true); }
}

async function uploadKnowledge() {
  const files = [...$('knowledgeFiles').files];
  if (!files.length) return showNotice('请选择知识文档', true);
  for (const file of files) {
    const form = new FormData();
    form.append('file', file);
    try {
      showNotice(`正在提取并索引：${file.name}`);
      await api(`/v1/knowledge/documents/uploads?projectKey=${encodeURIComponent(currentProjectKey())}`,
        {method: 'POST', body: form});
    } catch (error) {
      showNotice(`${file.name} 上传失败：${error.message}`, true);
      return;
    }
  }
  $('knowledgeFiles').value = '';
  showNotice(`${files.length} 个文档已完成文本提取和向量索引`);
  await refreshCapabilities();
}

async function deleteKnowledge(name) {
  if (!confirm(`删除知识文档“${name}”？`)) return;
  try {
    await api(`/v1/knowledge/documents/${encodeURIComponent(currentProjectKey())}/${encodeURIComponent(name)}`,
      {method: 'DELETE'});
    await refreshCapabilities();
  } catch (error) { showNotice(error.message, true); }
}

async function stopRun() {
  if (!state.runId) return;
  try {
    const result = await api(`/v1/runs/${state.runId}/cancel`, {
      method: 'POST', body: '{}'
    });
    if (result.canceled) {
      setStatus('CANCELED');
      showNotice(result.modelRequestCanceled ? '模型请求已终止' : '任务已取消');
    }
  } catch (error) {
    showNotice(error.message, true);
  }
}

function liveAssistant() {
  if (state.live) return state.live;
  const row = element('article', 'message assistant');
  const avatar = element('div', 'avatar', 'π');
  const body = element('div', 'body');
  const text = element('div', 'text cursor', '');
  body.append(element('div', 'who', 'PaiCLI · 正在回复'), text);
  row.append(avatar, body);
  $('stack').querySelector('.empty')?.remove();
  $('stack').append(row);
  state.live = {row, text};
  return state.live;
}

function flushLiveText() {
  if (state.textFrame) cancelAnimationFrame(state.textFrame);
  state.textFrame = 0;
  if (!state.pendingText) return;
  liveAssistant().text.textContent += state.pendingText;
  state.pendingText = '';
  scrollBottom();
}

function queueLiveText(text) {
  state.pendingText += text || '';
  if (!state.textFrame) state.textFrame = requestAnimationFrame(flushLiveText);
}

function finishLive() {
  flushLiveText();
  if (!state.live) return;
  state.live.text.classList.remove('cursor');
  const label = state.live.row.querySelector('.who');
  if (label) label.textContent = 'PaiCLI';
  state.live = null;
}

function parseData(value) {
  try { return JSON.parse(value || '{}'); }
  catch { return {raw: value}; }
}

function eventSummary(type, data) {
  if (type === 'model.delta') return '正在生成回答';
  if (type === 'model.reasoning.delta') return '收到推理内容';
  if (type === 'model.tool_calls') return `模型请求 ${data.count || 0} 个工具`;
  if (type === 'tool.requested') return `请求工具：${data.name || ''}`;
  if (type === 'tool.completed') return `工具完成 · ${data.durationMs || 0}ms`;
  if (type === 'tool.failed') return `工具失败：${data.error || ''}`;
  if (type === 'approval.requested') return '等待人工确认';
  if (type === 'run.status_changed') return statusNames[data.status] || data.status;
  return type.replaceAll('.', ' · ');
}

function addEvent(event, data) {
  const container = $('events');
  container.querySelector('.nothing')?.remove();
  const followTail = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
  const item = element('div', 'event');
  const top = element('div', 'event-top');
  top.append(element('span', '', event.type), element('i', '', `#${event.id || ''}`));
  item.append(top, element('div', 'summary', eventSummary(event.type, data)));
  const details = element('details');
  details.append(
    element('summary', 'hint', '原始数据'),
    element('pre', 'raw', JSON.stringify(data, null, 2))
  );
  item.append(details);
  container.append(item);
  while (container.children.length > 160) container.firstElementChild.remove();
  if (followTail) container.scrollTop = container.scrollHeight;
}

function updateReasoningActivity(event, data) {
  state.reasoningChars += (data.content || '').length;
  if (!state.reasoningActivity) {
    const item = element('div', 'event');
    const top = element('div', 'event-top');
    top.append(element('span', '', 'model.reasoning.delta'), element('i', '', `#${event.id || ''}`));
    const summary = element('div', 'summary', '正在接收推理内容');
    item.append(top, summary);
    $('events').querySelector('.nothing')?.remove();
    $('events').append(item);
    state.reasoningActivity = {item, summary, eventId: top.lastElementChild};
  }
  state.reasoningActivity.summary.textContent = `推理流已合并 · ${state.reasoningChars} 字符`;
  state.reasoningActivity.eventId.textContent = `#${event.id || ''}`;
}

async function handleEvent(event) {
  const data = parseData(event.data);
  if (event.type === 'model.delta') {
    queueLiveText(data.content);
  } else if (event.type === 'model.reasoning.delta') {
    updateReasoningActivity(event, data);
  } else if (event.type === 'run.status_changed') {
    addEvent(event, data);
    setStatus(data.status);
  } else if (event.type === 'approval.requested') {
    addEvent(event, data);
    setStatus('WAITING_APPROVAL');
    await loadApprovals();
  } else if (event.type === 'model.completed' || event.type === 'tool.completed') {
    addEvent(event, data);
    finishLive();
    await loadMessages();
  } else if (event.type === 'run.completed') {
    addEvent(event, data);
    setStatus('COMPLETED');
    finishLive();
    await loadMessages();
    await loadApprovals();
    showNotice('回答完成');
    return true;
  } else if (event.type === 'run.failed') {
    addEvent(event, data);
    setStatus('FAILED');
    finishLive();
    await loadMessages();
    showNotice(data.error || '任务失败', true);
    return true;
  } else if (event.type === 'run.canceled') {
    addEvent(event, data);
    setStatus('CANCELED');
    finishLive();
    await loadMessages();
    showNotice('任务已取消');
    return true;
  } else {
    addEvent(event, data);
  }
  return false;
}

async function watch(runId) {
  state.stream?.abort();
  const controller = new AbortController();
  state.stream = controller;
  let terminalSeen = false;
  let streamError = null;
  const dispatchFrame = async frame => {
    const event = {type: 'message', data: '', id: 0};
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('id:')) event.id = Number(line.slice(3));
      else if (line.startsWith('event:')) event.type = line.slice(6).trim();
      else if (line.startsWith('data:')) event.data += line.slice(5).trim();
    }
    if (event.data && await handleEvent(event)) terminalSeen = true;
  };
  try {
    const response = await fetch(`/v1/runs/${runId}/events?after=0`, {
      headers: headers(false), signal: controller.signal
    });
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      buffer += decoder.decode(part.value, {stream: true});
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop();
      for (const frame of frames) {
        await dispatchFrame(frame);
        if (terminalSeen) break;
      }
      if (terminalSeen) {
        await reader.cancel();
        break;
      }
    }
    if (!terminalSeen && buffer.trim()) await dispatchFrame(buffer);
  } catch (error) {
    if (error.name !== 'AbortError') streamError = error;
  } finally {
    if (state.stream === controller) state.stream = null;
    if (state.runId !== runId || controller.signal.aborted) return;
    try {
      const run = await api(`/v1/runs/${runId}`);
      setStatus(run.status);
      if (terminal.has(run.status)) {
        finishLive();
        await loadMessages();
        await loadApprovals();
      } else {
        setTimeout(() => {
          if (state.runId === runId && !state.stream && !terminal.has(state.runStatus)) watch(runId);
        }, 1000);
      }
    } catch (error) {
      showNotice(`状态刷新失败：${error.message}`, true);
      return;
    }
    if (streamError && !terminal.has(state.runStatus)) {
      showNotice(`事件流断开，正在重连：${streamError.message}`, true);
    }
  }
}

async function loadApprovals() {
  $('approvals').replaceChildren();
  if (!state.runId) return;
  const values = (await api('/v1/approvals')).filter(item => item.runId === state.runId);
  for (const approval of values) {
    const card = element('div', 'approval');
    card.append(element('strong', '', '需要你的确认'), element('div', '', approval.reason));
    const actions = element('div', 'actions');
    const approve = element('button', 'primary', '允许');
    const deny = element('button', 'primary deny', '拒绝');
    approve.onclick = () => resolveApproval(approval.id, 'APPROVED');
    deny.onclick = () => resolveApproval(approval.id, 'DENIED');
    actions.append(approve, deny);
    card.append(actions);
    $('approvals').append(card);
  }
}

async function resolveApproval(id, decision) {
  try {
    await api(`/v1/approvals/${id}`, {
      method: 'POST', body: JSON.stringify({decision})
    });
    $('approvals').replaceChildren();
    showNotice(decision === 'APPROVED' ? '已允许，继续执行' : '已拒绝');
    if (decision === 'APPROVED') setStatus('QUEUED');
  } catch (error) {
    showNotice(error.message, true);
  }
}

function clearEvents() {
  state.reasoningActivity = null;
  state.reasoningChars = 0;
  $('events').replaceChildren(
    element('div', 'nothing', '工具调用、审批和运行状态会显示在这里。')
  );
}

function renderModelControls() {
  document.querySelectorAll('[data-thinking]').forEach(button => {
    button.classList.toggle('active', button.dataset.thinking === state.thinkingMode);
  });
  document.querySelectorAll('[data-effort]').forEach(button => {
    button.classList.toggle('active', button.dataset.effort === state.reasoningEffort);
    button.disabled = state.thinkingMode !== 'enabled';
  });
}

$('new').onclick = createSession;
$('newGroup').onclick = () => {
  $('groupName').value = '';
  $('groupDialog').showModal();
  $('groupName').focus();
};
$('cancelGroup').onclick = () => $('groupDialog').close();
$('saveGroup').onclick = createGroup;
$('groupName').onkeydown = event => {
  if (event.key === 'Enter') createGroup();
};
$('send').onclick = sendMessage;
$('attach').onclick = () => $('attachmentInput').click();
$('attachmentInput').onchange = () => uploadAttachments([...$('attachmentInput').files]);
$('stop').onclick = stopRun;
$('input').oninput = resizeInput;
$('input').onkeydown = event => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendMessage();
  }
};
$('clear').onclick = clearEvents;
document.querySelectorAll('[data-thinking]').forEach(button => button.onclick = () => {
  state.thinkingMode = button.dataset.thinking;
  localStorage.setItem('paicli_thinking_mode', state.thinkingMode);
  renderModelControls();
});
document.querySelectorAll('[data-effort]').forEach(button => button.onclick = () => {
  state.reasoningEffort = button.dataset.effort;
  localStorage.setItem('paicli_reasoning_effort', state.reasoningEffort);
  renderModelControls();
});
$('toggle').onclick = () => {
  state.detailOpen = !state.detailOpen;
  $('workspace').classList.toggle('hide-detail', !state.detailOpen);
};
$('menu').onclick = () => $('sidebar').classList.toggle('open');
$('settings').onclick = () => {
  $('key').value = sessionStorage.getItem('paicli_api_key') || '';
  $('dialog').showModal();
};
$('capabilities').onclick = openCapabilities;
$('closeCapabilities').onclick = () => $('capabilityDialog').close();
$('importSkill').onclick = importSkill;
$('uploadKnowledge').onclick = uploadKnowledge;
$('close').onclick = () => $('dialog').close();
$('save').onclick = () => {
  sessionStorage.setItem('paicli_api_key', $('key').value.trim());
  $('dialog').close();
  refreshSessions();
};
document.addEventListener('click', () => {
  document.querySelectorAll('.session-menu.open').forEach(menu => menu.classList.remove('open'));
});

$('workspace').classList.toggle('hide-detail', !state.detailOpen);
clearEvents();
renderModelControls();
renderEmpty();
refreshSessions();
