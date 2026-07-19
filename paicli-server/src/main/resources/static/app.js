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
  agentProfiles: [],
  availableSkills: [],
  editingAgentProfileId: '',
  homeMode: localStorage.getItem('paicli_home_mode') || 'chat',
  modelProfileId: localStorage.getItem('paicli_model_profile') || '',
  agentProfileId: localStorage.getItem('paicli_agent_profile') || '',
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
const agentToolOptions = [
  ['list_agent_profiles', '列出专家 Profile'],
  ['spawn_agent', '创建子智能体任务'],
  ['list_agents', '查看子任务状态'],
  ['get_agent_result', '读取子任务结果'],
  ['cancel_agent', '取消子任务'],
  ['list_dir', '列目录'],
  ['read_file', '读文件'],
  ['write_file', '写文件'],
  ['execute_command', '执行命令'],
  ['read_artifact', '读 Artifact'],
  ['search_knowledge', '查项目知识库'],
  ['session_search', '查历史会话'],
  ['web_search', '网页搜索'],
  ['web_fetch', '读取网页']
];
const agentRoleHelp = {
  LEADER: 'Leader：协作入口会优先选择；通常允许 list_agent_profiles / spawn_agent 来拆分和汇总任务。',
  EXPERT: 'Expert：领域执行者；按专家提示和工具白名单完成被分派的子任务。',
  REVIEWER: 'Reviewer：审查者；建议搭配只读工具和 READ_ONLY 审批策略，用于风险、回归和缺失测试检查。',
  RUNNER: 'Runner：验证执行者；通常允许 execute_command，用于运行测试、构建或检查命令。'
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
  if (!response.ok) {
    const body = await response.text();
    if (response.status === 401) openConnectionSettings('认证失败，请填写与服务端 PAICLI_API_KEY 一致的密钥。');
    const error = new Error(response.status === 401 ? 'API Key 无效或未填写' : `${response.status} ${body}`);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

function openConnectionSettings(message = '请输入 PAICLI_API_KEY；不要填写 DeepSeek 的 PAICLI_MODEL_API_KEY。') {
  $('key').value = sessionStorage.getItem('paicli_api_key') || '';
  $('connectionHint').textContent = message;
  if (!$('dialog').open) $('dialog').showModal();
  requestAnimationFrame(() => { $('key').focus(); $('key').select(); });
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

function updateComposerVisibility() {
  const hidden = !state.sessionId && state.homeMode === 'collaboration';
  $('compose').hidden = hidden;
}

function renderEmpty() {
  updateComposerVisibility();
  const empty = element('div', 'empty');
  const content = element('div');
  const switcher = element('div', 'home-mode-switch');
  const chatMode = element('button', state.homeMode === 'chat' ? 'primary' : 'secondary', '普通对话');
  const collaborationMode = element('button', state.homeMode === 'collaboration' ? 'primary' : 'secondary', '专家协作');
  chatMode.onclick = () => setHomeMode('chat');
  collaborationMode.onclick = () => setHomeMode('collaboration');
  switcher.append(chatMode, collaborationMode);
  content.append(switcher);
  if (state.homeMode === 'collaboration') content.append(renderHomeCollaboration());
  else {
    const actions = element('div', 'home-actions');
    const productivity = element('button', 'secondary home-action');
    productivity.append(element('strong', '', '效率工作台'), element('small', '', '用量、模板、队列与自动化'));
    productivity.onclick = openWorkbench;
    const evaluations = element('button', 'primary home-action');
    evaluations.append(element('strong', '', 'Agent 评测中心'), element('small', '', '套件、运行报告与质量基线'));
    evaluations.onclick = openEvaluationCenter;
    actions.append(productivity, evaluations);
    content.append(
      element('div', 'logo', 'π'),
      element('h1', '', '今天想完成什么？'),
      element('p', '', '直接描述目标。工具调用、推理和审批会收纳在执行详情中。'),
      actions
    );
  }
  empty.append(content);
  $('stack').replaceChildren(empty);
}

function setHomeMode(mode) {
  state.homeMode = mode;
  localStorage.setItem('paicli_home_mode', mode);
  renderEmpty();
}

function renderHomeCollaboration() {
  const panel = element('div', 'home-collaboration');
  const head = element('div', 'home-collaboration-head');
  const copy = element('div');
  copy.append(
    element('h1', '', '多智能体协作'),
    element('p', '', '输入一句话目标，由 Leader 查看专家目录、分派子任务并汇总结果。')
  );
  head.append(element('div', 'logo', 'π'), copy);
  const leaderField = element('label', 'form-field full');
  leaderField.append(element('span', '', 'Leader'));
  const leader = element('select');
  leader.id = 'homeCollaborationLeader';
  leader.append(new Option('自动选择 Leader', ''));
  homeLeaders().forEach(value => leader.append(new Option(`${value.collaborationRole || 'EXPERT'} · ${value.name}`, value.id)));
  leaderField.append(leader);
  const objectiveField = element('label', 'form-field full');
  objectiveField.append(element('span', '', '一句话目标'));
  const objective = element('textarea');
  objective.id = 'homeCollaborationObjective';
  objective.rows = 5;
  objective.placeholder = '例如：评估并实现专家协作模块的最小可用版本，最后给出验证结果';
  objectiveField.append(objective);
  const policy = element('div', 'collaboration-policy');
  const complexityField = element('label', 'form-field');
  complexityField.append(element('span', '', '任务复杂度'));
  const complexity = element('select');
  complexity.id = 'homeCollaborationComplexity';
  [['MEDIUM', '中等'], ['SIMPLE', '简单'], ['COMPLEX', '复杂']].forEach(([id, label]) => complexity.append(new Option(label, id)));
  complexityField.append(complexity);
  const riskField = element('label', 'form-field');
  riskField.append(element('span', '', '风险等级'));
  const risk = element('select');
  risk.id = 'homeCollaborationRisk';
  [['MEDIUM', '中等'], ['LOW', '低'], ['HIGH', '高']].forEach(([id, label]) => risk.append(new Option(label, id)));
  riskField.append(risk);
  const maxField = element('label', 'form-field');
  maxField.append(element('span', '', '最多专家'));
  const maxExperts = element('input');
  maxExperts.id = 'homeCollaborationMaxExperts';
  maxExperts.type = 'number';
  maxExperts.min = '0';
  maxExperts.max = '6';
  maxExperts.value = '3';
  maxField.append(maxExperts);
  policy.append(complexityField, riskField, maxField);
  const checks = element('div', 'checkbox-row collaboration-options');
  checks.append(
    checkboxControl('homeCollaborationRequireReviewer', '高风险/代码任务必须审查', true),
    checkboxControl('homeCollaborationRequireRunner', '需要验证时必须测试', true),
    checkboxControl('homeCollaborationAllowExpertDelegation', '允许子专家继续分派', false)
  );
  const allowed = renderCollaborationAgentChoices();
  const error = element('div', 'form-error');
  error.id = 'homeCollaborationError';
  error.setAttribute('role', 'alert');
  const actions = element('div', 'dialog-actions');
  const manage = element('button', 'secondary', '管理专家');
  manage.onclick = openAgentStudio;
  const start = element('button', 'primary', '启动协作');
  start.id = 'homeStartCollaboration';
  start.onclick = startCollaboration;
  actions.append(manage, start);
  panel.append(head, leaderField, objectiveField, policy, checks, allowed, error, actions);
  return panel;
}

function checkboxControl(id, label, checked = false) {
  const outer = element('label');
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.id = id;
  input.checked = checked;
  outer.append(input, document.createTextNode(` ${label}`));
  return outer;
}

function renderCollaborationAgentChoices() {
  const field = element('fieldset', 'agent-choice-field');
  const legend = element('legend', '', '本次可用专家');
  const hint = element('small', '', '不勾选表示允许全部启用专家；Leader 自身不会因此自动被派发。');
  const grid = element('div', 'agent-choice-grid');
  state.agentProfiles.filter(value => value.enabled && (value.collaborationRole || '').toUpperCase() !== 'LEADER')
    .forEach(value => {
      const label = element('label', 'agent-choice');
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.value = value.id;
      input.dataset.collaborationAgent = 'true';
      label.append(input, element('strong', '', value.name), element('small', '', `${value.collaborationRole || 'EXPERT'} · ${value.description || '未填写说明'}`));
      grid.append(label);
    });
  if (!grid.children.length) grid.append(element('div', 'hint', '暂无可用子专家，请先到“专家创建”补齐模板'));
  field.append(legend, hint, grid);
  return field;
}

function homeLeaders() {
  const enabled = state.agentProfiles.filter(value => value.enabled);
  const leaders = enabled.filter(value => (value.collaborationRole || '').toUpperCase() === 'LEADER');
  const experts = enabled.filter(value => (value.collaborationRole || '').toUpperCase() !== 'LEADER');
  return leaders.concat(experts);
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
  await renderSessionPlanPanel();
  await renderCollaborationBoard();
  messages.forEach(renderMessage);
  if (!$('stack').children.length) renderEmpty();
  scrollBottom();
}

async function renderSessionPlanPanel() {
  try {
    const values = await api(`/v1/sessions/${state.sessionId}/plans?limit=3`);
    if (!values.length) return;
    const panel = element('section', 'session-plan-panel');
    const title = element('div', 'section-title');
    title.append(
      element('h3', '', '关联 Plan'),
      element('span', '', `${values.length} 个计划`)
    );
    const list = element('div', 'session-plan-list');
    values.forEach(view => list.append(renderSessionPlanItem(view)));
    panel.append(title, list);
    $('stack').append(panel);
  } catch (error) {
    const panel = element('section', 'session-plan-panel');
    panel.append(element('div', 'form-error', `Plan 面板加载失败：${error.message}`));
    $('stack').append(panel);
  }
}

function renderSessionPlanItem(view) {
  const plan = view.plan;
  const steps = view.steps || [];
  const counts = steps.reduce((acc, step) => {
    acc[step.status] = (acc[step.status] || 0) + 1;
    return acc;
  }, {});
  const completed = (counts.COMPLETED || 0) + (counts.SKIPPED || 0);
  const running = steps.find(step => ['RUNNING', 'READY'].includes(step.status));
  const failed = steps.find(step => step.status === 'FAILED');
  const current = failed || running || steps.find(step => !['COMPLETED', 'SKIPPED', 'CANCELED'].includes(step.status)) || steps[steps.length - 1];
  const item = workbenchItem(
    `${plan.status} · ${plan.objective}`,
    `v${plan.version} · ${completed}/${steps.length} 步 · ${current ? `${current.status}：${current.title}` : '暂无步骤'}`
  );
  item.classList.add('session-plan-item');
  if (plan.status === 'ACTIVE') actionButton(item, '调度', () => dispatchPlan(plan.id), true);
  actionButton(item, '详情', () => inspectPlan(plan.id));
  actionButton(item, '打开工作台', async () => { await openWorkbench(); });
  return item;
}

async function renderCollaborationBoard() {
  if (!state.runId) return;
  try {
    const board = await api(`/v1/runs/${state.runId}/collaboration`);
    if (!board.enabled && !board.tasks.length) return;
    const panel = element('section', 'collaboration-board');
    const policy = board.policy || {};
    const title = element('div', 'section-title');
    title.append(
      element('h3', '', '协作任务看板'),
      element('span', '', `${policy.complexity || 'MEDIUM'} · ${policy.risk || 'MEDIUM'} · ${board.tasks.length}/${policy.maxExperts || 0} 专家`)
    );
    const list = element('div', 'collaboration-task-list');
    board.tasks.forEach(task => list.append(renderCollaborationTask(task)));
    if (!board.tasks.length) list.append(element('div', 'hint', 'Leader 尚未分派子专家；等待它调用 list_agent_profiles / spawn_agent。'));
    panel.append(title, list);
    $('stack').append(panel);
  } catch (error) {
    const panel = element('section', 'collaboration-board');
    panel.append(element('div', 'form-error', `协作看板加载失败：${error.message}`));
    $('stack').append(panel);
  }
}

function renderCollaborationTask(task) {
  const item = element('article', 'collaboration-task');
  const head = element('div', 'collaboration-task-head');
  const profile = task.profile || {};
  head.append(
    element('strong', '', task.agentName || profile.name || '子专家'),
    element('span', `status ${terminal.has(task.status) ? '' : 'active'}`, task.status || 'UNKNOWN')
  );
  item.append(head);
  item.append(element('div', 'hint', `${profile.role || 'EXPERT'} · ${task.childRunId}`));
  item.append(element('p', '', task.task || ''));
  if (task.error) item.append(element('div', 'form-error', task.error));
  if (task.result) {
    const details = element('details', 'collaboration-result');
    details.append(element('summary', '', '查看专家结果'), element('pre', '', task.result));
    item.append(details);
  }
  return item;
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
  updateComposerVisibility();
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

function showHome() {
  state.stream?.abort();
  state.sessionId = '';
  state.runId = '';
  state.runStatus = '';
  localStorage.removeItem('paicli_session');
  $('chatTitle').textContent = '新对话';
  $('runMeta').textContent = '尚未开始任务';
  $('input').value = '';
  resizeInput();
  setStatus();
  clearEvents();
  renderSessions();
  renderEmpty();
  $('sidebar').classList.remove('open');
  if (!$('compose').hidden) $('input').focus();
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
        modelProfileId: state.modelProfileId || null,
        agentProfileId: state.agentProfileId || null
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
      method: 'POST', body: JSON.stringify({
        branch,
        modelProfileId: state.modelProfileId || null,
        agentProfileId: state.agentProfileId || null
      })
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
  const creationButtons = ['addTemplate', 'addProfile', 'addSchedule', 'addNotification'].map($);
  creationButtons.forEach(button => { button.disabled = true; });
  $('workbenchDialog').showModal();
  try { await Promise.all([loadManagedMemories(), loadArtifacts(), loadPlans(), loadApprovalPolicies(), loadProductivityData()]); }
  finally { creationButtons.forEach(button => { button.disabled = false; }); }
}

let evaluationSuites = [];
let currentEvaluationExecution = '';

async function openEvaluationCenter() {
  $('evaluationProject').textContent = `当前项目：${currentProjectKey()}`;
  const creationButtons = ['installEvaluationStarterPack', 'addEvaluationSuite'].map($);
  creationButtons.forEach(button => { button.disabled = true; });
  $('evaluationDialog').showModal();
  try { await loadEvaluations(); }
  finally { creationButtons.forEach(button => { button.disabled = false; }); }
}

async function loadEvaluations() {
  const project = encodeURIComponent(currentProjectKey());
  try {
    evaluationSuites = await api(`/v1/evaluations/suites?projectKey=${project}`);
    const groups = await Promise.all(evaluationSuites.map(async suite => ({
      suite,
      cases: await api(`/v1/evaluations/suites/${suite.id}/cases`),
      executions: await api(`/v1/evaluations/suites/${suite.id}/executions?limit=5`)
    })));
    const enabledCases = groups.reduce((total, group) => total + group.cases.filter(value => value.enabled).length, 0);
    $('evaluationSuiteCount').textContent = `${groups.length} 个套件 · ${enabledCases} 个启用用例`;
    const nodes = [];
    groups.forEach(({suite, cases, executions}) => {
      const block = element('div', 'evaluation-suite-block');
      const latest = executions[0];
      const summary = `${cases.filter(value => value.enabled).length}/${cases.length} 个启用用例 · 默认 ${suite.defaultTrials} Trial · ${suite.passThreshold} 分通过${latest ? ` · 最近 ${latest.status}${latest.averageScore == null ? '' : ` ${latest.averageScore.toFixed(1)} 分`}` : ''}`;
      const item = workbenchItem(suite.name, `${suite.description || '未填写说明'} · ${summary}`);
      actionButton(item, '新建用例', () => openEvaluationCaseDialog(suite), true);
      actionButton(item, '运行', () => startEvaluation(suite));
      if (latest) actionButton(item, '查看报告', () => loadEvaluationReport(latest.id));
      actionButton(item, '删除', async () => {
        if (!confirm(`删除评测套件“${suite.name}”？`)) return;
        try { await api(`/v1/evaluations/suites/${suite.id}`, {method: 'DELETE'}); await loadEvaluations(); }
        catch (error) { showNotice(`套件删除失败：${error.message}`, true); }
      });
      block.append(item);
      if (cases.length) {
        const caseList = element('details', 'evaluation-case-list');
        caseList.append(element('summary', '', `查看 ${cases.length} 个用例`));
        cases.forEach(value => {
          const ruleCount = value.requiredTools.length + value.forbiddenTools.length + value.requiredResponse.length + value.forbiddenResponse.length;
          const child = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`,
            `${value.prompt} · ${ruleCount} 条内容规则${value.maxToolCalls ? ` · ≤${value.maxToolCalls} 工具` : ''}${value.maxTokens ? ` · ≤${value.maxTokens} 输出 Token` : ''}`);
          child.classList.add('evaluation-case-item');
          actionButton(child, value.enabled ? '停用' : '启用', () => setEvaluationCaseEnabled(value, !value.enabled));
          actionButton(child, '删除', async () => {
            if (!confirm(`删除评测用例“${value.name}”？`)) return;
            try { await api(`/v1/evaluations/cases/${value.id}`, {method: 'DELETE'}); await loadEvaluations(); }
            catch (error) { showNotice(`用例删除失败：${error.message}`, true); }
          });
          caseList.append(child);
        });
        block.append(caseList);
      }
      nodes.push(block);
    });
    $('evaluationSuiteList').replaceChildren(...nodes);
    if (!nodes.length) $('evaluationSuiteList').append(element('div', 'hint', '尚未创建评测套件。先建立一组关键任务和确定性规则。'));
    if (currentEvaluationExecution) await loadEvaluationReport(currentEvaluationExecution, false);
  } catch (error) { showNotice(`评测中心加载失败：${error.message}`, true); }
}

async function installEvaluationStarterPack() {
  if (!confirm('安装官方入门评测集？已存在的同名套件和用例会保留，不会覆盖你的修改。')) return;
  const button = $('installEvaluationStarterPack'); button.disabled = true;
  try {
    const result = await api(`/v1/evaluations/starter-pack?projectKey=${encodeURIComponent(currentProjectKey())}`,
      {method: 'POST', body: '{}'});
    await loadEvaluations();
    showNotice(`官方评测集 ${result.version}：新增 ${result.installedSuites} 个套件、${result.installedCases} 个用例，跳过 ${result.skippedCases} 个已有用例`);
  } catch (error) { showNotice(`官方评测集安装失败：${error.message}`, true); }
  finally { button.disabled = false; }
}

async function setEvaluationCaseEnabled(value, enabled) {
  try {
    await api(`/v1/evaluations/cases/${value.id}`, {method: 'PUT', body: JSON.stringify({...value, enabled})});
    await loadEvaluations(); showNotice(`评测用例已${enabled ? '启用' : '停用'}`);
  } catch (error) { showNotice(`评测用例状态更新失败：${error.message}`, true); }
}

function openEvaluationSuiteDialog() {
  $('evaluationSuiteForm').reset();
  $('evaluationSuiteTrials').value = '1'; $('evaluationSuiteThreshold').value = '80';
  setFormError('evaluationSuiteError'); $('evaluationSuiteDialog').showModal(); $('evaluationSuiteName').focus();
}

async function submitEvaluationSuite(event) {
  event.preventDefault(); setFormError('evaluationSuiteError'); $('saveEvaluationSuite').disabled = true;
  try {
    await api('/v1/evaluations/suites', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), name: $('evaluationSuiteName').value.trim(),
      description: $('evaluationSuiteDescription').value.trim(),
      defaultTrials: +$('evaluationSuiteTrials').value, passThreshold: +$('evaluationSuiteThreshold').value
    })});
    $('evaluationSuiteDialog').close(); await loadEvaluations(); showNotice('评测套件已创建');
  } catch (error) { setFormError('evaluationSuiteError', error.message); }
  finally { $('saveEvaluationSuite').disabled = false; }
}

function openEvaluationCaseDialog(suite) {
  $('evaluationCaseForm').reset(); $('evaluationCaseForm').dataset.suiteId = suite.id;
  $('evaluationCaseSuite').textContent = `所属套件：${suite.name}`; $('evaluationCaseEnabled').checked = true;
  ['evaluationMaxTools', 'evaluationMaxTokens', 'evaluationMaxDuration'].forEach(id => { $(id).value = '0'; });
  setFormError('evaluationCaseError'); $('evaluationCaseDialog').showModal(); $('evaluationCaseName').focus();
}

function evaluationRuleList(id) {
  return $(id).value.split(/[\n,，]+/).map(value => value.trim()).filter(Boolean);
}

async function submitEvaluationCase(event) {
  event.preventDefault(); setFormError('evaluationCaseError'); $('saveEvaluationCase').disabled = true;
  try {
    const suiteId = $('evaluationCaseForm').dataset.suiteId;
    await api(`/v1/evaluations/suites/${suiteId}/cases`, {method: 'POST', body: JSON.stringify({
      name: $('evaluationCaseName').value.trim(), prompt: $('evaluationCasePrompt').value.trim(),
      requiredTools: evaluationRuleList('evaluationRequiredTools'), forbiddenTools: evaluationRuleList('evaluationForbiddenTools'),
      requiredResponse: evaluationRuleList('evaluationRequiredResponse'), forbiddenResponse: evaluationRuleList('evaluationForbiddenResponse'),
      maxToolCalls: +$('evaluationMaxTools').value, maxTokens: +$('evaluationMaxTokens').value,
      maxDurationMs: +$('evaluationMaxDuration').value, enabled: $('evaluationCaseEnabled').checked
    })});
    $('evaluationCaseDialog').close(); await loadEvaluations(); showNotice('评测用例已创建');
  } catch (error) { setFormError('evaluationCaseError', error.message); }
  finally { $('saveEvaluationCase').disabled = false; }
}

async function startEvaluation(suite) {
  if (!confirm(`运行“${suite.name}”？每个启用用例将执行 ${suite.defaultTrials} 次。`)) return;
  try {
    const body = state.modelProfileId ? {modelProfileId: state.modelProfileId} : {};
    const execution = await api(`/v1/evaluations/suites/${suite.id}/executions`, {method: 'POST', body: JSON.stringify(body)});
    currentEvaluationExecution = execution.id; showNotice('评测已排队，将复用内部 Run 执行');
    await Promise.all([loadEvaluations(), loadEvaluationReport(execution.id)]);
  } catch (error) { showNotice(`评测启动失败：${error.message}`, true); }
}

async function loadEvaluationReport(executionId, notify = true) {
  try {
    currentEvaluationExecution = executionId;
    $('evaluationReportState').textContent = '正在加载报告…';
    const report = await api(`/v1/evaluations/executions/${executionId}`);
    const execution = report.execution;
    $('evaluationReportState').textContent = `${execution.status} · ${execution.trialCount} Trial/用例`;
    const header = element('div', 'evaluation-report-header');
    const score = execution.averageScore == null ? '执行中' : `${execution.averageScore.toFixed(1)} 分`;
    header.append(element('strong', '', `${report.suite.name} · ${score}`),
      element('small', '', `${execution.status} · ${execution.trialCount} Trial/用例 · ${execution.passThreshold} 分通过`));
    const refresh = element('button', 'secondary', '刷新报告'); refresh.onclick = () => loadEvaluationReport(executionId); header.append(refresh);
    const nodes = [header];
    report.trials.forEach(value => {
      const trial = value.trial; const details = value.details || {};
      const status = trial.score == null ? '运行中' : `${trial.score} 分 · ${trial.passed ? '通过' : '未通过'}`;
      const item = element('div', `evaluation-trial ${trial.score == null ? '' : trial.passed ? 'pass' : 'fail'}`);
      item.append(element('strong', '', `${value.caseName} · Trial ${trial.ordinal} · ${status}`));
      if (details.toolCalls != null) item.append(element('div', 'evaluation-checks',
        `${details.toolCalls} 次工具调用 · ${details.outputTokens ?? details.tokens ?? 0} 输出 Token · ${details.totalTokens ?? details.tokens ?? 0} 总 Token · ${details.durationMs || 0} ms · Run ${trial.runId}`));
      const failures = (details.checks || []).filter(check => !check.passed);
      if (failures.length) item.append(element('div', 'evaluation-checks', failures.map(check => `${check.rule}：${check.evidence}（-${check.deduction}）`).join('\n')));
      (details.approvals || []).filter(approval => approval.status === 'PENDING').forEach(approval => {
        const approvalText = element('div', 'evaluation-checks', `等待审批：${approval.reason}`);
        const allow = element('button', 'primary', '仅本次允许');
        const deny = element('button', 'secondary', '拒绝');
        allow.onclick = () => resolveEvaluationApproval(approval.id, 'APPROVED', executionId);
        deny.onclick = () => resolveEvaluationApproval(approval.id, 'DENIED', executionId);
        approvalText.append(allow, deny); item.append(approvalText);
      });
      if (details.runStatus === 'COMPLETED' && trial.passed) {
        const baseline = element('button', 'secondary', value.hasBaseline ? '更新基线' : '设为基线');
        baseline.onclick = async () => {
          await api(`/v1/evaluations/trials/${trial.id}/baseline`, {method: 'POST'});
          showNotice('人工确认基线已保存'); await loadEvaluationReport(executionId, false);
        };
        item.append(baseline);
      }
      nodes.push(item);
    });
    $('evaluationReport').replaceChildren(...nodes);
    if (notify) showNotice(execution.status === 'RUNNING' ? '评测仍在执行，可稍后刷新报告' : `评测${execution.passed ? '通过' : '未通过'}`,
      execution.status !== 'RUNNING' && !execution.passed);
  } catch (error) {
    $('evaluationReportState').textContent = '报告加载失败';
    showNotice(`评测报告加载失败：${error.message}`, true);
  }
}

async function resolveEvaluationApproval(id, decision, executionId) {
  try {
    await api(`/v1/approvals/${id}`, {method: 'POST', body: JSON.stringify({decision, rememberScope: null})});
    showNotice(decision === 'APPROVED' ? '评测工具已允许，将继续执行' : '评测工具已拒绝');
    await loadEvaluationReport(executionId, false);
  } catch (error) { showNotice(`评测审批失败：${error.message}`, true); }
}

async function refreshComposerOptions() {
  try {
    const project = encodeURIComponent(currentProjectKey());
    [state.templates, state.modelProfiles, state.agentProfiles] = await Promise.all([
      api(`/v1/productivity/templates?projectKey=${project}`),
      api(`/v1/productivity/model-profiles?projectKey=${project}`),
      api(`/v1/productivity/agent-profiles?projectKey=${project}`)
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
    const agent = $('agentProfile');
    agent.replaceChildren(element('option', '', '默认专家'));
    agent.firstElementChild.value = '';
    state.agentProfiles.filter(value => value.enabled).forEach(value => {
      const option = element('option', '', `${value.collaborationRole || 'EXPERT'} · ${value.name}`);
      option.value = value.id; agent.append(option);
    });
    if (state.agentProfiles.some(value => value.id === state.agentProfileId && value.enabled)) agent.value = state.agentProfileId;
    else state.agentProfileId = '';
    scheduleEstimate();
    if (!state.sessionId && state.homeMode === 'collaboration') renderEmpty();
  } catch (error) { showNotice(`效率配置加载失败：${error.message}`, true); }
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

async function loadProductivityData() {
  const project = encodeURIComponent(currentProjectKey());
  try {
    const [usage, templates, profiles, agents, skills, queue, schedules, notifications] = await Promise.all([
      api(`/v1/productivity/usage?projectKey=${project}&days=30`),
      api(`/v1/productivity/templates?projectKey=${project}`),
      api(`/v1/productivity/model-profiles?projectKey=${project}`),
      api(`/v1/productivity/agent-profiles?projectKey=${project}`),
      api(`/v1/skills?projectKey=${project}`),
      api(`/v1/productivity/queue?projectKey=${project}`),
      api(`/v1/productivity/schedules?projectKey=${project}`),
      api(`/v1/productivity/notifications?projectKey=${project}`)
    ]);
    state.templates = templates; state.modelProfiles = profiles; state.agentProfiles = agents; state.availableSkills = skills;
    renderUsage(usage); renderTemplates(templates); renderAgentStudio(agents); renderProfiles(profiles); renderQueue(queue);
    renderSchedules(schedules, templates); renderNotifications(notifications);
  } catch (error) { showNotice(`效率工作台加载失败：${error.message}`, true); }
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
  $('configureUsageBudget').onclick = () => configureBudget(budget);
  const breakdown = (value.breakdown || []).slice(0, 12);
  $('usageHistorySummary').textContent = breakdown.length ? `最近 ${breakdown.length} 条 · 点击展开` : '暂无记录';
  const rows = breakdown.map(row => {
    const item = element('div', 'usage-row');
    const rowTokens = row.inputTokens + row.outputTokens;
    item.append(element('strong', '', row.date), element('span', '', `${row.model} · ${row.sessionTitle}`),
      element('small', '', `${row.calls} 次 · ${rowTokens.toLocaleString()} Token · ${row.localModel ? `${Math.round(row.averageDurationMs)} ms` : `$${row.estimatedCost.toFixed(4)}`}`));
    return item;
  });
  $('usageBreakdown').replaceChildren(...rows);
  if (!rows.length) $('usageBreakdown').append(element('div', 'evaluation-empty', '近 30 天暂无模型调用记录。'));
}

async function configureBudget(current) {
  const dailyTokens = prompt('每日 Token 预算（0 表示不限）', current.dailyTokens || 0); if (dailyTokens === null) return;
  const monthlyTokens = prompt('每月 Token 预算（0 表示不限）', current.monthlyTokens || 0); if (monthlyTokens === null) return;
  const dailyCost = prompt('每日成本预算 USD（0 表示不限）', current.dailyCost || 0); if (dailyCost === null) return;
  const monthlyCost = prompt('每月成本预算 USD（0 表示不限）', current.monthlyCost || 0); if (monthlyCost === null) return;
  const maxConcurrentRuns = prompt('项目最大并发 Run', current.maxConcurrentRuns || 4); if (maxConcurrentRuns === null) return;
  await api(`/v1/productivity/budget?projectKey=${encodeURIComponent(currentProjectKey())}`, {method: 'PUT', body: JSON.stringify({dailyTokens:+dailyTokens, monthlyTokens:+monthlyTokens, dailyCost:+dailyCost, monthlyCost:+monthlyCost, warnRatio:.8, maxConcurrentRuns:+maxConcurrentRuns})});
  await loadProductivityData();
}

function renderTemplates(values) {
  $('templateList').replaceChildren(...values.map(value => {
    const item = workbenchItem(`${value.shortcut || '模板'} · ${value.name}`, `${value.prompt} · 使用 ${value.useCount} 次${value.attachmentRequirements ? ` · 附件：${value.attachmentRequirements}` : ''}`);
    actionButton(item, '使用', async () => { $('workbenchDialog').close(); await applyTemplate(value.id); }, true);
    actionButton(item, '删除', async () => { if (confirm(`删除模板“${value.name}”？`)) { await api(`/v1/productivity/templates/${value.id}`, {method: 'DELETE'}); await loadProductivityData(); } });
    return item;
  }));
}

function renderAgentStudio(values = state.agentProfiles) {
  const list = $('agentStudioList');
  if (!list) return;
  const enabled = values.filter(value => value.enabled);
  const leaders = enabled.filter(value => (value.collaborationRole || '').toUpperCase() === 'LEADER');
  const experts = enabled.filter(value => (value.collaborationRole || '').toUpperCase() !== 'LEADER');
  $('agentStudioSummary').replaceChildren(
    agentStudioMetric('专家总数', values.length),
    agentStudioMetric('可用专家', enabled.length),
    agentStudioMetric('Leader', leaders.length)
  );
  list.replaceChildren(...values.map(value => {
    const tools = parseStringListJson(value.toolNamesJson);
    const model = state.modelProfiles.find(item => item.id === value.modelProfileId);
    const subtitle = [
      value.description || '未填写说明',
      `角色 ${value.collaborationRole || 'EXPERT'}`,
      value.templateKey ? `模板 ${value.templateKey}@v${value.templateVersion || 0}` : '自定义专家',
      model ? `模型 ${model.name}` : '项目默认模型',
      tools.length ? `工具 ${tools.slice(0, 8).join(', ')}` : '工具不限制'
    ].join(' · ');
    const selected = state.agentProfileId === value.id;
    const item = workbenchItem(`${selected ? '当前 · ' : ''}${value.enabled ? '●' : '○'} ${value.name}`, subtitle);
    if (selected) item.classList.add('selected');
    if (value.enabled) actionButton(item, selected ? '已用于对话' : '用于对话', () => selectAgentForChat(value), true);
    actionButton(item, '编辑', () => openAgentProfileDialog(value));
    actionButton(item, '复制', () => copyAgentProfile(value));
    if (value.templateKey) actionButton(item, '恢复默认', () => restoreAgentTemplate(value));
    return item;
  }));
  if (!values.length) list.append(element('div', 'hint', '暂无专家模板；点击“补齐专家模板”安装 Leader、需求、实现、测试、审查和文档专家。'));
}

function selectAgentForChat(value) {
  state.agentProfileId = value.id;
  localStorage.setItem('paicli_agent_profile', value.id);
  $('agentProfile').value = value.id;
  renderAgentStudio(state.agentProfiles);
  showNotice(`后续对话将使用专家：${value.name}`);
}

async function copyAgentProfile(value) {
  const name = prompt('复制为新专家名称', `${value.name} 副本`);
  if (name === null) return;
  try {
    await api(`/v1/productivity/agent-profiles/${value.id}/copy`, {
      method: 'POST',
      body: JSON.stringify({projectKey: currentProjectKey(), name: name.trim() || `${value.name} 副本`})
    });
    await Promise.all([loadProductivityData(), refreshComposerOptions()]);
    showNotice('专家已复制');
  } catch (error) { showNotice(`复制专家失败：${error.message}`, true); }
}

async function restoreAgentTemplate(value) {
  if (!confirm(`恢复“${value.name}”到内置模板版本？当前修改会被覆盖。`)) return;
  try {
    await api(`/v1/productivity/agent-profiles/${value.id}/restore-template`, {method: 'POST', body: '{}'});
    await Promise.all([loadProductivityData(), refreshComposerOptions()]);
    showNotice('专家模板已恢复');
  } catch (error) { showNotice(`恢复模板失败：${error.message}`, true); }
}

function agentStudioMetric(name, value) {
  const item = element('div', 'capability-status-item');
  item.append(element('strong', '', name), element('small', '', String(value)));
  return item;
}

async function refreshAgentStudio() {
  const project = encodeURIComponent(currentProjectKey());
  const [profiles, agents, skills] = await Promise.all([
    api(`/v1/productivity/model-profiles?projectKey=${project}`),
    api(`/v1/productivity/agent-profiles?projectKey=${project}`),
    api(`/v1/skills?projectKey=${project}`)
  ]);
  state.modelProfiles = profiles;
  state.agentProfiles = agents;
  state.availableSkills = skills;
  renderAgentStudio(agents);
}

async function openAgentStudio() {
  $('agentStudioProject').textContent = `当前项目：${currentProjectKey()}`;
  $('agentStudioDialog').showModal();
  try { await refreshAgentStudio(); }
  catch (error) { showNotice(`专家创建加载失败：${error.message}`, true); }
}

async function installAgentStarterPack() {
  try {
    const project = encodeURIComponent(currentProjectKey());
    state.agentProfiles = await api(`/v1/productivity/agent-profiles/starter-pack?projectKey=${project}`, {method: 'POST', body: '{}'});
    renderAgentStudio(state.agentProfiles);
    await refreshComposerOptions();
    showNotice('专家模板已补齐');
  } catch (error) { showNotice(`专家模板安装失败：${error.message}`, true); }
}

async function startCollaboration() {
  const ids = {
    leader: 'homeCollaborationLeader',
    objective: 'homeCollaborationObjective',
    error: 'homeCollaborationError',
    start: 'homeStartCollaboration'
  };
  setFormError(ids.error);
  const objective = $(ids.objective).value.trim();
  if (!objective) return setFormError(ids.error, '请先填写一句话目标');
  const enabled = state.agentProfiles.filter(value => value.enabled);
  const selected = $(ids.leader).value;
  const leader = enabled.find(value => value.id === selected)
      || enabled.find(value => (value.collaborationRole || '').toUpperCase() === 'LEADER');
  if (!leader) return setFormError(ids.error, '没有可用 Leader，请先到“专家创建”补齐或创建专家模板');
  const allowedAgentProfileIds = [...document.querySelectorAll('[data-collaboration-agent]:checked')]
      .map(input => input.value).filter(Boolean);
  const complexity = $('homeCollaborationComplexity').value;
  const risk = $('homeCollaborationRisk').value;
  const maxExperts = Math.max(0, Math.min(Number($('homeCollaborationMaxExperts').value || 3), 6));
  const title = `协作：${objective.slice(0, 42)}`;
  const prompt = [
    `协作目标：${objective}`,
    `任务复杂度：${complexity}；风险等级：${risk}；最多可派发专家数：${maxExperts}。`,
    allowedAgentProfileIds.length ? `本次只允许从这些 agent_profile_id 中选择：${allowedAgentProfileIds.join(', ')}` : '本次允许从全部启用专家中选择。',
    '',
    '请作为 Leader 执行：',
    '1. 调用 list_agent_profiles 查看本次策略允许的专家。',
    '2. 先判断是否确实需要分派；简单任务可以不分派或只派 1 个专家。',
    '3. 使用 list_agents / get_agent_result 跟踪子任务，必要时等待或补充派发。',
    '4. 派发数量不得超过策略上限；高风险或代码任务优先包含 Reviewer，需验证时优先包含 Runner。',
    '5. 汇总所有专家结果，交付完整结论、风险和验证建议。'
  ].join('\n');
  try {
    $(ids.start).disabled = true;
    const session = await api('/v1/sessions', {
      method: 'POST',
      body: JSON.stringify({title, projectKey: currentProjectKey(), groupId: null})
    });
    await api(`/v1/sessions/${session.id}/runs`, {
      method: 'POST',
      body: JSON.stringify({
        input: prompt,
        thinkingMode: state.thinkingMode,
        reasoningEffort: state.thinkingMode === 'enabled' ? state.reasoningEffort : '',
        modelProfileId: state.modelProfileId || null,
        agentProfileId: leader.id,
        collaboration: {
          enabled: true,
          complexity,
          risk,
          allowedAgentProfileIds,
          maxExperts,
          maxDepth: $('homeCollaborationAllowExpertDelegation').checked ? 2 : 1,
          maxChildRuns: Math.max(maxExperts, 1),
          allowExpertDelegation: $('homeCollaborationAllowExpertDelegation').checked,
          requireReviewer: $('homeCollaborationRequireReviewer').checked,
          requireRunner: $('homeCollaborationRequireRunner').checked
        }
      })
    });
    $(ids.objective).value = '';
    if ($('agentStudioDialog').open) $('agentStudioDialog').close();
    await refreshSessions();
    await selectSession(session.id);
    showNotice(`已启动协作：${leader.name}`);
  } catch (error) { setFormError(ids.error, error.message); }
  finally { $(ids.start).disabled = false; }
}

function setFormError(id, message = '') { $(id).textContent = message; }

function fillSelect(select, values, emptyLabel = '') {
  const options = emptyLabel ? [new Option(emptyLabel, '')] : [];
  options.push(...values.map(value => new Option(value.label, value.id)));
  select.replaceChildren(...options);
}

function fillMultiSelect(select, values, selected = []) {
  const chosen = new Set(selected);
  select.replaceChildren(...values.map(value => {
    const option = new Option(value.label, value.id);
    option.selected = chosen.has(value.id);
    return option;
  }));
}

function selectedValues(select) {
  return [...select.selectedOptions].map(option => option.value).filter(Boolean);
}

function fillTagPicker(select, picker, values, selected = []) {
  fillMultiSelect(select, values, selected);
  const render = (filter = '') => {
    const chosen = new Set(selectedValues(select));
    const chips = element('div', 'tag-chips');
    chosen.forEach(id => {
      const meta = values.find(value => value.id === id) || {id, label: id};
      const chip = element('button', 'tag-chip', meta.id);
      chip.type = 'button';
      chip.title = `${meta.label} · 点击移除`;
      chip.onclick = () => {
        [...select.options].forEach(option => { if (option.value === id) option.selected = false; });
        render(filter);
      };
      chips.append(chip);
    });
    const input = element('input', 'tag-search');
    input.placeholder = '搜索并添加';
    input.value = filter;
    input.oninput = () => render(input.value);
    const options = element('div', 'tag-options');
    const normalized = filter.trim().toLowerCase();
    values.filter(value => !chosen.has(value.id))
      .filter(value => !normalized || `${value.id} ${value.label}`.toLowerCase().includes(normalized))
      .slice(0, 12).forEach(value => {
        const button = element('button', 'tag-option', value.label);
        button.type = 'button';
        button.onclick = () => {
          [...select.options].forEach(option => { if (option.value === value.id) option.selected = true; });
          render('');
        };
        options.append(button);
      });
    if (!options.children.length) options.append(element('div', 'hint', '没有匹配项'));
    picker.replaceChildren(chips, input, options);
  };
  render();
}

function defaultToolsForRole(role) {
  if (role === 'LEADER') return ['list_agent_profiles','spawn_agent','list_agents','get_agent_result','cancel_agent','read_file','list_dir','search_knowledge'];
  if (role === 'REVIEWER') return ['list_dir','read_file','search_knowledge','session_search'];
  if (role === 'RUNNER') return ['list_dir','read_file','execute_command','search_knowledge'];
  return ['list_dir','read_file','write_file','search_knowledge'];
}

function updateAgentRoleHelp() {
  $('agentRoleHelp').textContent = agentRoleHelp[$('agentRole').value] || '';
  if (!state.editingAgentProfileId && $('agentToolsPicker')) {
    fillTagPicker($('agentTools'), $('agentToolsPicker'), agentToolOptions.map(([id, label]) => ({id, label: `${id} · ${label}`})),
        defaultToolsForRole($('agentRole').value));
    if ($('agentApproval').value === 'INHERIT' || $('agentApproval').value === 'READ_ONLY') {
      $('agentApproval').value = $('agentRole').value === 'REVIEWER' ? 'READ_ONLY' : 'INHERIT';
    }
  }
}

function parseStringMap(text, label) {
  let value;
  try { value = JSON.parse(text.trim() || '{}'); }
  catch { throw new Error(`${label}不是有效的 JSON`); }
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label}必须是 JSON 对象`);
  if (Object.values(value).some(item => typeof item !== 'string')) throw new Error(`${label}的值必须全部是字符串`);
  return value;
}

function parseCsvList(text) {
  return text.split(/[\n,]/).map(value => value.trim()).filter(Boolean);
}

function parseStringListJson(text) {
  try {
    const value = JSON.parse(text || '[]');
    return Array.isArray(value) ? value.filter(item => typeof item === 'string' && item.trim()).map(item => item.trim()) : [];
  } catch { return []; }
}

function openTemplateDialog() {
  $('templateForm').reset();
  $('templateVariables').value = '{"repository":"当前仓库","outputFormat":"Markdown"}';
  fillSelect($('templateProfile'), state.modelProfiles.map(value => ({id: value.id, label: `${value.name} · ${value.model}`})), '使用项目默认模型');
  if (state.modelProfiles.some(value => value.id === state.modelProfileId)) $('templateProfile').value = state.modelProfileId;
  setFormError('templateFormError');
  $('templateDialog').showModal();
  $('templateName').focus();
}

async function submitTemplate(event) {
  event.preventDefault();
  setFormError('templateFormError');
  const button = $('saveTemplate'); button.disabled = true;
  try {
    const shortcut = $('templateShortcut').value.trim();
    if (shortcut && !/^\/[a-zA-Z0-9_-]+$/.test(shortcut)) throw new Error('快捷指令需以 / 开头，且只能包含字母、数字、下划线和连字符');
    const variables = parseStringMap($('templateVariables').value, '变量默认值');
    const allowedTools = $('templateTools').value.split(',').map(value => value.trim()).filter(Boolean);
    await api('/v1/productivity/templates', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), name: $('templateName').value.trim(), shortcut,
      prompt: $('templatePrompt').value.trim(), variables,
      attachmentRequirements: $('templateAttachments').value.trim(), allowedTools,
      modelProfileId: $('templateProfile').value || null
    })});
    $('templateDialog').close();
    await Promise.all([loadProductivityData(), refreshComposerOptions()]);
    showNotice('任务模板已创建');
  } catch (error) { setFormError('templateFormError', error.message); }
  finally { button.disabled = false; }
}

function openAgentProfileDialog(profile = null) {
  const editing = !!profile;
  state.editingAgentProfileId = editing ? profile.id : '';
  $('agentProfileForm').reset();
  $('agentProfileDialogTitle').textContent = editing ? '编辑智能体专家' : '新建智能体专家';
  $('saveAgentProfile').textContent = editing ? '保存专家' : '创建专家';
  $('agentName').value = editing ? profile.name : '';
  $('agentDescription').value = editing ? profile.description || '' : '';
  $('agentPrompt').value = editing ? profile.systemPrompt || '' : '';
  $('agentOutputSchema').value = editing ? profile.outputSchema || '' : '';
  $('agentEnabled').checked = editing ? profile.enabled : true;
  $('agentRole').value = editing ? profile.collaborationRole || 'EXPERT' : 'EXPERT';
  $('agentHandoff').value = editing ? profile.handoffPolicy || 'MANUAL' : 'MANUAL';
  $('agentScope').value = editing ? profile.workspaceScope || 'PROJECT' : 'PROJECT';
  $('agentApproval').value = editing ? profile.approvalPolicy || 'INHERIT' : 'INHERIT';
  fillSelect($('agentModelProfile'), state.modelProfiles.map(value => ({id: value.id, label: `${value.name} · ${value.model}`})), '使用项目默认模型');
  if (editing) $('agentModelProfile').value = profile.modelProfileId || '';
  else if (state.modelProfiles.some(value => value.id === state.modelProfileId)) $('agentModelProfile').value = state.modelProfileId;
  fillTagPicker($('agentTools'), $('agentToolsPicker'), agentToolOptions.map(([id, label]) => ({id, label: `${id} · ${label}`})),
      editing ? parseStringListJson(profile.toolNamesJson) : defaultToolsForRole($('agentRole').value));
  fillTagPicker($('agentSkills'), $('agentSkillsPicker'), state.availableSkills.map(value => ({id: value.name, label: `${value.name} · ${value.description || value.source || ''}`})),
      editing ? parseStringListJson(profile.skillNamesJson) : []);
  updateAgentRoleHelp();
  setFormError('agentProfileFormError');
  $('agentProfileDialog').showModal();
  $('agentName').focus();
}

async function submitAgentProfile(event) {
  event.preventDefault();
  setFormError('agentProfileFormError');
  const button = $('saveAgentProfile'); button.disabled = true;
  try {
    const id = state.editingAgentProfileId;
    await api(id ? `/v1/productivity/agent-profiles/${id}` : '/v1/productivity/agent-profiles', {method: id ? 'PUT' : 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(),
      name: $('agentName').value.trim(),
      description: $('agentDescription').value.trim(),
      systemPrompt: $('agentPrompt').value.trim(),
      modelProfileId: $('agentModelProfile').value || null,
      toolNames: selectedValues($('agentTools')),
      skillNames: selectedValues($('agentSkills')),
      outputSchema: $('agentOutputSchema').value.trim(),
      collaborationRole: $('agentRole').value,
      handoffPolicy: $('agentHandoff').value,
      workspaceScope: $('agentScope').value,
      approvalPolicy: $('agentApproval').value,
      enabled: $('agentEnabled').checked
    })});
    $('agentProfileDialog').close();
    state.editingAgentProfileId = '';
    await Promise.all([loadProductivityData(), refreshComposerOptions()]);
    showNotice(id ? '智能体专家已保存' : '智能体专家已创建');
  } catch (error) { setFormError('agentProfileFormError', error.message); }
  finally { button.disabled = false; }
}

function renderProfiles(values) {
  $('profileList').replaceChildren(...values.map(value => {
    const item = workbenchItem(`${value.defaultProfile ? '★ ' : ''}${value.name}`, `${value.localModel ? '本地模型' : '远程模型'} · ${value.model} · ${value.maxContextTokens.toLocaleString()} ctx · fallback ${value.fallbackModel || '无'}`);
    actionButton(item, '选用', () => { state.modelProfileId = value.id; localStorage.setItem('paicli_model_profile', value.id); $('modelProfile').value = value.id; scheduleEstimate(); });
    actionButton(item, '删除', async () => { if (confirm(`删除模型方案“${value.name}”？`)) { await api(`/v1/productivity/model-profiles/${value.id}`, {method: 'DELETE'}); await loadProductivityData(); } });
    return item;
  }));
  if (!values.length) $('profileList').append(element('div', 'hint', '暂无项目级模型方案；继续使用服务端默认模型'));
}

function openProfileDialog() {
  $('profileForm').reset();
  setFormError('profileFormError');
  updateProfilePriceFields();
  $('profileDialog').showModal();
  $('profileName').focus();
}

function updateProfilePriceFields() {
  const local = $('profileLocal').checked;
  for (const id of ['profileInputPrice', 'profileOutputPrice']) {
    $(id).disabled = local;
    if (local) $(id).value = '0';
  }
}

async function submitProfile(event) {
  event.preventDefault();
  setFormError('profileFormError');
  const button = $('saveProfile'); button.disabled = true;
  try {
    const localModel = $('profileLocal').checked;
    await api('/v1/productivity/model-profiles', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), name: $('profileName').value.trim(),
      baseUrl: $('profileBaseUrl').value.trim(), apiKeyEnv: $('profileApiKeyEnv').value.trim(),
      model: $('profileModel').value.trim(), fallbackModel: $('profileFallback').value.trim(),
      maxContextTokens: Number($('profileContext').value), maxOutputTokens: Number($('profileOutput').value),
      inputPrice: localModel ? 0 : Number($('profileInputPrice').value || 0),
      outputPrice: localModel ? 0 : Number($('profileOutputPrice').value || 0),
      localModel, makeDefault: $('profileDefault').checked
    })});
    $('profileDialog').close();
    await Promise.all([loadProductivityData(), refreshComposerOptions()]);
    showNotice('模型配置方案已创建');
  } catch (error) { setFormError('profileFormError', error.message); }
  finally { button.disabled = false; }
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
async function setRunPriority(id, priority) { await api(`/v1/productivity/queue/${id}/priority`, {method: 'PATCH', body: JSON.stringify({priority})}); await loadProductivityData(); }
async function batchQueue(runIds, action) { await api('/v1/productivity/queue/batch', {method: 'POST', body: JSON.stringify({runIds, action})}); await loadProductivityData(); }

function renderSchedules(values, templates) {
  $('scheduleList').replaceChildren(...values.map(value => {
    const template = templates.find(item => item.id === value.templateId); const item = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`, `${value.scheduleType} ${value.scheduleValue || ''} · ${template?.name || value.templateId} · 下次 ${value.nextRunAt ? new Date(value.nextRunAt).toLocaleString() : '未安排'}`);
    actionButton(item, '删除', async () => { if (confirm(`删除定时任务“${value.name}”？`)) { await api(`/v1/productivity/schedules/${value.id}`, {method: 'DELETE'}); await loadProductivityData(); } }); return item;
  }));
}
function localDateTimeValue(date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function nextDaily(time) {
  const [hours, minutes] = time.split(':').map(Number); const value = new Date();
  value.setSeconds(0, 0); value.setHours(hours, minutes, 0, 0);
  if (value <= new Date()) value.setDate(value.getDate() + 1);
  return value;
}

function nextWeekly(weekday, time) {
  const value = nextDaily(time); const target = Number(weekday);
  let days = (target - value.getDay() + 7) % 7;
  value.setDate(value.getDate() + days);
  return value;
}

function selectedTemplateVariables() {
  const template = state.templates.find(value => value.id === $('scheduleTemplate').value);
  return template?.variablesJson || '{}';
}

function updateScheduleFields() {
  const type = $('scheduleType').value;
  $('scheduleOnceFields').hidden = type !== 'ONCE';
  $('scheduleDailyFields').hidden = type !== 'DAILY';
  $('scheduleWeeklyFields').hidden = type !== 'WEEKLY';
  $('scheduleCronFields').hidden = type !== 'CRON';
}

function openScheduleDialog() {
  if (!state.templates.length) return showNotice('请先创建任务模板，再新建定时任务', true);
  $('scheduleForm').reset();
  fillSelect($('scheduleTemplate'), state.templates.map(value => ({id: value.id, label: `${value.shortcut || '模板'} · ${value.name}`})));
  $('scheduleOnceAt').value = localDateTimeValue(new Date(Date.now() + 3600000));
  $('scheduleVariables').value = selectedTemplateVariables();
  setFormError('scheduleFormError');
  updateScheduleFields();
  $('scheduleDialog').showModal();
  $('scheduleName').focus();
}

async function submitSchedule(event) {
  event.preventDefault();
  setFormError('scheduleFormError');
  const button = $('saveSchedule'); button.disabled = true;
  try {
    const type = $('scheduleType').value;
    let nextRunAt = null; let scheduleValue = '';
    if (type === 'ONCE') {
      const date = new Date($('scheduleOnceAt').value);
      if (Number.isNaN(date.getTime()) || date <= new Date()) throw new Error('一次性任务的执行时间必须晚于当前时间');
      nextRunAt = date.toISOString();
    } else if (type === 'DAILY') {
      if (!$('scheduleDailyTime').value) throw new Error('请选择每天执行时间');
      scheduleValue = $('scheduleDailyTime').value; nextRunAt = nextDaily(scheduleValue).toISOString();
    } else if (type === 'WEEKLY') {
      const time = $('scheduleWeeklyTime').value; if (!time) throw new Error('请选择每周执行时间');
      const weekday = $('scheduleWeekday').value;
      scheduleValue = `${['SUN','MON','TUE','WED','THU','FRI','SAT'][Number(weekday)]} ${time}`;
      nextRunAt = nextWeekly(weekday, time).toISOString();
    } else {
      scheduleValue = $('scheduleCron').value.trim();
      if (!scheduleValue) throw new Error('请输入 Spring 六段 Cron 表达式');
    }
    const variables = parseStringMap($('scheduleVariables').value, '模板变量');
    await api('/v1/productivity/schedules', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), name: $('scheduleName').value.trim(),
      templateId: $('scheduleTemplate').value, scheduleType: type, scheduleValue,
      variables, enabled: $('scheduleEnabled').checked, nextRunAt
    })});
    $('scheduleDialog').close();
    await loadProductivityData();
    showNotice('定时任务已创建');
  } catch (error) { setFormError('scheduleFormError', error.message); }
  finally { button.disabled = false; }
}

function renderNotifications(values) {
  $('notificationList').replaceChildren(...values.map(value => { const item = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`, `${value.type} · ${value.events} · 密钥环境变量 ${value.secretEnv || '无'}`); actionButton(item, '删除', async () => { await api(`/v1/productivity/notifications/${value.id}`, {method: 'DELETE'}); await loadProductivityData(); }); return item; }));
}
const notificationNames = {BROWSER: '浏览器通知', WEBHOOK: 'Webhook 通知', EMAIL: '邮件通知', IM: '企业 IM 通知'};

function updateNotificationFields(resetName = false) {
  const browser = $('notificationType').value === 'BROWSER';
  $('notificationEndpointFields').hidden = browser;
  $('notificationSecretFields').hidden = browser;
  $('notificationEndpoint').required = !browser;
  if (resetName) $('notificationName').value = notificationNames[$('notificationType').value];
}

function openNotificationDialog() {
  $('notificationForm').reset();
  setFormError('notificationFormError');
  updateNotificationFields(true);
  $('notificationDialog').showModal();
  $('notificationName').focus();
  $('notificationName').select();
}

async function submitNotification(event) {
  event.preventDefault();
  setFormError('notificationFormError');
  const button = $('saveNotification'); button.disabled = true;
  try {
    const type = $('notificationType').value;
    const events = [...document.querySelectorAll('[name="notificationEvent"]:checked')].map(value => value.value);
    if (!events.length) throw new Error('请至少选择一个通知事件');
    if (type === 'BROWSER' && 'Notification' in window) {
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') throw new Error('浏览器通知权限未授予');
    }
    await api('/v1/productivity/notifications', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), name: $('notificationName').value.trim(), type,
      endpoint: type === 'BROWSER' ? '' : $('notificationEndpoint').value.trim(),
      secretEnv: type === 'BROWSER' ? '' : $('notificationSecretEnv').value.trim(),
      events, enabled: $('notificationEnabled').checked
    })});
    $('notificationDialog').close();
    await loadProductivityData();
    showNotice('完成通知已创建');
  } catch (error) { setFormError('notificationFormError', error.message); }
  finally { button.disabled = false; }
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
      if (values.length > 1) actionButton(item, '合并到…', () => openMemoryMerge(memory, values));
      actionButton(item, '修订', () => openMemoryRevision(memory));
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

let memoryMergeOptions = [];
let memoryRevisionCurrent = null;

function memorySummary(container, title, memory) {
  container.replaceChildren(
    element('strong', '', title),
    document.createTextNode(`${memory.memoryKey}\n${memory.content}${memory.tags ? `\n标签：${memory.tags}` : ''}`)
  );
}

function updateMemoryMergePreview() {
  const target = memoryMergeOptions.find(value => value.id === $('memoryMergeTarget').value);
  if (target) memorySummary($('memoryMergePreview'), '目标 Memory', target);
  else $('memoryMergePreview').replaceChildren();
}

function openMemoryMerge(source, values) {
  memoryMergeOptions = values.filter(value => value.id !== source.id);
  if (!memoryMergeOptions.length) return showNotice('没有可合并的目标 Memory', true);
  $('memoryMergeForm').dataset.sourceId = source.id;
  memorySummary($('memoryMergeSource'), '源 Memory（合并后删除）', source);
  fillSelect($('memoryMergeTarget'), memoryMergeOptions.map(value => ({
    id: value.id, label: `${value.memoryKey} · ${value.content.slice(0, 60)}`
  })));
  setFormError('memoryMergeError');
  updateMemoryMergePreview();
  $('memoryMergeDialog').showModal();
  $('memoryMergeTarget').focus();
}

async function submitMemoryMerge(event) {
  event.preventDefault();
  setFormError('memoryMergeError');
  const sourceId = $('memoryMergeForm').dataset.sourceId;
  const target = memoryMergeOptions.find(value => value.id === $('memoryMergeTarget').value);
  if (!target || !sourceId) return setFormError('memoryMergeError', '请选择有效的目标 Memory');
  const button = $('saveMemoryMerge'); button.disabled = true;
  try {
    await api(`/v1/memories/${target.id}/merge`, {
      method: 'POST', body: JSON.stringify({sourceIds: [sourceId]})
    });
    $('memoryMergeDialog').close();
    await loadManagedMemories();
    showNotice(`Memory 已合并到 ${target.memoryKey}`);
  } catch (error) { setFormError('memoryMergeError', error.message); }
  finally { button.disabled = false; }
}

function openMemoryRevision(memory) {
  memoryRevisionCurrent = memory;
  $('memoryRevisionForm').dataset.memoryId = memory.id;
  $('memoryRevisionKey').value = memory.memoryKey;
  $('memoryRevisionContent').value = memory.content;
  $('memoryRevisionTags').value = memory.tags || '';
  setFormError('memoryRevisionError');
  $('memoryRevisionStatus').textContent = '';
  $('memoryRevisionList').replaceChildren(element('div', 'hint', '正在加载历史版本…'));
  $('memoryRevisionDialog').showModal();
  loadMemoryRevisionHistory();
}

async function loadMemoryRevisionHistory() {
  const memoryId = $('memoryRevisionForm').dataset.memoryId;
  if (!memoryId) return;
  $('refreshMemoryRevisions').disabled = true;
  try {
    const revisions = await api(`/v1/memories/${memoryId}/revisions`);
    $('memoryRevisionList').replaceChildren(...revisions.map(revision => {
      const item = workbenchItem(
        new Date(revision.replacedAt).toLocaleString(),
        `${revision.layer}/${revision.memoryType} · 置信度 ${Math.round(revision.confidence * 100)}% · ${revision.content}${revision.tags ? ` · 标签：${revision.tags}` : ''}`
      );
      actionButton(item, '恢复此版本', () => restoreMemoryRevision(revision, item), true);
      return item;
    }));
    if (!revisions.length) $('memoryRevisionList').append(element('div', 'hint', '暂无历史版本；保存一次修订后会在这里生成记录。'));
  } catch (error) {
    $('memoryRevisionList').replaceChildren(element('div', 'form-error', `历史版本加载失败：${error.message}`));
  } finally { $('refreshMemoryRevisions').disabled = false; }
}

async function restoreMemoryRevision(revision, item) {
  const memoryId = $('memoryRevisionForm').dataset.memoryId;
  const button = item.querySelector('button'); button.disabled = true;
  setFormError('memoryRevisionError');
  try {
    const restored = await api(`/v1/memories/${memoryId}/revisions/${revision.id}/restore`, {method: 'POST'});
    memoryRevisionCurrent = restored;
    $('memoryRevisionKey').value = restored.memoryKey;
    $('memoryRevisionContent').value = restored.content;
    $('memoryRevisionTags').value = restored.tags || '';
    await Promise.all([loadManagedMemories(), loadMemoryRevisionHistory()]);
    $('memoryRevisionStatus').textContent = `已恢复 ${new Date(revision.replacedAt).toLocaleString()} 的版本；恢复前内容已保留为历史版本。`;
  } catch (error) { setFormError('memoryRevisionError', error.message); }
  finally { button.disabled = false; }
}

async function submitMemoryRevision(event) {
  event.preventDefault();
  const memoryId = $('memoryRevisionForm').dataset.memoryId;
  if (!memoryId) return;
  setFormError('memoryRevisionError');
  $('memoryRevisionStatus').textContent = '';
  const button = $('saveMemoryRevision'); button.disabled = true;
  try {
    const updated = await api(`/v1/memories/${memoryId}`, {method: 'PUT', body: JSON.stringify({
      memoryKey: $('memoryRevisionKey').value.trim(),
      content: $('memoryRevisionContent').value.trim(),
      tags: $('memoryRevisionTags').value.trim()
    })});
    memoryRevisionCurrent = {...memoryRevisionCurrent, ...updated};
    await Promise.all([loadManagedMemories(), loadMemoryRevisionHistory()]);
    $('memoryRevisionStatus').textContent = '修订已保存，修改前内容已加入历史版本。';
  } catch (error) { setFormError('memoryRevisionError', error.message); }
  finally { button.disabled = false; }
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

async function loadPlans() {
  const list = $('planList');
  if (!list) return;
  try {
    const values = await api(`/v1/plans?projectKey=${encodeURIComponent(currentProjectKey())}&limit=50`);
    list.replaceChildren(...values.map(plan => {
      const item = workbenchItem(`${plan.status} · ${plan.objective}`, `v${plan.version} · ${plan.id} · ${new Date(plan.updatedAt).toLocaleString()}`);
      if (['WAITING_APPROVAL', 'DRAFT'].includes(plan.status)) actionButton(item, '启动', () => startPlan(plan.id), true);
      if (plan.status === 'ACTIVE') actionButton(item, '调度', () => dispatchPlan(plan.id), true);
      actionButton(item, '详情', () => inspectPlan(plan.id));
      return item;
    }));
    if (!values.length) list.append(element('div', 'hint', '暂无 Plan'));
  } catch (error) { showNotice(`Plan 加载失败：${error.message}`, true); }
}

async function startPlan(id) {
  try { await api(`/v1/plans/${id}/start`, {method: 'POST', body: '{}'}); await loadPlans(); }
  catch (error) { showNotice(`Plan 启动失败：${error.message}`, true); }
}

async function dispatchPlan(id) {
  try {
    const report = await api(`/v1/plans/${id}/dispatch`, {method: 'POST', body: '{}'});
    showNotice(`Plan 调度：启动 ${report.startedSteps} 步，刷新 ${report.refreshedSteps} 步`);
    await loadPlans();
  } catch (error) { showNotice(`Plan 调度失败：${error.message}`, true); }
}

async function inspectPlan(id) {
  try {
    const [view, jobs, checks, batches] = await Promise.all([
      api(`/v1/plans/${id}`),
      api(`/v1/plans/${id}/jobs`),
      api(`/v1/plans/${id}/validation-checks`),
      api(`/v1/plans/${id}/dag/batches`)
    ]);
    const steps = view.steps.map(step => `${step.status} · ${step.clientId} · ${step.title}${step.runId ? ` · run ${step.runId}` : ''}`).join('\n');
    const jobText = jobs.map(job => `${job.status} · ${job.kind} · ${job.id}${job.runId ? ` · run ${job.runId}` : ''}`).join('\n') || '无';
    const checkText = checks.map(check => `${check.status} · ${check.name} · ${check.evidence || check.expected}`).join('\n') || '无';
    const batchText = batches.map(batch => `#${batch.ordinal} ${batch.readOnlyEligible ? 'read-only' : 'serial'} · ${batch.stepIds.join(', ')}`).join('\n') || '无';
    alert(`Plan: ${view.plan.objective}\nStatus: ${view.plan.status}\n\nSteps:\n${steps}\n\nDAG Batches:\n${batchText}\n\nAsync Jobs:\n${jobText}\n\nValidation Checks:\n${checkText}`);
  } catch (error) { showNotice(`Plan 详情加载失败：${error.message}`, true); }
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

$('new').onclick = showHome;
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
$('settings').onclick = () => openConnectionSettings();
$('capabilities').onclick = openCapabilities;
$('workbench').onclick = openWorkbench;
$('agentStudio').onclick = openAgentStudio;
$('evaluationCenter').onclick = openEvaluationCenter;
$('closeWorkbench').onclick = () => $('workbenchDialog').close();
$('closeAgentStudio').onclick = () => $('agentStudioDialog').close();
$('closeEvaluationCenter').onclick = () => $('evaluationDialog').close();
$('searchAll').onclick = searchAll;
$('globalSearch').onkeydown = event => { if (event.key === 'Enter') searchAll(); };
$('refreshMemories').onclick = loadManagedMemories;
$('refreshArtifacts').onclick = loadArtifacts;
$('refreshPlans').onclick = loadPlans;
$('refreshPolicies').onclick = loadApprovalPolicies;
$('refreshProductivity').onclick = loadProductivityData;
$('refreshQueue').onclick = loadProductivityData;
$('refreshEvaluations').onclick = loadEvaluations;
$('addEvaluationSuite').onclick = openEvaluationSuiteDialog;
$('installEvaluationStarterPack').onclick = installEvaluationStarterPack;
$('installAgentStarterPack').onclick = installAgentStarterPack;
$('addAgentFromStudio').onclick = () => openAgentProfileDialog();
$('addTemplate').onclick = openTemplateDialog;
$('addProfile').onclick = openProfileDialog;
$('addSchedule').onclick = openScheduleDialog;
$('addNotification').onclick = openNotificationDialog;
$('cancelTemplate').onclick = () => $('templateDialog').close();
$('cancelAgentProfile').onclick = () => { state.editingAgentProfileId = ''; $('agentProfileDialog').close(); };
$('cancelProfile').onclick = () => $('profileDialog').close();
$('cancelSchedule').onclick = () => $('scheduleDialog').close();
$('cancelNotification').onclick = () => $('notificationDialog').close();
$('cancelEvaluationSuite').onclick = () => $('evaluationSuiteDialog').close();
$('cancelEvaluationCase').onclick = () => $('evaluationCaseDialog').close();
$('templateForm').onsubmit = submitTemplate;
$('agentProfileForm').onsubmit = submitAgentProfile;
$('agentProfileDialog').addEventListener('close', () => { state.editingAgentProfileId = ''; });
$('profileForm').onsubmit = submitProfile;
$('scheduleForm').onsubmit = submitSchedule;
$('notificationForm').onsubmit = submitNotification;
$('evaluationSuiteForm').onsubmit = submitEvaluationSuite;
$('evaluationCaseForm').onsubmit = submitEvaluationCase;
$('profileLocal').onchange = updateProfilePriceFields;
$('agentRole').onchange = updateAgentRoleHelp;
$('scheduleType').onchange = updateScheduleFields;
$('scheduleTemplate').onchange = () => { $('scheduleVariables').value = selectedTemplateVariables(); };
$('notificationType').onchange = () => updateNotificationFields(true);
$('cancelMemoryMerge').onclick = () => $('memoryMergeDialog').close();
$('cancelMemoryRevision').onclick = () => $('memoryRevisionDialog').close();
$('memoryMergeForm').onsubmit = submitMemoryMerge;
$('memoryRevisionForm').onsubmit = submitMemoryRevision;
$('memoryMergeTarget').onchange = updateMemoryMergePreview;
$('refreshMemoryRevisions').onclick = loadMemoryRevisionHistory;
$('templateName').oninput = () => {
  if (!$('templateShortcut').value) {
    const slug = $('templateName').value.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9_-]/g, '');
    $('templateShortcut').value = slug ? `/${slug}` : '';
  }
};
$('quickTemplate').onchange = () => applyTemplate($('quickTemplate').value);
$('agentProfile').onchange = () => {
  state.agentProfileId = $('agentProfile').value;
  localStorage.setItem('paicli_agent_profile', state.agentProfileId);
};
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
$('save').onclick = async () => {
  const key = $('key').value.trim();
  if (key) sessionStorage.setItem('paicli_api_key', key);
  else sessionStorage.removeItem('paicli_api_key');
  $('save').disabled = true;
  $('connectionHint').textContent = '正在验证连接…';
  try {
    await api('/v1/system/info');
    $('dialog').close();
    showNotice('连接成功');
    await refreshSessions();
  } catch (error) {
    if (error.status !== 401) $('connectionHint').textContent = `连接验证失败：${error.message}`;
  } finally {
    $('save').disabled = false;
  }
};
document.addEventListener('click', () => {
  document.querySelectorAll('.session-menu.open').forEach(menu => menu.classList.remove('open'));
});
document.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); $('input').focus(); }
  if (event.altKey && event.key.toLowerCase() === 'n') { event.preventDefault(); showHome(); }
});

$('workspace').classList.toggle('hide-detail', !state.detailOpen);
clearEvents();
renderModelControls();
renderEmpty();
refreshComposerOptions();
refreshSessions();
