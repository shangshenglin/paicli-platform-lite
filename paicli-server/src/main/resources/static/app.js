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
  templates: [],
  modelProfiles: [],
  modelProfileId: localStorage.getItem('paicli_model_profile') || '',
  notifiedRunId: '',
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
  const retryable = Boolean(state.runId && terminal.has(value));
  $('retryRun').hidden = !retryable;
  $('branchRun').hidden = !retryable;
  if ((terminal.has(value) || value === 'WAITING_APPROVAL') && state.runId && state.notifiedRunId !== `${state.runId}:${value}`) {
    state.notifiedRunId = `${state.runId}:${value}`;
    if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
      new Notification(`PaiCLI · ${statusNames[value] || value}`, {body: $('chatTitle').textContent || state.runId});
    }
  }
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
  $('input').value = localStorage.getItem(`paicli_draft_${id}`) || '';
  resizeInput();
  await refreshComposerOptions();
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
        attachmentIds: state.pendingAttachments.map(item => item.id),
        modelProfileId: state.modelProfileId || null
      })
    });
    $('input').value = '';
    localStorage.removeItem(`paicli_draft_${state.sessionId}`);
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
    const [skills, documents, status, mcpServers, mcpTools] = await Promise.all([
      api(`/v1/skills?projectKey=${project}`),
      api(`/v1/knowledge/documents?projectKey=${project}`),
      api(`/v1/capabilities/status?projectKey=${project}`),
      api('/v1/mcp/configurations'),
      api('/v1/mcp/tools')
    ]);
    renderCapabilityStatus(status);
    $('skillList').replaceChildren(...skills.map(skill => {
      const item = workbenchItem(`${skill.enabled ? '●' : '○'} ${skill.name}`, `${skill.source} · ${skill.description || '无描述'} · ${skill.repository || '本地'}${skill.commit ? ` @ ${skill.commit.slice(0, 10)}` : ''} · ${skill.pinned ? '已固定版本' : '跟随更新'}`);
      actionButton(item, skill.enabled ? '停用' : '启用', () => setSkillState(skill, !skill.enabled, skill.pinned));
      actionButton(item, skill.pinned ? '取消固定' : '固定版本', () => setSkillState(skill, skill.enabled, !skill.pinned));
      if (skill.repository) actionButton(item, '检查更新', async () => {
        const global = skill.source === 'global';
        const status = await api(`/v1/skills/${encodeURIComponent(skill.name)}/updates?projectKey=${project}&global=${global}`);
        if (!status.updateAvailable) return showNotice(status.error || '当前已是最新版本');
        if (confirm(`发现新 Commit ${status.latestCommit.slice(0, 10)}，立即升级？`)) {
          await api(`/v1/skills/${encodeURIComponent(skill.name)}/upgrade?projectKey=${project}&global=${global}`, {method: 'POST', body: '{}'});
          await refreshCapabilities();
        }
      });
      if (skill.repository) actionButton(item, '回滚', async () => {
        const global = skill.source === 'global';
        await api(`/v1/skills/${encodeURIComponent(skill.name)}/rollback?projectKey=${project}&global=${global}`, {method: 'POST', body: '{}'});
        await refreshCapabilities();
      });
      actionButton(item, '文件清单', async () => alert((await api(`/v1/skills/${encodeURIComponent(skill.name)}/files?projectKey=${project}`)).join('\n')));
      actionButton(item, '删除', () => deleteSkill(skill.name, skill.source === 'global'));
      return item;
    }));
    if (!skills.length) $('skillList').append(element('div', 'hint', '尚未安装 Skill'));
    $('knowledgeList').replaceChildren(...documents.map(document => {
      const item = workbenchItem(document.name,
        `${document.collection} · v${document.version} · ${document.indexStatus} · ${document.indexedChunks} 块 · ${document.embeddingProvider || '待索引'}`);
      actionButton(item, '重建索引', () => reindexKnowledge(document.name));
      actionButton(item, '删除', () => deleteKnowledge(document.name));
      return item;
    }));
    if (!documents.length) $('knowledgeList').append(element('div', 'hint', '尚未上传知识文档'));
    $('mcpList').replaceChildren(...mcpServers.map(server => {
      const status = mcpTools.filter(tool => tool.server === server.name);
      const item = workbenchItem(`${server.enabled ? '●' : '○'} ${server.name}`, `${server.url} · ${status.length} 个工具 · Header ${Object.keys(server.headers || {}).join(', ') || '无'}`);
      actionButton(item, '测试', async () => { const result = await api(`/v1/mcp/servers/${server.name}/test`, {method: 'POST', body: '{}'}); showNotice(result.ready ? `${server.name} 连接正常` : `${server.name}：${result.error}`, !result.ready); await refreshCapabilities(); });
      actionButton(item, server.enabled ? '停用' : '启用', () => saveMcp({...server, enabled: !server.enabled}));
      actionButton(item, '删除', async () => { await api(`/v1/mcp/servers/${server.name}`, {method: 'DELETE'}); await refreshCapabilities(); }); return item;
    }));
    if (!mcpServers.length) $('mcpList').append(element('div', 'hint', '尚未配置 MCP Server'));
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

async function setSkillState(skill, enabled, pinned) {
  await api(`/v1/skills/${encodeURIComponent(skill.name)}/state?projectKey=${encodeURIComponent(currentProjectKey())}&global=${skill.source === 'global'}&enabled=${enabled}&pinned=${pinned}`, {method: 'POST', body: '{}'});
  await refreshCapabilities();
}

async function saveMcp(server) {
  await api(`/v1/mcp/servers/${encodeURIComponent(server.name)}`, {method: 'PUT', body: JSON.stringify(server)});
  await refreshCapabilities();
}

async function addMcp() {
  const name = prompt('MCP Server 名称（字母、数字、点、下划线或连字符）'); if (!name) return;
  const url = prompt('Streamable HTTP MCP 地址'); if (!url) return;
  const header = prompt('敏感 Header 名称（可空，例如 Authorization）', '') || '';
  const env = header ? prompt('环境变量引用（例如 env:MCP_TOKEN）', 'env:MCP_TOKEN') : '';
  await saveMcp({name, url, enabled: true, headers: header ? {[header]: env} : {}});
}

function workbenchItem(title, subtitle) {
  const item = element('div', 'managed-item');
  const main = element('div', 'managed-main');
  main.append(element('strong', '', title), element('small', 'snippet', subtitle || ''));
  item.append(main, element('div', 'managed-actions'));
  return item;
}

function actionButton(item, label, action, primary = false) {
  const button = element('button', primary ? 'primary' : 'secondary', label);
  button.onclick = action;
  item.querySelector('.managed-actions').append(button);
  return button;
}

async function reindexKnowledge(name) {
  try {
    await api(`/v1/knowledge/documents/${encodeURIComponent(currentProjectKey())}/${encodeURIComponent(name)}/reindex`, {method: 'POST'});
    showNotice(`已重建知识索引：${name}`);
    await refreshCapabilities();
  } catch (error) { showNotice(`索引重建失败：${error.message}`, true); }
}

async function importSkill() {
  const gitUrl = $('skillGitUrl').value.trim();
  if (!gitUrl) return showNotice('请输入 Skill Git 地址', true);
  const global = $('skillGlobal').checked;
  try {
    showNotice('正在预检 Skill 文件和权限声明…');
    const request = {projectKey: currentProjectKey(), gitUrl, name: $('skillName').value.trim() || null, global};
    const inspection = await api('/v1/skills/imports/inspect', {method: 'POST', body: JSON.stringify(request)});
    if (!confirm(`安装 Skill“${inspection.name}”？\n权限声明：${inspection.permissions}\n\n文件清单：\n${inspection.files.join('\n')}`)) return;
    showNotice('正在安装已确认的 Skill…');
    await api('/v1/skills/imports', {method: 'POST', body: JSON.stringify(request)});
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

async function retryRun(branch) {
  if (!state.runId || !terminal.has(state.runStatus)) return;
  try {
    const result = await api(`/v1/runs/${state.runId}/retry`, {
      method: 'POST', body: JSON.stringify({branch, modelProfileId: state.modelProfileId || null})
    });
    if (branch) {
      await refreshSessions();
      await selectSession(result.sessionId);
    } else {
      state.runId = result.run.id;
      $('runMeta').textContent = result.run.id;
      setStatus(result.run.status);
      await loadMessages();
      watch(result.run.id);
    }
    showNotice(branch ? '已创建分支并重新执行' : '已重新执行');
  } catch (error) { showNotice(`操作失败：${error.message}`, true); }
}

async function openWorkbench() {
  $('workbenchProject').textContent = `当前项目：${currentProjectKey()}`;
  $('workbenchDialog').showModal();
  await Promise.all([loadManagedMemories(), loadArtifacts(), loadApprovalPolicies(), loadP1Data()]);
}

async function refreshComposerOptions() {
  try {
    const project = encodeURIComponent(currentProjectKey());
    [state.templates, state.modelProfiles] = await Promise.all([
      api(`/v1/productivity/templates?projectKey=${project}`),
      api(`/v1/productivity/model-profiles?projectKey=${project}`)
    ]);
    const template = $('quickTemplate');
    template.replaceChildren(element('option', '', '快捷指令'));
    template.firstElementChild.value = '';
    state.templates.forEach(value => {
      const option = element('option', '', `${value.shortcut || '模板'} · ${value.name}`);
      option.value = value.id; template.append(option);
    });
    const profile = $('modelProfile');
    profile.replaceChildren(element('option', '', '默认模型'));
    profile.firstElementChild.value = '';
    state.modelProfiles.forEach(value => {
      const option = element('option', '', `${value.defaultProfile ? '★ ' : ''}${value.name}`);
      option.value = value.id; profile.append(option);
    });
    if (state.modelProfiles.some(value => value.id === state.modelProfileId)) profile.value = state.modelProfileId;
    else state.modelProfileId = '';
    scheduleEstimate();
  } catch (error) { showNotice(`P1 配置加载失败：${error.message}`, true); }
}

let estimateTimer = 0;
function scheduleEstimate() {
  clearTimeout(estimateTimer);
  estimateTimer = setTimeout(updateEstimate, 250);
}

async function updateEstimate() {
  if (!state.sessionId) return;
  try {
    const query = new URLSearchParams({sessionId: state.sessionId, inputChars: String($('input').value.length)});
    if (state.modelProfileId) query.set('modelProfileId', state.modelProfileId);
    const value = await api(`/v1/productivity/estimate?${query}`);
    const cost = value.estimatedMaxCost ? ` · 上限成本约 $${value.estimatedMaxCost.toFixed(4)}` : '';
    $('estimate').textContent = `预计上下文 ${value.estimatedContextTokens.toLocaleString()} / ${value.maxContextTokens ? value.maxContextTokens.toLocaleString() : '默认'} Token · 输出上限 ${value.maxOutputTokens || '默认'}${cost}${value.warning ? ` · ${value.warning}` : ''}`;
    $('estimate').classList.toggle('warning', Boolean(value.warning));
  } catch { $('estimate').textContent = '暂时无法估算上下文与成本'; }
}

async function applyTemplate(id) {
  if (!id) return;
  try {
    const value = await api(`/v1/productivity/templates/${encodeURIComponent(id)}/resolve?projectKey=${encodeURIComponent(currentProjectKey())}`, {method: 'POST', body: JSON.stringify({variables: {}})});
    $('input').value = value.prompt;
    if (value.modelProfileId) {
      state.modelProfileId = value.modelProfileId;
      $('modelProfile').value = value.modelProfileId;
    }
    localStorage.setItem(`paicli_draft_${state.sessionId}`, $('input').value);
    resizeInput(); scheduleEstimate(); $('input').focus();
  } catch (error) { showNotice(`模板解析失败：${error.message}`, true); }
}

async function loadP1Data() {
  const project = encodeURIComponent(currentProjectKey());
  try {
    const [usage, templates, profiles, queue, schedules, notifications] = await Promise.all([
      api(`/v1/productivity/usage?projectKey=${project}&days=30`),
      api(`/v1/productivity/templates?projectKey=${project}`),
      api(`/v1/productivity/model-profiles?projectKey=${project}`),
      api(`/v1/productivity/queue?projectKey=${project}`),
      api(`/v1/productivity/schedules?projectKey=${project}`),
      api(`/v1/productivity/notifications?projectKey=${project}`)
    ]);
    renderUsage(usage); renderTemplates(templates); renderProfiles(profiles); renderQueue(queue);
    renderSchedules(schedules, templates); renderNotifications(notifications);
    state.templates = templates; state.modelProfiles = profiles;
  } catch (error) { showNotice(`P1 工作台加载失败：${error.message}`, true); }
}

function renderUsage(value) {
  const budget = value.budget;
  const tokens = value.inputTokens + value.outputTokens;
  const labels = [
    ['调用', value.calls.toLocaleString()], ['Token', tokens.toLocaleString()],
    ['缓存命中', value.cachedTokens.toLocaleString()], ['平均响应', `${Math.round(value.averageDurationMs)} ms`],
    ['失败率', `${(value.failureRate * 100).toFixed(1)}%`], ['重试', value.retries.toLocaleString()],
    ['估算成本', `$${value.estimatedCost.toFixed(4)}`]
  ];
  const tokenRatio = budget.monthlyTokens ? tokens / budget.monthlyTokens : 0;
  const costRatio = budget.monthlyCost ? value.estimatedCost / budget.monthlyCost : 0;
  if (Math.max(tokenRatio, costRatio) >= budget.warnRatio) {
    labels.push(['预算提醒', `${Math.round(Math.max(tokenRatio, costRatio) * 100)}%`]);
    showNotice('项目月度模型预算已接近上限', true);
  }
  $('usageSummary').replaceChildren(...labels.map(([name, text]) => {
    const item = element('div', 'capability-status-item'); item.append(element('strong', '', name), element('small', '', text)); return item;
  }));
  const configure = element('button', 'secondary', '配置预算与并发');
  configure.onclick = () => configureBudget(budget);
  $('usageSummary').append(configure);
  (value.breakdown || []).slice(0, 8).forEach(row => {
    const item = element('div', 'capability-status-item');
    const tokens = row.inputTokens + row.outputTokens;
    item.append(element('strong', '', `${row.date} · ${row.model}`),
      element('small', '', `${row.sessionTitle} · ${row.calls} 次 · ${tokens.toLocaleString()} Token · ${row.localModel ? `${Math.round(row.averageDurationMs)} ms` : `$${row.estimatedCost.toFixed(4)}`}`));
    $('usageSummary').append(item);
  });
}

async function configureBudget(current) {
  const dailyTokens = prompt('每日 Token 预算（0 表示不限）', current.dailyTokens || 0); if (dailyTokens === null) return;
  const monthlyTokens = prompt('每月 Token 预算（0 表示不限）', current.monthlyTokens || 0); if (monthlyTokens === null) return;
  const dailyCost = prompt('每日成本预算 USD（0 表示不限）', current.dailyCost || 0); if (dailyCost === null) return;
  const monthlyCost = prompt('每月成本预算 USD（0 表示不限）', current.monthlyCost || 0); if (monthlyCost === null) return;
  const maxConcurrentRuns = prompt('项目最大并发 Run', current.maxConcurrentRuns || 4); if (maxConcurrentRuns === null) return;
  await api(`/v1/productivity/budget?projectKey=${encodeURIComponent(currentProjectKey())}`, {method: 'PUT', body: JSON.stringify({dailyTokens:+dailyTokens, monthlyTokens:+monthlyTokens, dailyCost:+dailyCost, monthlyCost:+monthlyCost, warnRatio:.8, maxConcurrentRuns:+maxConcurrentRuns})});
  await loadP1Data();
}

function renderTemplates(values) {
  $('templateList').replaceChildren(...values.map(value => {
    const item = workbenchItem(`${value.shortcut || '模板'} · ${value.name}`, `${value.prompt} · 使用 ${value.useCount} 次${value.attachmentRequirements ? ` · 附件：${value.attachmentRequirements}` : ''}`);
    actionButton(item, '使用', async () => { $('workbenchDialog').close(); await applyTemplate(value.id); }, true);
    actionButton(item, '删除', async () => { if (confirm(`删除模板“${value.name}”？`)) { await api(`/v1/productivity/templates/${value.id}`, {method: 'DELETE'}); await loadP1Data(); } });
    return item;
  }));
}

async function addTemplate() {
  const name = prompt('模板名称'); if (!name) return;
  const shortcut = prompt('快捷指令，例如 /review', `/${name.toLowerCase().replace(/\s+/g, '-')}`); if (shortcut === null) return;
  const promptText = prompt('Prompt（变量写成 ${repository}）'); if (!promptText) return;
  const variablesText = prompt('变量默认值 JSON', '{"repository":"当前仓库","outputFormat":"Markdown"}'); if (variablesText === null) return;
  const variables = JSON.parse(variablesText || '{}');
  await api('/v1/productivity/templates', {method: 'POST', body: JSON.stringify({projectKey: currentProjectKey(), name, shortcut, prompt: promptText, variables, attachmentRequirements: prompt('附件要求（可空）', '') || '', allowedTools: [], modelProfileId: state.modelProfileId || null})});
  await Promise.all([loadP1Data(), refreshComposerOptions()]);
}

function renderProfiles(values) {
  $('profileList').replaceChildren(...values.map(value => {
    const item = workbenchItem(`${value.defaultProfile ? '★ ' : ''}${value.name}`, `${value.localModel ? '本地模型' : '远程模型'} · ${value.model} · ${value.maxContextTokens.toLocaleString()} ctx · fallback ${value.fallbackModel || '无'}`);
    actionButton(item, '选用', () => { state.modelProfileId = value.id; localStorage.setItem('paicli_model_profile', value.id); $('modelProfile').value = value.id; scheduleEstimate(); });
    actionButton(item, '删除', async () => { if (confirm(`删除模型方案“${value.name}”？`)) { await api(`/v1/productivity/model-profiles/${value.id}`, {method: 'DELETE'}); await loadP1Data(); } });
    return item;
  }));
  if (!values.length) $('profileList').append(element('div', 'hint', '暂无项目级模型方案；继续使用服务端默认模型'));
}

async function addProfile() {
  const name = prompt('方案名称，例如 快速 / 深度 / 本地模型'); if (!name) return;
  const baseUrl = prompt('OpenAI-compatible Base URL', 'https://api.openai.com/v1'); if (!baseUrl) return;
  const model = prompt('模型名称'); if (!model) return;
  const apiKeyEnv = prompt('API Key 环境变量名（本地无密钥可空）', 'PAICLI_MODEL_API_KEY'); if (apiKeyEnv === null) return;
  const fallbackModel = prompt('Fallback 模型（可空）', '') || '';
  const localModel = confirm('这是本地模型吗？本地模型只统计耗时，不计算价格。');
  await api('/v1/productivity/model-profiles', {method: 'POST', body: JSON.stringify({projectKey: currentProjectKey(), name, baseUrl, apiKeyEnv, model, fallbackModel, maxContextTokens: +(prompt('上下文上限', '128000') || 128000), maxOutputTokens: +(prompt('输出上限', '4096') || 4096), inputPrice: localModel ? 0 : +(prompt('输入价格 USD / 1M Token', '0') || 0), outputPrice: localModel ? 0 : +(prompt('输出价格 USD / 1M Token', '0') || 0), localModel, makeDefault: confirm('设为项目默认模型方案？')})});
  await Promise.all([loadP1Data(), refreshComposerOptions()]);
}

function renderQueue(values) {
  $('queueList').replaceChildren(...values.map(value => {
    const run = value.run; const remaining = value.remainingBudgetTokens < 0 ? '预算不限' : `项目余量 ${value.remainingBudgetTokens.toLocaleString()} Token`;
    const item = workbenchItem(`${run.status} · ${value.sessionTitle}`, `${run.id} · step ${run.currentStep} · 优先级 ${run.priority} · ${Math.round(value.elapsedMs / 1000)}s · 已用 ${value.usedTokens.toLocaleString()} Token · ${remaining} · retry ${run.retryCount}`);
    if (run.status === 'QUEUED') { actionButton(item, '提高优先级', () => setRunPriority(run.id, run.priority + 1)); actionButton(item, '取消', () => batchQueue([run.id], 'CANCEL')); }
    if (run.status === 'FAILED' || run.status === 'CANCELED') actionButton(item, '重新排队', () => batchQueue([run.id], 'REQUEUE'), true);
    return item;
  }));
  if (!values.length) $('queueList').append(element('div', 'hint', '当前没有排队、运行、待审批或失败任务'));
}
async function setRunPriority(id, priority) { await api(`/v1/productivity/queue/${id}/priority`, {method: 'PATCH', body: JSON.stringify({priority})}); await loadP1Data(); }
async function batchQueue(runIds, action) { await api('/v1/productivity/queue/batch', {method: 'POST', body: JSON.stringify({runIds, action})}); await loadP1Data(); }

function renderSchedules(values, templates) {
  $('scheduleList').replaceChildren(...values.map(value => {
    const template = templates.find(item => item.id === value.templateId); const item = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`, `${value.scheduleType} ${value.scheduleValue || ''} · ${template?.name || value.templateId} · 下次 ${value.nextRunAt ? new Date(value.nextRunAt).toLocaleString() : '未安排'}`);
    actionButton(item, '删除', async () => { if (confirm(`删除定时任务“${value.name}”？`)) { await api(`/v1/productivity/schedules/${value.id}`, {method: 'DELETE'}); await loadP1Data(); } }); return item;
  }));
}
async function addSchedule() {
  if (!state.templates.length) return showNotice('请先创建任务模板', true);
  const name = prompt('定时任务名称'); if (!name) return;
  const templateId = prompt(`模板 ID：\n${state.templates.map(v => `${v.id} · ${v.name}`).join('\n')}`, state.templates[0].id); if (!templateId) return;
  const scheduleType = (prompt('类型：ONCE / DAILY / WEEKLY / CRON', 'DAILY') || '').toUpperCase(); if (!scheduleType) return;
  const scheduleValue = scheduleType === 'CRON' ? prompt('Spring Cron（6 段，例如 0 0 9 * * MON-FRI）', '0 0 9 * * *') : '';
  await api('/v1/productivity/schedules', {method: 'POST', body: JSON.stringify({projectKey: currentProjectKey(), name, templateId, scheduleType, scheduleValue, variables: {}, enabled: true, nextRunAt: new Date(Date.now() + 60000).toISOString()})}); await loadP1Data();
}

function renderNotifications(values) {
  $('notificationList').replaceChildren(...values.map(value => { const item = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`, `${value.type} · ${value.events} · 密钥环境变量 ${value.secretEnv || '无'}`); actionButton(item, '删除', async () => { await api(`/v1/productivity/notifications/${value.id}`, {method: 'DELETE'}); await loadP1Data(); }); return item; }));
}
async function addNotification() {
  const type = (prompt('通知类型：BROWSER / WEBHOOK / EMAIL / IM', 'BROWSER') || '').toUpperCase(); if (!type) return;
  if (type === 'BROWSER') { if ('Notification' in window) await Notification.requestPermission(); }
  const name = prompt('通知名称', type === 'BROWSER' ? '浏览器通知' : `${type} 通知`); if (!name) return;
  const endpoint = type === 'BROWSER' ? '' : prompt('Webhook 接收地址（邮件/企业 IM 使用对应网关）', ''); if (endpoint === null) return;
  const secretEnv = type === 'BROWSER' ? '' : prompt('服务端密钥环境变量名（可空）', '') || '';
  await api('/v1/productivity/notifications', {method: 'POST', body: JSON.stringify({projectKey: currentProjectKey(), name, type, endpoint, secretEnv, events: ['COMPLETED','FAILED','WAITING_APPROVAL','BUDGET_INSUFFICIENT'], enabled: true})}); await loadP1Data();
}

async function exportSession(format) {
  if (!state.sessionId) return showNotice('请先选择对话', true);
  const response = await fetch(`/v1/sessions/${state.sessionId}/export?format=${format}&redactSecrets=${$('redactExport').checked}`, {headers: headers(false)});
  if (!response.ok) throw new Error(await response.text()); const url = URL.createObjectURL(await response.blob()); const anchor = document.createElement('a'); anchor.href = url; anchor.download = `paicli-session.${format === 'markdown' ? 'md' : 'json'}`; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function importSession(file) {
  const payload = await file.text(); const session = await api('/v1/sessions/import', {method: 'POST', body: JSON.stringify({projectKey: currentProjectKey(), payload, redactSecrets: $('redactExport').checked})}); await refreshSessions(); await selectSession(session.id); $('workbenchDialog').close();
}

async function searchAll() {
  const query = $('globalSearch').value.trim();
  if (query.length < 2) return showNotice('搜索词至少 2 个字符', true);
  try {
    const values = await api(`/v1/search?projectKey=${encodeURIComponent(currentProjectKey())}&query=${encodeURIComponent(query)}&limit=50`);
    $('searchResults').replaceChildren(...values.map(result => {
      const item = workbenchItem(`${result.type} · ${result.title}`,
        `${result.citation ? `[${result.citation}] ` : ''}${result.snippet}`);
      if (result.sessionId) actionButton(item, '打开对话', async () => {
        $('workbenchDialog').close();
        await selectSession(result.sessionId);
      }, true);
      if (result.type === 'ARTIFACT') actionButton(item, '预览', () => previewArtifact(result.id));
      if (result.type === 'KNOWLEDGE') {
        actionButton(item, '有帮助', () => sendKnowledgeFeedback(result, true));
        actionButton(item, '无帮助', () => sendKnowledgeFeedback(result, false));
      }
      return item;
    }));
    if (!values.length) $('searchResults').append(element('div', 'hint', '没有匹配结果'));
  } catch (error) { showNotice(`搜索失败：${error.message}`, true); }
}

async function sendKnowledgeFeedback(result, helpful) {
  try {
    await api(`/v1/knowledge/documents/${encodeURIComponent(currentProjectKey())}/${encodeURIComponent(result.id)}/feedback?chunk=${result.chunk || 0}`, {
      method: 'POST', body: JSON.stringify({helpful, note: 'Console 全局检索反馈'})
    });
    showNotice(helpful ? '已记录为有帮助' : '已记录为无帮助');
  } catch (error) { showNotice(`反馈记录失败：${error.message}`, true); }
}

async function loadManagedMemories() {
  try {
    const values = await api(`/v1/memories/managed?projectKey=${encodeURIComponent(currentProjectKey())}&limit=200`);
    $('memoryList').replaceChildren(...values.map(memory => {
      const source = memory.origin === 'automatic' ? `自动 · 置信度 ${Math.round(memory.confidence * 100)}%` : '人工';
      const item = workbenchItem(`${memory.pinned ? '📌 ' : ''}${memory.memoryKey}`,
        `${source} · ${memory.layer}/${memory.memoryType} · ${memory.content}`);
      actionButton(item, memory.pinned ? '取消置顶' : '置顶', () => setMemoryState(memory.id, {pinned: !memory.pinned}));
      actionButton(item, '确认', () => setMemoryState(memory.id, {confirmed: true}));
      actionButton(item, memory.enabled ? '停用' : '启用', () => setMemoryState(memory.id, {enabled: !memory.enabled}));
      actionButton(item, '合并到…', () => mergeMemory(memory, values));
      actionButton(item, '修订', () => showMemoryRevisions(memory));
      return item;
    }));
    if (!values.length) $('memoryList').append(element('div', 'hint', '暂无启用的 Memory'));
  } catch (error) { showNotice(`Memory 加载失败：${error.message}`, true); }
}

async function setMemoryState(id, value) {
  try {
    await api(`/v1/memories/${id}/state`, {method: 'POST', body: JSON.stringify(value)});
    await loadManagedMemories();
  } catch (error) { showNotice(`Memory 更新失败：${error.message}`, true); }
}

async function mergeMemory(source, values) {
  const key = prompt('输入要合并到的目标 Memory Key：');
  if (!key) return;
  const target = values.find(value => value.memoryKey === key.trim());
  if (!target || target.id === source.id) return showNotice('未找到其他同名目标 Memory', true);
  try {
    await api(`/v1/memories/${target.id}/merge`, {
      method: 'POST', body: JSON.stringify({sourceIds: [source.id]})
    });
    showNotice(`已将 ${source.memoryKey} 合并到 ${target.memoryKey}`);
    await loadManagedMemories();
  } catch (error) { showNotice(`Memory 合并失败：${error.message}`, true); }
}

async function showMemoryRevisions(memory) {
  try {
    const revisions = await api(`/v1/memories/${memory.id}/revisions`);
    if (!revisions.length) return showNotice('该 Memory 暂无历史修订');
    const revision = revisions[0];
    if (confirm(`恢复最近一次修订（${new Date(revision.replacedAt).toLocaleString()}）？\n\n${revision.content}`)) {
      await api(`/v1/memories/${memory.id}/revisions/${revision.id}/restore`, {method: 'POST'});
      await loadManagedMemories();
    }
  } catch (error) { showNotice(`修订加载失败：${error.message}`, true); }
}

async function loadArtifacts() {
  try {
    const values = await api(`/v1/artifacts?projectKey=${encodeURIComponent(currentProjectKey())}&limit=100`);
    $('artifactList').replaceChildren(...values.map(artifact => {
      const item = workbenchItem(artifact.name, `${artifact.type} · ${Math.ceil(artifact.size / 1024)} KB · ${artifact.runId}`);
      actionButton(item, '预览', () => previewArtifact(artifact.id));
      actionButton(item, '下载', () => downloadArtifact(artifact.id));
      actionButton(item, '复用', () => reuseArtifact(artifact.id), true);
      actionButton(item, '删除', () => deleteArtifact(artifact.id));
      return item;
    }));
    if (!values.length) $('artifactList').append(element('div', 'hint', '暂无 Artifact'));
  } catch (error) { showNotice(`Artifact 加载失败：${error.message}`, true); }
}

async function previewArtifact(id) {
  try {
    const value = await api(`/v1/artifacts/${id}/content?limit=8000`);
    alert(value.content);
  } catch (error) { showNotice(`预览失败：${error.message}`, true); }
}

async function downloadArtifact(id) {
  try {
    const response = await fetch(`/v1/artifacts/${id}/download`, {headers: headers(false)});
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement('a');
    anchor.href = url;
    const disposition = response.headers.get('Content-Disposition') || '';
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
    anchor.download = encoded ? decodeURIComponent(encoded) : `artifact-${id}.txt`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) { showNotice(`下载失败：${error.message}`, true); }
}

async function reuseArtifact(id) {
  if (!state.sessionId) return showNotice('请先选择或创建一个对话', true);
  try {
    const attachment = await api(`/v1/artifacts/${id}/reuse`, {
      method: 'POST', body: JSON.stringify({sessionId: state.sessionId})
    });
    state.pendingAttachments.push({...attachment, kind: 'document', preview: null});
    renderPendingAttachments();
    $('workbenchDialog').close();
    showNotice(`Artifact 已作为附件复用：${attachment.name}`);
  } catch (error) { showNotice(`复用失败：${error.message}`, true); }
}

async function deleteArtifact(id) {
  if (!confirm('删除这个 Artifact？')) return;
  try { await api(`/v1/artifacts/${id}`, {method: 'DELETE'}); await loadArtifacts(); }
  catch (error) { showNotice(`删除失败：${error.message}`, true); }
}

async function loadApprovalPolicies() {
  try {
    const values = await api(`/v1/approvals/policies?projectKey=${encodeURIComponent(currentProjectKey())}`);
    $('policyList').replaceChildren(...values.map(policy => {
      const item = workbenchItem(`${policy.scope} · ${policy.toolName}`, `参数指纹 ${policy.argumentsSha256.slice(0, 16)}…`);
      actionButton(item, '撤销', async () => {
        await api(`/v1/approvals/policies/${policy.id}`, {method: 'DELETE'});
        await loadApprovalPolicies();
      });
      return item;
    }));
    if (!values.length) $('policyList').append(element('div', 'hint', '暂无持久化审批策略'));
  } catch (error) { showNotice(`审批策略加载失败：${error.message}`, true); }
}

async function loadApprovals() {
  $('approvals').replaceChildren();
  if (!state.runId) return;
  const values = (await api('/v1/approvals')).filter(item => item.runId === state.runId);
  for (const approval of values) {
    const card = element('div', 'approval');
    card.append(element('strong', '', '需要你的确认'), element('div', '', approval.reason));
    const actions = element('div', 'actions');
    const approve = element('button', 'primary', '仅本次允许');
    const approveSession = element('button', 'secondary', '本对话允许');
    const approveProject = element('button', 'secondary', '本项目允许');
    const deny = element('button', 'primary deny', '拒绝');
    approve.onclick = () => resolveApproval(approval.id, 'APPROVED');
    approveSession.onclick = () => resolveApproval(approval.id, 'APPROVED', 'SESSION');
    approveProject.onclick = () => resolveApproval(approval.id, 'APPROVED', 'PROJECT');
    deny.onclick = () => resolveApproval(approval.id, 'DENIED');
    actions.append(approve, approveSession, approveProject, deny);
    card.append(actions);
    $('approvals').append(card);
  }
}

async function resolveApproval(id, decision, rememberScope = null) {
  try {
    await api(`/v1/approvals/${id}`, {
      method: 'POST', body: JSON.stringify({decision, rememberScope})
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
$('input').oninput = () => {
  resizeInput(); scheduleEstimate();
  if (state.sessionId) localStorage.setItem(`paicli_draft_${state.sessionId}`, $('input').value);
};
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
$('retryRun').onclick = () => retryRun(false);
$('branchRun').onclick = () => retryRun(true);
$('menu').onclick = () => $('sidebar').classList.toggle('open');
$('settings').onclick = () => {
  $('key').value = sessionStorage.getItem('paicli_api_key') || '';
  $('dialog').showModal();
};
$('capabilities').onclick = openCapabilities;
$('workbench').onclick = openWorkbench;
$('closeWorkbench').onclick = () => $('workbenchDialog').close();
$('searchAll').onclick = searchAll;
$('globalSearch').onkeydown = event => { if (event.key === 'Enter') searchAll(); };
$('refreshMemories').onclick = loadManagedMemories;
$('refreshArtifacts').onclick = loadArtifacts;
$('refreshPolicies').onclick = loadApprovalPolicies;
$('refreshP1').onclick = loadP1Data;
$('refreshQueue').onclick = loadP1Data;
$('addTemplate').onclick = () => addTemplate().catch(error => showNotice(error.message, true));
$('addProfile').onclick = () => addProfile().catch(error => showNotice(error.message, true));
$('addSchedule').onclick = () => addSchedule().catch(error => showNotice(error.message, true));
$('addNotification').onclick = () => addNotification().catch(error => showNotice(error.message, true));
$('quickTemplate').onchange = () => applyTemplate($('quickTemplate').value);
$('modelProfile').onchange = () => {
  state.modelProfileId = $('modelProfile').value;
  localStorage.setItem('paicli_model_profile', state.modelProfileId);
  scheduleEstimate();
};
document.querySelectorAll('[data-export]').forEach(button => button.onclick = () => exportSession(button.dataset.export).catch(error => showNotice(`导出失败：${error.message}`, true)));
$('sessionImport').onchange = () => { const file = $('sessionImport').files[0]; if (file) importSession(file).catch(error => showNotice(`导入失败：${error.message}`, true)); };
$('closeCapabilities').onclick = () => $('capabilityDialog').close();
$('importSkill').onclick = importSkill;
$('addMcp').onclick = () => addMcp().catch(error => showNotice(`MCP 配置失败：${error.message}`, true));
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
document.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); $('input').focus(); }
  if (event.altKey && event.key.toLowerCase() === 'n') { event.preventDefault(); createSession(); }
});

$('workspace').classList.toggle('hide-detail', !state.detailOpen);
clearEvents();
renderModelControls();
renderEmpty();
refreshSessions();
