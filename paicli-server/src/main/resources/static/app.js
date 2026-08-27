const $ = id => document.getElementById(id);

// After a refresh, restore the last viewed page (session, home mode, selected collaboration task)
// instead of always starting from the neutral composer. The child-session return pointer is kept
// in sessionStorage and is intentionally scoped to the tab lifetime.

const state = {
  sessions: [],
  groups: [],
  sessionId: localStorage.getItem('paicli_session') || '',
  runId: '',
  runStatus: '',
  planStatus: '',
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
  agentTeams: [],
  collaborationTasks: [],
  collaborationTaskHistory: [],
  selectedCollaborationTaskId: localStorage.getItem('paicli_selected_task') || '',
  collaborationTaskView: ['task', 'execution', 'collaboration'].includes(localStorage.getItem('paicli_task_view'))
    ? localStorage.getItem('paicli_task_view') : 'task',
  collaborationTaskNotice: '',
  collaborationReplyToId: '',
  collaborationTaskSignature: '',
  collaborationTaskListSignature: '',
  collaborationTaskRefreshTimer: 0,
  collaborationTaskRefreshPending: false,
  collaborationTaskReturn: null,
  availableSkills: [],
  managedMemories: [],
  toolCallNames: new Map(),
  editingAgentProfileId: '',
  editingAgentTeamId: '',
  selectedAgentTeamId: '',
  homeMode: ['chat', 'collaboration', 'tasks'].includes(localStorage.getItem('paicli_home_mode'))
    ? localStorage.getItem('paicli_home_mode') : 'chat',
  modelProfileId: localStorage.getItem('paicli_model_profile') || '',
  agentProfileId: localStorage.getItem('paicli_agent_profile') || '',
  executionShell: localStorage.getItem('paicli_execution_shell') || 'bash',
  notifiedRunId: '',
  notifiedApprovalIds: new Set(),
  planRefreshTimer: 0,
  planRefreshPending: false,
  approvalRefreshTimer: 0,
  approvalInboxRefreshTimer: 0,
  eventCursors: new Map(),
  detailOpen: innerWidth > 1000,
  messageScroll: new Map()
};
const terminal = new Set(['COMPLETED', 'FAILED', 'CANCELED']);
const statusNames = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  WAITING_MODEL: '思考中',
  WAITING_TOOL: '执行工具',
  WAITING_APPROVAL: '等待确认',
  WAITING_AGENT: '等待专家',
  PLAN_ACTIVE: '计划执行中',
  PLAN_WAITING_APPROVAL: '计划等待确认',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
};
const agentToolOptions = [
  ['list_plans', '查看项目计划'],
  ['get_plan', '查看计划图和状态'],
  ['create_plan', '创建受控计划（需审批）'],
  ['replan_plan', '调整受控计划（需审批）'],
  ['start_plan', '启动受控计划（需审批）'],
  ['cancel_plan', '取消受控计划（需审批）'],
  ['list_agent_profiles', '列出专家 Profile'],
  ['spawn_agent', '创建子智能体任务'],
  ['list_agents', '查看子任务状态'],
  ['get_agent_result', '读取子任务结果'],
  ['cancel_agent', '取消子任务'],
  ['get_collaboration_task', '读取当前协作任务'],
  ['post_task_comment', '发布任务评论或结论'],
  ['update_collaboration_task', '更新协作任务状态'],
  ['create_collaboration_subtask', '创建协作子任务'],
  ['list_dir', '列目录'],
  ['read_file', '读文件'],
  ['write_file', '写文件'],
  ['execute_command', '执行命令'],
  ['read_artifact', '读 Artifact'],
  ['search_knowledge', '查项目知识库'],
  ['session_search', '查历史会话'],
  ['web_search', '网页搜索'],
  ['web_fetch', '读取网页'],
  ['github_repo_fetch', 'GitHub 仓库读取'],
  ['mcp__github__*', 'GitHub MCP 全部工具']
];
const agentToolNames = new Map(agentToolOptions);
const expertPlanTools = ['list_plans','get_plan','create_plan','replan_plan','start_plan','cancel_plan'];
const agentRoleHelp = {
  LEADER: 'Leader：协作入口会优先选择；通常允许 list_agent_profiles / spawn_agent 来拆分和汇总任务。',
  EXPERT: 'Expert：领域执行者；按专家提示和工具白名单完成被分派的子任务。',
  REVIEWER: 'Reviewer：审查者；建议搭配只读工具和 READ_ONLY 审批策略，用于风险、回归和缺失测试检查。',
  RUNNER: 'Runner：验证执行者；通常允许 execute_command，用于运行测试、构建或检查命令。'
};

function agentProfileName(id, fallback = '未知专家') {
  return state.agentProfiles.find(value => value.id === id)?.name || fallback;
}

function agentTeamName(id, fallback = '未知团队') {
  return state.agentTeams.find(value => value.id === id)?.name || fallback;
}

function modelProfileName(id, fallback = '项目默认模型') {
  const profile = state.modelProfiles.find(value => value.id === id)
    || (!id ? state.modelProfiles.find(value => value.defaultProfile) : null);
  return profile ? `${profile.name} · ${profile.model}` : fallback;
}

function collaborationEntityName(type, id) {
  if (type === 'USER' || type === 'HUMAN') return '用户';
  if (type === 'AGENT') return agentProfileName(id);
  if (type === 'TEAM') return agentTeamName(id);
  if (type === 'SYSTEM') return '系统';
  return '协作成员';
}

function toolDisplayName(name) {
  if (!name) return '工具';
  if (agentToolNames.has(name)) return agentToolNames.get(name);
  if (name.startsWith('mcp__')) {
    const parts = name.split('__');
    return parts.length >= 3 ? `${parts[1]} · ${parts.slice(2).join(' ')}` : 'MCP 工具';
  }
  return name.replaceAll('_', ' ');
}

function indexMessageToolCalls(messages) {
  state.toolCallNames.clear();
  messages.forEach(message => {
    if (!message.toolCallsJson) return;
    let calls = [];
    try { calls = JSON.parse(message.toolCallsJson) || []; }
    catch (_) { return; }
    if (!Array.isArray(calls)) return;
    calls.forEach(call => {
      const id = call.id || call.callId || call.providerCallId;
      const name = call.name || call.toolName || call.function?.name;
      if (id && name) state.toolCallNames.set(id, name);
    });
  });
}

function toolCallDisplayName(id) {
  return toolDisplayName(state.toolCallNames.get(id));
}

function replaceEntityReferences(value) {
  let text = String(value ?? '');
  const named = [
    ...state.agentProfiles.map(item => [item.id, item.name]),
    ...state.agentTeams.map(item => [item.id, item.name]),
    ...state.modelProfiles.map(item => [item.id, `${item.name} · ${item.model}`]),
    ...state.collaborationTasks.map(item => [item.id, item.title]),
    ...state.collaborationTaskHistory.map(item => [item.task.id, item.task.title]),
    ...state.managedMemories.map(item => [item.id, memoryDisplayTitle(item)]),
    ...[...state.toolCallNames].map(([id, name]) => [id, toolDisplayName(name)])
  ];
  named.forEach(([id, name]) => {
    if (id && name) text = text.split(id).join(name);
  });
  return text
    .replace(/\brun_[A-Za-z0-9_-]+\b/g, token => token === state.runId ? '当前执行' : '关联执行')
    .replace(/\btask_[A-Za-z0-9_-]+\b/g, '协作任务')
    .replace(/\bagent_[A-Za-z0-9_-]+\b/g, '未知专家')
    .replace(/\bteam_[A-Za-z0-9_-]+\b/g, '未知团队')
    .replace(/\b(?:tool|call)_[A-Za-z0-9_-]+\b/g, '工具调用')
    .replace(/\bmemory_[A-Za-z0-9_-]+\b/g, '记忆条目')
    .replace(/\btask_id\b/gi, '任务')
    .replace(/\bagent_profile_id\b/gi, '专家');
}

function runMetaLabel(run) {
  if (!run) return '尚未开始';
  return `${statusNames[run.status] || run.status} · ${agentProfileName(run.agentProfileId, '默认专家')} · ${modelProfileName(run.modelProfileId)}`;
}

function taskRunModelName(run) {
  if (run.modelName) return run.modelProfileName
    ? `${run.modelProfileName} · ${run.modelName}`
    : run.modelName;
  return modelProfileName(run.modelProfileId, '服务端默认模型');
}

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

// Frontend-only explainers: these describe the product's default strategy. Live
// status (provider availability, selected model and limits) stays on the cards
// already rendered by each module, so a tooltip never masquerades as runtime data.
const moduleExplainers = {
  'connection': {title: '连接设置', summary: 'Console 只保存本页会话的访问凭据，不保存或下发模型密钥。', facts: ['认证：X-API-Key', '存储：sessionStorage', '模型密钥：仅 Server']},
  'approvals': {title: '持久化审批', summary: '危险操作先持久化审批，再按原工具参数执行，恢复时复用原决策。', facts: ['匹配：工具 + 完全相同参数', '执行：审批后原样复用', '策略：可撤销']},
  'capabilities': {title: '能力管理', summary: '按项目统一管理检索、Skill 与 MCP；状态卡展示当前实际能力和健康度。', facts: ['隔离：项目级', '状态：实时加载', '来源：已配置 Provider']},
  'skills': {title: 'Skills', summary: '模型先看到稳定排序的能力索引，匹配后才加载完整说明、模板和资源。', facts: ['发现：稳定索引', '加载：load_skill', '资源：read_skill_resource 按需读取']},
  'knowledge': {title: '项目知识库', summary: '按标题、段落、句子、代码块和表格结构分块，再混合召回、融合和重排。', facts: ['分块：结构化', 'BM25 + Embedding', 'RRF：1.00 / 1.15', '候选：Top 30 → Rerank', 'Milvus：可选 COSINE 索引']},
  'mcp': {title: 'MCP Server', summary: '按 Server 注册外部工具；敏感 Header 只保存环境变量引用，不回显真实值。', facts: ['凭据：env:变量名', '连接：测试后启用', '工具：按 Server 聚合']},
  'agent-studio': {title: '专家创建', summary: 'Profile 把角色指令、模型、工具、Skill 和交付格式固定为可审计配置。', facts: ['范围：项目级', 'Leader：可分派子任务']},
  'agent-profiles': {title: '智能体专家', summary: '每个专家使用独立提示、模型、Shell、工具与 Skill 白名单，形成可审计执行边界。', facts: ['模型：Profile 选择', '工具/Skill：白名单', '输出：交付格式约束']},
  'agent-teams': {title: '执行小队', summary: '保存 Leader 与专家组合，协作时以小队作为可复用编排模板。', facts: ['路由：Leader 分派', '执行：可追踪子 Run', '并发：受团队/任务限制']},
  'workbench': {title: '效率工作台', summary: '把运行、成本、自动化、记忆和审计入口集中到一个项目工作区。', facts: ['范围：当前项目', '数据：按需刷新']},
  'global-search': {title: '全局检索', summary: '一次查询聚合会话、消息、记忆、知识与 Artifact，便于追溯上下文。', facts: ['范围：项目内', '对象：5 类工作数据']},
  'usage': {title: '长期使用效率', summary: '按最近 30 天汇总用量、性能和预算风险，不改变实际调度策略。', facts: ['窗口：近 30 天', '指标：Token / 成本 / 延迟', '缓存：前缀复用 / 命中率']},
  'templates': {title: '任务模板', summary: '把常用提示和变量固化为可复用入口，发送前仍可编辑。', facts: ['复用：项目级', '执行：按当前模型']},
  'model-profiles': {title: '模型配置方案', summary: '集中管理 Provider、模型、端点、上下文、输出、重试、Fallback 与价格估算。', facts: ['实际模型：当前 Profile', '限额：运行时生效', '思考：按模型能力']},
  'run-queue': {title: 'Run 队列', summary: '查看持久化执行及其终态记录；删除只对明确选择的终态 Run 生效。', facts: ['状态：持久化', '删除：终态可选']},
  'schedules': {title: '定时任务', summary: '按已保存的模板和目标在设定时间触发，不绕过常规运行边界。', facts: ['输入：模板 + 变量', '触发：计划时间']},
  'notifications': {title: '完成通知', summary: '为完成或异常事件配置通知，运行链路仍以持久化事件为准。', facts: ['来源：Run 事件', '范围：项目级']},
  'portability': {title: 'Session 导入导出', summary: '按 Markdown、JSON 或审计包导出会话；可选隐私脱敏。', facts: ['格式：Markdown / JSON', '审计：完整包']},
  'memory': {title: '长期记忆', summary: 'Run 完成后异步提取；先按项目与 Agent/工作区/任务类型过滤，再由混合分数和 Cross-Encoder 动态选取。', facts: ['候选：语义 + 词法 + 置信度 + 新鲜度', '范围：PROJECT / AGENT / WORKSPACE / TASK_TYPE', '重排：复用本地 TEI，失败时确定性回退', 'Top K：最低相关性门槛 + 数量上限']},
  'memory-wiki': {title: '记忆浏览', summary: '按 L1/L2/L3、来源、置信度和修订历史检查记忆，人工编辑是纠错边界。', facts: ['层级：L1 / L2 / L3', '来源：Run / 人工', '反馈：历史效果参与排序']},
  'artifacts': {title: 'Artifact 工作台', summary: '集中列出 Run 生成的交付物，以所属 Run 和受控工作区作为追溯边界。', facts: ['归属：Run', '访问：受控路径']},
  'plans': {title: 'Plan 工作台', summary: '复杂任务以持久化 DAG 调度，步骤仍复用普通 ReAct Run，并保留验证证据。', facts: ['图：Step DAG', '调度：依赖 / 资源锁', '校验：Validation Check']},
  'policies': {title: '持久化审批策略', summary: '只复用相同工具与完全相同参数的已批准决策。', facts: ['匹配：精确参数', '安全：可随时撤销']},
  'evaluation': {title: 'Agent 评测中心', summary: '用固定套件、Case、Trial 和历史基线比较质量，显示真实执行证据而非主观评分。', facts: ['单位：Suite / Case / Trial', '通过：阈值 + 硬规则', '证据：真实 Run']},
  'evaluation-suites': {title: '评测套件', summary: '套件包含可开关的用例与默认 Trial 数，用于可重复回归。', facts: ['执行：真实 Run', '对比：历史基线']},
  'evaluation-report': {title: '执行报告', summary: '报告汇总每次 Trial 的得分、规则证据与失败原因。', facts: ['证据：持久化', '结果：可回看']},
  'collaboration': {title: '多智能体协作', summary: 'Leader 按目标、复杂度和风险决定是否分派，并汇总子任务证据。', facts: ['入口：Leader', '并发：最多专家数', '结果：子 Run 汇总']},
  'collaboration-tasks': {title: '协作任务', summary: '任务长期存在，Agent Run 是每次可追踪的执行；最终验收由人工决定。', facts: ['任务：长期状态', '执行：可追踪 Run']},
  'chat': {title: '普通对话', summary: '当前 Profile 决定模型和工具范围；复杂目标可转为受控 Plan。', facts: ['模型：当前 Profile', '复杂任务：Plan']}
};

function createModuleExplainer(key) {
  const config = moduleExplainers[key];
  if (!config) return null;
  const wrapper = element('div', 'module-explainer');
  wrapper.tabIndex = 0;
  wrapper.setAttribute('aria-label', `查看“${config.title}”的策略说明`);
  wrapper.setAttribute('aria-haspopup', 'true');
  const card = element('span', 'module-explainer-card');
  card.setAttribute('role', 'tooltip');
  card.append(
    element('strong', '', config.title),
    element('span', 'module-explainer-summary', config.summary)
  );
  const facts = element('span', 'module-explainer-facts');
  config.facts.forEach(fact => facts.append(element('span', '', fact)));
  card.append(facts);
  wrapper.append(card);
  const setOpen = open => {
    wrapper.classList.toggle('is-open', open);
    wrapper.setAttribute('aria-expanded', String(open));
  };
  wrapper.setAttribute('aria-expanded', 'false');
  wrapper.addEventListener('pointermove', event => {
    const suppressUntil = Number(wrapper.dataset.moduleExplainerSuppressUntil || 0);
    if (event.pointerType !== 'touch' && performance.now() >= suppressUntil) setOpen(true);
  });
  wrapper.addEventListener('pointerleave', () => setOpen(false));
  wrapper.addEventListener('focus', () => setOpen(true));
  wrapper.addEventListener('click', event => {
    event.preventDefault();
    setOpen(!wrapper.classList.contains('is-open'));
  });
  wrapper.addEventListener('focusout', () => {
    requestAnimationFrame(() => { if (!wrapper.contains(document.activeElement)) setOpen(false); });
  });
  return wrapper;
}

function suppressDialogModuleExplainers(dialog) {
  const suppressUntil = performance.now() + 350;
  dialog.querySelectorAll('.module-explainer').forEach(wrapper => {
    wrapper.dataset.moduleExplainerSuppressUntil = String(suppressUntil);
    wrapper.classList.remove('is-open');
    wrapper.setAttribute('aria-expanded', 'false');
  });
}

document.addEventListener('toggle', event => {
  const dialog = event.target;
  if (!(dialog instanceof HTMLDialogElement) || !dialog.open) return;
  requestAnimationFrame(() => suppressDialogModuleExplainers(dialog));
}, true);

const moduleExplainerTargets = [
  ['#workbenchDialog .workbench-search-section', 'global-search'],
  ['#usageSummary', 'usage'], ['#templateList', 'templates'], ['#profileList', 'model-profiles'],
  ['#queueCollapse', 'run-queue'], ['#scheduleList', 'schedules'], ['#notificationList', 'notifications'],
  ['#sessionImport', 'portability'], ['#memoryList', 'memory'], ['#memoryWikiDialog', 'memory-wiki'],
  ['#artifactList', 'artifacts'], ['#planList', 'plans'], ['#policyList', 'policies'],
  ['#evaluationDialog', 'evaluation'], ['.evaluation-suites-pane', 'evaluation-suites'],
  ['.evaluation-report-pane', 'evaluation-report']
];

function tagModuleExplainerTargets() {
  moduleExplainerTargets.forEach(([selector, key]) => {
    document.querySelectorAll(selector).forEach(node => {
      const container = node.matches('section, dialog') ? node : node.closest('section, dialog');
      if (container && !container.dataset.moduleHelp) container.dataset.moduleHelp = key;
    });
  });
}

function hydrateModuleExplainers(root = document) {
  tagModuleExplainerTargets();
  root.querySelectorAll?.('[data-module-help]').forEach(container => {
    if (container.dataset.moduleHelpAttached === 'true') return;
    const config = moduleExplainers[container.dataset.moduleHelp];
    if (!config) return;
    const heading = container.querySelector(':scope > strong, :scope > .dialog-title > strong, :scope > h1, :scope > h2, :scope > h3')
      || container.querySelector(':scope > .section-title h3, :scope > .pane-title h3, :scope > .task-workspace-toolbar h1');
    if (!heading) return;
    const explainer = createModuleExplainer(container.dataset.moduleHelp);
    if (!explainer) return;
    heading.replaceWith(explainer);
    explainer.prepend(heading);
    container.dataset.moduleHelpAttached = 'true';
  });
}

function showNotice(text, error = false) {
  $('notice').textContent = 'Enter 发送，Shift + Enter 换行';
  $('notice').className = 'notice';
  window.alert(error ? `需要处理：\n${text}` : text);
}

function setStatus(value = '') {
  state.runStatus = value;
  const effective = effectiveConversationStatus(value);
  $('status').textContent = statusNames[effective] || '空闲';
  $('status').className = `status${effective && !terminal.has(effective) ? ' active' : ''}`
    + `${effective === 'FAILED' ? ' error' : ''}`;
  $('composer').classList.toggle('running', Boolean(effective && !terminal.has(effective)));
  $('input').disabled = ['WAITING_APPROVAL', 'WAITING_AGENT', 'PLAN_ACTIVE', 'PLAN_WAITING_APPROVAL'].includes(effective);
  const retryable = Boolean(state.runId && terminal.has(value) && !planInProgress());
  $('retryRun').hidden = !retryable;
  $('branchRun').hidden = !retryable;
  configureApprovalPolling(Boolean(state.runId));
  if ((terminal.has(effective) || ['WAITING_APPROVAL', 'WAITING_AGENT', 'PLAN_WAITING_APPROVAL'].includes(effective))
      && state.runId && state.notifiedRunId !== `${state.runId}:${effective}`) {
    state.notifiedRunId = `${state.runId}:${effective}`;
    if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
      new Notification(`PaiCLI · ${statusNames[effective] || effective}`, {body: $('chatTitle').textContent || state.runId});
    }
  }
}

function planInProgress() {
  return ['ACTIVE', 'WAITING_APPROVAL'].includes(state.planStatus);
}

function effectiveConversationStatus(runStatus = state.runStatus) {
  if (!planInProgress() || (runStatus && !terminal.has(runStatus))) return runStatus;
  return state.planStatus === 'WAITING_APPROVAL' ? 'PLAN_WAITING_APPROVAL' : 'PLAN_ACTIVE';
}

function scrollBottom() {
  requestAnimationFrame(() => $('messages').scrollTop = $('messages').scrollHeight);
}

function messagesNearBottom(offset = 80) {
  const container = $('messages');
  return container.scrollHeight - container.scrollTop - container.clientHeight < offset;
}

function restoreMessageScroll(previousTop, previousHeight) {
  requestAnimationFrame(() => {
    const container = $('messages');
    container.scrollTop = Math.max(0, Math.min(previousTop, container.scrollHeight - container.clientHeight));
  });
}

function rememberMessageScroll(sessionId = state.sessionId) {
  if (!sessionId) return;
  const container = $('messages');
  state.messageScroll.set(sessionId, {
    top: container.scrollTop,
    atBottom: messagesNearBottom(),
    height: container.scrollHeight
  });
  sessionStorage.setItem(`paicli_message_scroll_${sessionId}`,
    JSON.stringify(state.messageScroll.get(sessionId)));
}

function savedMessageScroll(sessionId) {
  if (state.messageScroll.has(sessionId)) return state.messageScroll.get(sessionId);
  try {
    const saved = JSON.parse(sessionStorage.getItem(`paicli_message_scroll_${sessionId}`) || 'null');
    if (saved && Number.isFinite(saved.top)) {
      state.messageScroll.set(sessionId, saved);
      return saved;
    }
  } catch (_) { }
  return null;
}

function configureApprovalPolling(enabled) {
  clearInterval(state.approvalRefreshTimer);
  state.approvalRefreshTimer = 0;
  if (!enabled || !state.runId) return;
  state.approvalRefreshTimer = setInterval(() => {
    if (state.runId) loadApprovals();
  }, 2500);
}

function updateComposerVisibility() {
  const hidden = !state.sessionId && ['collaboration', 'tasks'].includes(state.homeMode);
  $('compose').hidden = hidden;
}

function modelProvider(profile) {
  const value = `${profile?.model || ''} ${profile?.baseUrl || ''}`.toLowerCase();
  if (value.includes('kimi-') || value.includes('moonshot.cn')) return 'Kimi';
  if (value.includes('deepseek')) return 'DeepSeek';
  return profile?.localModel ? '本地' : '兼容模型';
}

function modelProfileLabel(profile) {
  return `${modelProvider(profile)} · ${profile.name} · ${profile.model}`;
}

function selectedModelProfile() {
  return state.modelProfiles.find(value => value.id === state.modelProfileId)
    || state.modelProfiles.find(value => value.defaultProfile)
    || null;
}

function selectedModelIsKimiK3() {
  return (selectedModelProfile()?.model || '').toLowerCase().startsWith('kimi-k3');
}

function reasoningEffortForRequest() {
  return selectedModelIsKimiK3() || state.thinkingMode === 'enabled' ? state.reasoningEffort : '';
}

function selectModelProfile(profileId, rerenderHome = true) {
  state.modelProfileId = profileId || '';
  if (state.modelProfileId) localStorage.setItem('paicli_model_profile', state.modelProfileId);
  else localStorage.removeItem('paicli_model_profile');
  if ($('modelProfile')) $('modelProfile').value = state.modelProfileId;
  renderModelControls();
  scheduleEstimate();
  if (rerenderHome && !state.sessionId) syncHomeModelPicker();
}

function syncHomeModelPicker() {
  const selected = selectedModelProfile();
  document.querySelectorAll('[data-home-model-summary]').forEach(node => {
    node.textContent = selected ? modelProfileLabel(selected) : '使用项目或服务端默认模型';
  });
  document.querySelectorAll('[data-home-model-reset]').forEach(button => {
    button.hidden = !state.modelProfileId;
  });
  document.querySelectorAll('[data-home-model-profile]').forEach(button => {
    const active = Boolean(selected && button.dataset.homeModelProfile === selected.id);
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  });
}

function renderHomeModelPicker() {
  const picker = element('section', 'home-model-picker');
  const heading = element('div', 'home-model-picker-head');
  const copy = element('div');
  const summary = element('small', '',
    selectedModelProfile() ? modelProfileLabel(selectedModelProfile()) : '使用项目或服务端默认模型');
  summary.dataset.homeModelSummary = 'true';
  copy.append(element('strong', '', '本轮模型'), summary);
  const reset = element('button', 'text-button', '恢复默认');
  reset.type = 'button';
  reset.dataset.homeModelReset = 'true';
  reset.hidden = !state.modelProfileId;
  reset.onclick = () => selectModelProfile('');
  heading.append(copy, reset);
  const rail = element('div', 'home-model-rail');
  ['DeepSeek', 'Kimi'].forEach(provider => {
    const profile = state.modelProfiles.find(value => modelProvider(value) === provider);
    const active = Boolean(profile && selectedModelProfile()?.id === profile.id);
    const button = element('button', `home-model-card${active ? ' active' : ''}`);
    button.type = 'button';
    button.disabled = !profile;
    button.dataset.homeModelProfile = profile?.id || '';
    button.setAttribute('aria-pressed', String(active));
    const mark = element('span', `model-mark ${provider.toLowerCase()}`, provider === 'Kimi' ? 'K' : 'D');
    const text = element('span', 'home-model-copy');
    text.append(
      element('strong', '', provider),
      element('small', '', profile
        ? `${profile.model} · ${profile.maxContextTokens.toLocaleString()} ctx`
        : '模型方案尚未加载')
    );
    const trait = element('span', 'model-trait',
      provider === 'Kimi' ? '1M · 始终思考' : '1M · 思考可切换');
    button.append(mark, text, trait);
    if (profile) button.onclick = () => selectModelProfile(profile.id);
    rail.append(button);
  });
  picker.append(heading, rail);
  return picker;
}

async function refreshApprovalInbox() {
  const button = $('approvalInbox');
  if (!button) return [];
  try {
    const values = await api(`/v1/approvals?projectKey=${encodeURIComponent(currentProjectKey())}`);
    button.hidden = !values.length;
    $('approvalInboxCount').textContent = String(values.length);
    if ($('approvalInboxDialog').open) renderApprovalInbox(values);
    for (const approval of values) {
      if (!state.notifiedApprovalIds.has(approval.id)) {
        state.notifiedApprovalIds.add(approval.id);
        if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
          new Notification('PaiCLI · 待审批', {body: approval.reason || '专家操作正在等待确认'});
        }
      }
    }
    return values;
  } catch (_) {
    return [];
  }
}

function renderApprovalInbox(values) {
  const list = $('approvalInboxList');
  const cards = values.map(approval => {
    const item = element('article', 'managed-item approval');
    item.append(
      element('strong', '', approval.runId === state.runId ? '当前执行等待确认' : '协作执行等待确认'),
      element('div', 'hint', approval.reason || '需要人工确认后继续')
    );
    const actions = element('div', 'actions');
    const approve = element('button', 'primary', '仅本次允许');
    const deny = element('button', 'primary deny', '拒绝');
    approve.onclick = () => resolveApproval(approval.id, 'APPROVED');
    deny.onclick = () => resolveApproval(approval.id, 'DENIED');
    actions.append(approve, deny);
    item.append(actions);
    return item;
  });
  list.replaceChildren(...cards);
  if (!cards.length) list.append(element('div', 'hint', '当前项目没有待审批操作'));
}

function renderHomeExecutionPicker() {
  const picker = element('section', 'home-execution-picker');
  const copy = element('div', 'home-execution-copy');
  copy.append(
    element('strong', '', '执行环境'),
    element('small', '', '命令在隔离的 Docker Sandbox 内运行；每次工具调用仍需审批。')
  );
  const select = element('select', 'home-execution-select');
  [
    ['bash', 'Bash'],
    ['sh', 'POSIX sh'],
    ['powershell', 'PowerShell']
  ].forEach(([value, label]) => {
    const option = element('option', '', label);
    option.value = value;
    select.append(option);
  });
  select.value = state.executionShell;
  select.onchange = () => selectExecutionShell(select.value);
  picker.append(copy, select);
  return picker;
}

function selectExecutionShell(shell) {
  state.executionShell = ['sh', 'bash', 'powershell'].includes(shell) ? shell : 'bash';
  localStorage.setItem('paicli_execution_shell', state.executionShell);
  if ($('executionShell')) $('executionShell').value = state.executionShell;
  document.querySelectorAll('.home-execution-select').forEach(select => {
    select.value = state.executionShell;
  });
}

function renderEmpty() {
  updateComposerVisibility();
  $('stack').classList.toggle('task-stack', state.homeMode === 'tasks');
  const empty = element('div', 'empty');
  const content = element('div');
  if (state.homeMode === 'chat') content.dataset.moduleHelp = 'chat';
  if (state.homeMode === 'tasks') content.className = 'task-home-content';
  if (state.homeMode === 'collaboration') content.append(renderHomeCollaboration());
  else if (state.homeMode === 'tasks') content.append(renderCollaborationTaskWorkspace());
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
      renderHomeModelPicker(),
      renderHomeExecutionPicker(),
      actions
    );
  }
  empty.append(content);
  $('stack').replaceChildren(empty);
  syncGlobalModeSwitch();
  configureCollaborationTaskPolling(state.homeMode === 'tasks');
}

function syncGlobalModeSwitch() {
  document.querySelectorAll('[data-home-mode]').forEach(button => {
    const active = button.dataset.homeMode === state.homeMode;
    button.className = active ? 'primary' : 'secondary';
    button.setAttribute('aria-pressed', String(active));
  });
}

function setHomeMode(mode) {
  if (!['chat', 'collaboration', 'tasks'].includes(mode)) return;
  state.homeMode = mode;
  localStorage.setItem('paicli_home_mode', mode);
  document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
  showHome();
}

const collaborationStatusNames = {
  BACKLOG: '待规划', TODO: '待处理', IN_PROGRESS: '进行中', BLOCKED: '阻塞',
  IN_REVIEW: '待验收', DONE: '已完成', CANCELED: '已取消'
};

function renderCollaborationTaskWorkspace() {
  const workspace = element('section', 'task-workspace');
  workspace.id = 'collaborationTaskWorkspace';
  workspace.dataset.moduleHelp = 'collaboration-tasks';
  const toolbar = element('div', 'task-workspace-toolbar');
  const heading = element('div');
  heading.append(element('h1', '', '协作任务'),
    element('p', '', '任务长期存在；Agent Run 是一次次可追踪的执行。'));
  const actions = element('div', 'managed-actions');
  const refresh = element('button', 'secondary', '刷新');
  refresh.onclick = refreshCollaborationTaskWorkspace;
  const create = element('button', 'primary', '新建任务');
  create.onclick = () => $('collaborationTaskCreate')?.toggleAttribute('open');
  actions.append(refresh, create);
  toolbar.append(heading, actions);
  const createPanel = renderCollaborationTaskCreate();
  const layout = element('div', 'task-workspace-layout');
  const listPane = element('aside', 'task-list-pane');
  const filter = element('select', 'control-select');
  filter.id = 'collaborationTaskStatusFilter';
  filter.append(new Option('全部状态', ''));
  Object.entries(collaborationStatusNames).forEach(([id, label]) => filter.append(new Option(label, id)));
  filter.onchange = refreshCollaborationTaskWorkspace;
  const list = element('div', 'task-master-list');
  list.id = 'collaborationTaskList';
  listPane.append(filter, list);
  const detail = element('main', 'task-detail-pane');
  detail.id = 'collaborationTaskDetail';
  layout.append(listPane, detail);
  workspace.append(toolbar, createPanel, layout);
  requestAnimationFrame(refreshCollaborationTaskWorkspace);
  return workspace;
}

function renderCollaborationTaskCreate() {
  const details = element('details', 'task-create-panel');
  details.id = 'collaborationTaskCreate';
  details.append(element('summary', '', '新建协作任务'));
  const form = element('form', 'task-create-form');
  form.onsubmit = createCollaborationTask;
  const quick = element('div', 'task-create-quick');
  const titleField = element('label', 'form-field');
  titleField.append(element('span', '', '任务标题'));
  const title = element('input');
  title.id = 'collaborationTaskTitle';
  title.required = true;
  title.maxLength = 180;
  title.placeholder = '例如：完成支付链路回归测试';
  titleField.append(title);
  const typeField = element('label', 'form-field');
  typeField.append(element('span', '', '负责人类型'));
  const type = element('select');
  type.id = 'collaborationTaskAssigneeType';
  [['TEAM', 'AgentTeam'], ['AGENT', 'Agent']].forEach(([id, label]) => type.append(new Option(label, id)));
  typeField.append(type);
  const assigneeField = element('label', 'form-field');
  assigneeField.append(element('span', '', '负责人'));
  const assignee = element('select');
  assignee.id = 'collaborationTaskAssignee';
  assignee.required = true;
  assigneeField.append(assignee);
  const submit = element('button', 'primary', '创建任务');
  submit.type = 'submit';
  const fillAssignees = () => fillCollaborationAssignees(assignee, type.value);
  type.onchange = fillAssignees;
  fillAssignees();
  quick.append(titleField, typeField, assigneeField, submit);

  const advanced = element('details', 'task-create-advanced');
  advanced.append(element('summary', '', '更多设置'));
  const advancedGrid = element('div', 'task-create-advanced-grid');
  const descriptionField = element('label', 'form-field');
  descriptionField.append(element('span', '', '任务说明（可选）'));
  const description = element('textarea');
  description.id = 'collaborationTaskDescription';
  description.rows = 3;
  description.placeholder = '背景、范围和约束';
  descriptionField.append(description);
  const acceptanceField = element('label', 'form-field');
  acceptanceField.append(element('span', '', '完成条件（可选）'));
  const acceptance = element('textarea');
  acceptance.id = 'collaborationTaskAcceptance';
  acceptance.rows = 2;
  acceptance.placeholder = '用于 Agent 或 Team Leader 判断何时提交人工验收';
  acceptanceField.append(acceptance,
    element('small', 'hint', '负责人据此提交待验收，最终由人工确认是否完成。'));
  advancedGrid.append(descriptionField, acceptanceField);
  advanced.append(advancedGrid);
  const error = element('div', 'form-error');
  error.id = 'collaborationTaskCreateError';
  form.append(quick, advanced, error);
  details.append(form);
  return details;
}

function fillCollaborationAssignees(select, type, selected = '') {
  let values = [];
  if (type === 'TEAM') values = state.agentTeams.filter(value => value.enabled);
  if (type === 'AGENT') values = state.agentProfiles.filter(value => value.enabled);
  const options = [new Option(type === 'TEAM' ? '选择 AgentTeam' : '选择 Agent', ''),
    ...values.map(value => new Option(value.name, value.id))];
  select.replaceChildren(...options);
  select.value = selected;
}

async function createCollaborationTask(event) {
  event.preventDefault();
  setFormError('collaborationTaskCreateError');
  const type = $('collaborationTaskAssigneeType').value;
  const assigneeId = $('collaborationTaskAssignee').value;
  if (!assigneeId) return setFormError('collaborationTaskCreateError', '请选择任务负责人');
  try {
    const task = await api('/v1/collaboration/tasks', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(),
      title: $('collaborationTaskTitle').value.trim(),
      description: $('collaborationTaskDescription').value.trim(),
      acceptanceCriteria: $('collaborationTaskAcceptance').value.trim(),
      status: 'TODO', priority: 0, assigneeType: type, assigneeId: assigneeId || null, stage: 0
    })});
    state.selectedCollaborationTaskId = task.id;
    state.collaborationTaskNotice = '';
    localStorage.setItem('paicli_selected_task', task.id);
    localStorage.setItem('paicli_task_view', 'task');
    event.target.reset();
    fillCollaborationAssignees($('collaborationTaskAssignee'), $('collaborationTaskAssigneeType').value);
    $('collaborationTaskCreate').open = false;
    await refreshCollaborationTaskWorkspace();
    await loadSidebarHistory();
  } catch (error) { setFormError('collaborationTaskCreateError', error.message); }
}

function configureCollaborationTaskPolling(enabled) {
  clearInterval(state.collaborationTaskRefreshTimer);
  state.collaborationTaskRefreshTimer = 0;
  if (!enabled) return;
  state.collaborationTaskRefreshTimer = setInterval(async () => {
    if (document.hidden || state.collaborationTaskRefreshPending || !$('collaborationTaskWorkspace')) return;
    state.collaborationTaskRefreshPending = true;
    try {
      await refreshCollaborationTaskWorkspace({background: true});
    } finally {
      state.collaborationTaskRefreshPending = false;
    }
  }, 3000);
}

function collaborationTaskListSignature(tasks) {
  return JSON.stringify(tasks.map(task => [task.id, task.status, task.updatedAt, task.assigneeType, task.assigneeId]));
}

function collaborationTaskDetailSignature(detail) {
  return JSON.stringify({
    task: [detail.task.status, detail.task.updatedAt, detail.task.assigneeType, detail.task.assigneeId],
    children: detail.children.map(child => [child.id, child.status, child.stage, child.updatedAt]),
    comments: detail.comments.map(view => [view.comment.id, view.comment.content, view.comment.resolved,
      view.comment.conclusion, view.comment.createdAt, (view.mentions || []).map(value => `${value.type}:${value.id}`)]),
    activities: detail.activities.map(activity => [activity.id, activity.activityType, activity.payloadJson]),
    runs: detail.runs.map(run => [run.runId, run.taskId, run.status, run.modelName, run.createdAt, run.finishedAt]),
    threads: (detail.expertThreads || []).map(thread => [thread.threadId, thread.latestRunId, thread.digestJson,
      (thread.runs || []).map(run => [run.runId, run.ordinal, run.status])]),
    deliverables: [Boolean(detail.finalDeliveryReady),
      (detail.workspaceFiles || []).map(file => [file.runId, file.path, file.updatedAt || ''])]
  });
}

function collaborationTaskInteractionActive(pane) {
  const active = document.activeElement;
  return Boolean(active && pane.contains(active) && active.matches('textarea, input, select'));
}

function collaborationTaskFormDraft() {
  if (!$('collaborationCommentContent')) return null;
  return {
    content: $('collaborationCommentContent').value,
    mention: $('collaborationCommentMention')?.value || '',
    conclusion: Boolean($('collaborationCommentConclusion')?.checked)
  };
}

function restoreCollaborationTaskFormDraft(draft) {
  if (!draft || !$('collaborationCommentContent')) return;
  $('collaborationCommentContent').value = draft.content;
  if ($('collaborationCommentMention')) $('collaborationCommentMention').value = draft.mention;
  if ($('collaborationCommentConclusion')) $('collaborationCommentConclusion').checked = draft.conclusion;
}

async function refreshCollaborationTaskWorkspace(options = {}) {
  if (state.homeMode !== 'tasks' || !$('collaborationTaskList')) return;
  const background = options?.background === true;
  const filter = $('collaborationTaskStatusFilter')?.value || '';
  const query = new URLSearchParams({projectKey: currentProjectKey(), limit: '200'});
  if (filter) query.set('status', filter);
  try {
    state.collaborationTasks = await api(`/v1/collaboration/tasks?${query}`);
    const currentTasks = new Map(state.collaborationTasks.map(task => [task.id, task]));
    let historyChanged = false;
    state.collaborationTaskHistory = state.collaborationTaskHistory.map(history => {
      const task = currentTasks.get(history.task.id);
      if (!task || collaborationTaskListSignature([task]) === collaborationTaskListSignature([history.task])) return history;
      historyChanged = true;
      return {...history, task};
    });
    if (historyChanged) renderSessions();
    if (!state.collaborationTasks.some(value => value.id === state.selectedCollaborationTaskId)) {
      state.selectedCollaborationTaskId = state.collaborationTasks[0]?.id || '';
      state.collaborationTaskNotice = '';
      state.collaborationTaskSignature = '';
    }
    const listSignature = collaborationTaskListSignature(state.collaborationTasks);
    if (!background || listSignature !== state.collaborationTaskListSignature) renderCollaborationTaskList();
    state.collaborationTaskListSignature = listSignature;
    await renderCollaborationTaskDetail({background});
  } catch (error) {
    if (!background) $('collaborationTaskList').replaceChildren(element('div', 'form-error', error.message));
  }
}

function renderCollaborationTaskList() {
  const list = $('collaborationTaskList');
  list.replaceChildren(...state.collaborationTasks.map(task => {
    const item = element('button', `task-master-item${task.id === state.selectedCollaborationTaskId ? ' selected' : ''}`);
    item.type = 'button';
    item.append(element('strong', '', task.title),
      element('small', '', `${collaborationStatusNames[task.status] || task.status} · ${collaborationAssigneeName(task)}`));
    item.onclick = async () => {
      state.selectedCollaborationTaskId = task.id;
      state.collaborationTaskView = 'task';
      state.collaborationTaskNotice = '';
      state.collaborationTaskSignature = '';
      localStorage.setItem('paicli_selected_task', task.id);
      localStorage.setItem('paicli_task_view', 'task');
      renderCollaborationTaskList();
      await renderCollaborationTaskDetail();
    };
    return item;
  }));
  if (!state.collaborationTasks.length) list.append(element('div', 'task-empty', '当前筛选下没有协作任务'));
}

function collaborationAssigneeName(task) {
  if (task.assigneeType === 'TEAM') return agentTeamName(task.assigneeId);
  if (task.assigneeType === 'AGENT') return agentProfileName(task.assigneeId);
  return '历史手动任务';
}
const collaborationRunRelationships = {
  TRIGGERED: '触发执行', HUMAN_ACTION: '人工发起', MENTION: '提及触发', REPLY: '回复触发',
  RUN_EVENT: '执行结果触发', STAGE_BARRIER: '阶段完成触发', STAGE_DELEGATION: '阶段派发', MANUAL: '手动触发'
};

function collaborationRunRelationshipName(relationship) {
  return collaborationRunRelationships[relationship] || relationship || '执行';
}

function collaborationStageLabel(detail, taskId) {
  if (!detail || !detail.children || !taskId || !detail.task || taskId === detail.task.id) return '';
  const child = detail.children.find(value => value.id === taskId);
  if (!child) return '';
  const agent = child.assigneeType === 'AGENT' ? agentProfileName(child.assigneeId)
    : (child.assigneeType === 'TEAM' ? agentTeamName(child.assigneeId) : '');
  return '阶段 ' + (child.stage || '?') + (agent ? ' · ' + agent : '');
}

async function renderCollaborationTaskDetail(options = {}) {
  const pane = $('collaborationTaskDetail');
  if (!pane) return;
  const background = options?.background === true;
  if (!state.selectedCollaborationTaskId) {
    pane.replaceChildren(element('div', 'task-empty', '选择任务查看详情'));
    return;
  }
  try {
    const detail = await api(`/v1/collaboration/tasks/${state.selectedCollaborationTaskId}`);
    if (state.selectedCollaborationTaskId !== detail.task.id) return;
    const signature = collaborationTaskDetailSignature(detail);
    if (background && signature === state.collaborationTaskSignature) return;
    if (background && collaborationTaskInteractionActive(pane)) return;
    const previousScrollTop = $('messages').scrollTop;
    const formDraft = background ? collaborationTaskFormDraft() : null;
    const head = element('div', 'task-detail-head');
    const copy = element('div');
    copy.append(element('h2', '', detail.task.title),
      element('small', '', `${collaborationStatusNames[detail.task.status] || detail.task.status} · ${collaborationAssigneeName(detail.task)} · 更新于 ${new Date(detail.task.updatedAt).toLocaleString()}`));
    const status = element('span', `task-status task-status-${detail.task.status.toLowerCase()}`,
      collaborationStatusNames[detail.task.status] || detail.task.status);
    const headActions = element('div', 'task-detail-head-actions');
    const removeTask = element('button', 'secondary task-delete-button', '删除任务');
    removeTask.type = 'button';
    removeTask.title = (detail.runs || []).some(run => !['COMPLETED', 'FAILED', 'CANCELED'].includes(run.status))
      ? '请先取消仍在执行的任务，再删除协作任务记录'
      : '删除协作任务记录，保留已结束的执行与会话';
    removeTask.onclick = () => deleteCollaborationTask(detail.task);
    headActions.append(status, removeTask);
    head.append(copy, headActions);
    const tabs = element('div', 'task-detail-tabs');
    [['task', '任务'], ['collaboration', '协作'], ['execution', '执行']].forEach(([id, label]) => {
      const button = element('button', state.collaborationTaskView === id ? 'primary' : 'secondary', label);
      button.onclick = () => {
        state.collaborationTaskView = id;
        localStorage.setItem('paicli_task_view', id);
        renderCollaborationTaskDetail();
      };
      tabs.append(button);
    });
    const body = element('div', 'task-detail-body');
    if (state.collaborationTaskView === 'collaboration') body.append(renderTaskCollaborationLayer(detail));
    else if (state.collaborationTaskView === 'execution') body.append(renderTaskExecutionLayer(detail));
    else body.append(renderTaskDefinitionLayer(detail));
    const nodes = [head];
    if (state.collaborationTaskNotice) {
      nodes.push(element('div', 'task-inline-notice', state.collaborationTaskNotice));
    }
    nodes.push(tabs, body);
    pane.replaceChildren(...nodes);
    state.collaborationTaskSignature = signature;
    restoreCollaborationTaskFormDraft(formDraft);
    if (background) requestAnimationFrame(() => { $('messages').scrollTop = previousScrollTop; });
  } catch (error) { pane.replaceChildren(element('div', 'form-error', error.message)); }
}

function renderTaskDefinitionLayer(detail) {
  const task = detail.task;
  const root = element('div', 'task-definition-layer');
  root.append(taskTextSection('任务说明', task.description || '未填写'),
    taskTextSection('完成条件', task.acceptanceCriteria || '未设置，由负责人结合执行结果提交人工验收。'));
  if (detail.children.length) {
    const children = element('div', 'task-section');
    const stageHead = element('div');
    stageHead.append(element('h3', '', '子任务与阶段'));
    const stageNumbers = [...new Set(detail.children.map(child => child.stage).filter(Boolean))];
    if (stageNumbers.length > 1) {
      stageHead.append(element('small', 'hint', '阶段按序号串行交付：阶段 N 依赖前面阶段完成后执行；同阶段多个子任务（不同负责人）可并行。'));
    }
    children.append(stageHead);
    detail.children.forEach(child => {
      const row = element('div', 'task-child-row');
      const status = child.status === 'IN_REVIEW' ? '已交付，等待负责人汇总'
        : (collaborationStatusNames[child.status] || child.status);
      const run = (detail.runs || []).find(value => value.taskId === child.id);
      const parallelCount = detail.children.filter(value => value.stage === child.stage && value.id !== child.id).length + 1;
      const parallel = parallelCount > 1 ? ' · 并行 ' + parallelCount + ' 个' : '';
      row.append(element('strong', '', (child.stage ? '阶段 ' + child.stage : '子任务') + ' · ' + child.title),
        element('small', '', status + ' · ' + collaborationAssigneeName(child) + parallel + (run ? ' · ' + run.status : '')));
      children.append(row);
    });
    root.append(children);
  }
  const route = element('div', 'task-route-preview');
  route.id = 'taskRoutePreview';
  root.append(renderTaskHumanActions(detail), route);
  return root;
}

function renderTaskHumanActions(detail) {
  const task = detail.task;
  const root = element('section', 'task-action-panel');
  root.append(element('h3', '', '人工操作'));
  const activeRun = (detail.runs || []).some(run => !['COMPLETED', 'FAILED', 'CANCELED'].includes(run.status));
  const hint = activeRun
    ? '当前 Run 正在执行。可在“协作”中追加评论或提及负责人，Run 结束后可继续推进。'
    : humanActionHint(task.status);
  root.append(element('p', 'hint', hint));
  const reasonField = element('label', 'form-field');
  reasonField.append(element('span', '', task.status === 'IN_REVIEW' ? '审核意见或补充要求' : '补充指令或操作原因'));
  const reason = element('textarea');
  reason.id = 'collaborationTaskActionReason';
  reason.rows = 2;
  reason.placeholder = task.status === 'IN_REVIEW' ? '验收通过可不填；要求返工时必须说明原因' : '可选；阻塞任务时必须说明原因';
  reasonField.append(reason);
  root.append(reasonField);

  const actions = element('div', 'managed-actions task-action-buttons');
  if (task.assigneeType !== 'HUMAN' && task.assigneeId) {
    const preview = element('button', 'secondary', '预览路由');
    preview.type = 'button';
    preview.onclick = () => previewCollaborationTaskRoute(task);
    actions.append(preview);
  }
  if (!activeRun && task.assigneeType !== 'HUMAN') appendTaskStateActions(actions, task);
  if (!activeRun && task.assigneeType === 'HUMAN') {
    if (['DONE', 'CANCELED'].includes(task.status)) appendTaskActionButton(actions, task, 'REOPEN', '重新打开');
    else appendTaskActionButton(actions, task, 'CANCEL', '取消历史任务');
  }
  if (activeRun) {
    const collaboration = element('button', 'secondary', '前往协作');
    collaboration.type = 'button';
    collaboration.onclick = () => { state.collaborationTaskView = 'collaboration'; renderCollaborationTaskDetail(); };
    actions.append(collaboration);
    if (!['DONE', 'CANCELED'].includes(task.status)) {
      appendTaskActionButton(actions, task, 'CANCEL', '取消任务');
    }
  }
  root.append(actions);
  return root;
}

function humanActionHint(status) {
  if (status === 'IN_REVIEW') return '负责人已判断执行完成并提交验收。人工审核后确认完成，或带意见要求返工。';
  if (status === 'BLOCKED') return '任务处于阻塞状态，人工可补充指令并恢复执行。';
  if (status === 'DONE') return '任务已通过人工验收；需要继续处理时可重新打开。';
  if (status === 'CANCELED') return '任务已取消；需要继续处理时可重新打开。';
  if (status === 'IN_PROGRESS') return '当前没有活跃 Run，可继续执行、标记阻塞或取消任务。';
  return '人工可启动任务，也可在开始前取消。';
}

function appendTaskStateActions(actions, task) {
  const add = (action, label, primary = false) => appendTaskActionButton(actions, task, action, label, primary);
  if (['BACKLOG', 'TODO'].includes(task.status)) add('START', '启动执行', true);
  if (task.status === 'IN_PROGRESS') {
    add('CONTINUE', '继续执行', true);
    add('BLOCK', '标记阻塞');
  }
  if (task.status === 'BLOCKED') add('RESUME', '解除阻塞并继续', true);
  if (task.status === 'IN_REVIEW') {
    add('ACCEPT', '验收通过', true);
    add('REQUEST_REWORK', '要求返工');
  }
  if (['DONE', 'CANCELED'].includes(task.status)) add('REOPEN', '重新打开');
  if (!['DONE', 'CANCELED'].includes(task.status)) add('CANCEL', '取消任务');
}

function appendTaskActionButton(actions, task, action, label, primary = false) {
  const button = element('button', primary ? 'primary' : 'secondary', label);
  button.type = 'button';
  button.onclick = () => applyCollaborationTaskAction(task, action);
  actions.append(button);
}

function taskTextSection(title, text) {
  const section = element('section', 'task-section');
  section.append(element('h3', '', title), element('p', '', text));
  return section;
}

async function previewCollaborationTaskRoute(task) {
  const target = $('taskRoutePreview');
  try {
    const preview = await api('/v1/collaboration/routing/preview', {method: 'POST', body: JSON.stringify({
      projectKey: task.projectKey,
      input: `${task.title}\n${task.description || ''}\n${task.acceptanceCriteria || ''}`,
      targetType: task.assigneeType, targetId: task.assigneeId
    })});
    const candidates = (preview.candidates || []).map(value => `${value.name}（${value.role}）：${value.reason}`).join('\n');
    target.replaceChildren(element('strong', '', `建议并发 ${preview.estimatedConcurrency} · ${preview.complexity}/${preview.risk}`),
      element('pre', '', candidates || '直接由目标 Agent 执行'));
  } catch (error) { target.replaceChildren(element('div', 'form-error', error.message)); }
}

async function applyCollaborationTaskAction(task, action) {
  const reason = $('collaborationTaskActionReason')?.value.trim() || '';
  if (action === 'CANCEL'
      && !confirm(`取消任务“${task.title}”并停止所有关联的活跃执行？任务记录和审计历史会保留。`)) return;
  try {
    const result = await api(`/v1/collaboration/tasks/${task.id}/actions`, {method: 'POST', body: JSON.stringify({
      action, reason, idempotencyKey: `console:${task.id}:${task.updatedAt}:${action}`
    })});
    if (result.triggerExecution?.run) {
      state.collaborationTaskView = 'execution';
      showNotice('协作执行已创建');
    } else showNotice(taskActionSuccessMessage(action));
    await refreshCollaborationTaskWorkspace();
    await loadSidebarHistory();
  } catch (error) { showNotice(`操作失败：${error.message}`, true); }
}

function taskActionSuccessMessage(action) {
  return ({
    ACCEPT: '任务已验收完成', REQUEST_REWORK: '已要求负责人返工', START: '任务已启动', CONTINUE: '已继续执行',
    RESUME: '任务已恢复执行', BLOCK: '任务已标记阻塞', CANCEL: '任务已取消', REOPEN: '任务已重新打开'
  })[action] || '操作已完成';
}

const collaborationActionNames = {
  START: '启动任务', CONTINUE: '继续执行', RESUME: '恢复执行', BLOCK: '标记阻塞',
  REQUEST_REWORK: '要求返工', ACCEPT: '验收通过', CANCEL: '取消任务', REOPEN: '重新打开'
};

function collaborationActivityPayload(activity) {
  try { return JSON.parse(activity.payloadJson || '{}'); }
  catch (_) { return {}; }
}

function collaborationActivityPresentation(activity, detail) {
  const payload = collaborationActivityPayload(activity);
  const actor = collaborationEntityName(activity.actorType, activity.actorId);
  const run = detail.runs.find(value => value.runId === activity.subjectId);
  const comment = detail.comments.find(value => value.comment.id === activity.subjectId)?.comment;
  const runContext = run
    ? `${agentProfileName(run.agentProfileId, actor)} · ${taskRunModelName(run)}`
    : '';
  if (activity.activityType === 'TASK_CREATED') {
    return {tone: 'task', title: '任务已建立', detail: `${actor} 创建协作任务并等待负责人启动`};
  }
  if (activity.activityType === 'HUMAN_ACTION') {
    const transition = payload.toStatus
      ? `任务进入“${collaborationStatusNames[payload.toStatus] || payload.toStatus}”`
      : '';
    return {tone: 'human', title: collaborationActionNames[payload.action] || '人工推进任务',
      detail: [transition, payload.reason].filter(Boolean).join(' · ')};
  }
  if (activity.activityType === 'RUN_TRIGGERED') {
    const triggerNames = {HUMAN_ACTION: '人工发起', RUN_EVENT: '执行结果触发', MENTION: '提及触发', REPLY: '回复触发', STAGE_BARRIER: '阶段完成触发'};
    return {tone: 'run', title: `${actor} 派发协作执行`,
      detail: [runContext, triggerNames[payload.triggerType] || '事件驱动'].filter(Boolean).join(' · ')};
  }
  if (activity.activityType === 'RUN_COMPLETED') {
    return {tone: 'success', title: `${actor} 完成一次执行`, detail: runContext || '结果已回传任务负责人'};
  }
  if (activity.activityType === 'RUN_FAILED') {
    return {tone: 'error', title: `${actor} 执行失败`, detail: replaceEntityReferences(payload.error || '等待负责人处理')};
  }
  if (activity.activityType === 'COMMENT_POSTED') {
    const summary = replaceEntityReferences(comment?.content || '').replace(/\s+/g, ' ').trim();
    return {tone: 'comment', title: `${actor} 发布协作评论`, detail: summary.slice(0, 150)};
  }
  if (activity.activityType === 'STATUS_CHANGED') {
    const transition = payload.toStatus
      ? `从“${collaborationStatusNames[payload.fromStatus] || payload.fromStatus || '原状态'}”调整为“${collaborationStatusNames[payload.toStatus] || payload.toStatus}”`
      : '';
    return {tone: 'status', title: `${actor} 更新任务进展`,
      detail: replaceEntityReferences(payload.reason || transition || '状态已同步')};
  }
  if (activity.activityType === 'STAGE_BARRIER_COMPLETED') {
    return {tone: 'success', title: '阶段任务已汇合', detail: '当前阶段的子任务均已交付，Leader 可以推进下一阶段'};
  }
  return {tone: 'status', title: '协作状态已更新', detail: `${actor} 完成了一次任务更新`};
}

function collaborationVisibleActivities(detail) {
  return detail.activities.filter(activity => {
    if (activity.activityType !== 'STATUS_CHANGED' || activity.actorType !== 'SYSTEM') return true;
    const payload = collaborationActivityPayload(activity);
    return Boolean(payload.reason || payload.toStatus);
  });
}

function renderCollaborationProcessOverview(detail) {
  const root = element('section', 'task-process-overview');
  const head = element('div', 'task-process-head');
  const heading = element('div');
  heading.append(element('h3', '', '协作进展'), element('small', '', '评论、执行和状态每 3 秒同步'));
  head.append(heading, element('span', 'task-live-indicator', '自动同步'));

  const status = detail.task.status;
  const hasRuns = detail.runs.length > 0;
  const reviewStarted = ['IN_REVIEW', 'DONE'].includes(status);
  const stages = [
    {label: '任务建立', state: 'done'},
    {label: '执行派发', state: hasRuns ? 'done' : 'current'},
    {label: '专家协作', state: reviewStarted ? 'done' : (hasRuns ? 'current' : 'pending')},
    {label: '人工验收', state: status === 'DONE' ? 'done' : (status === 'IN_REVIEW' ? 'current' : 'pending')}
  ];
  const track = element('div', 'task-process-track');
  stages.forEach((stage, index) => {
    const item = element('div', `task-process-step ${stage.state}`);
    item.append(element('span', 'task-process-marker', stage.state === 'done' ? '✓' : String(index + 1)),
      element('strong', '', stage.label));
    track.append(item);
  });

  const participants = new Set();
  detail.runs.forEach(run => participants.add(agentProfileName(run.agentProfileId, '执行专家')));
  detail.comments.forEach(view => {
    const comment = view.comment;
    if (!['USER', 'SYSTEM'].includes(comment.authorType)) {
      participants.add(collaborationEntityName(comment.authorType, comment.authorId));
    }
  });
  const activeRuns = detail.runs.filter(run => !['COMPLETED', 'FAILED', 'CANCELED'].includes(run.status)).length;
  const metrics = element('div', 'task-process-metrics');
  [[participants.size, '参与角色'], [detail.runs.length, activeRuns ? `${activeRuns} 个执行中` : '关联执行'],
    [detail.comments.length, '协作评论']].forEach(([value, label]) => {
    const metric = element('div', 'task-process-metric');
    metric.append(element('strong', '', String(value)), element('small', '', label));
    metrics.append(metric);
  });
  root.append(head, track, metrics);
  return root;
}

function renderTaskCollaborationLayer(detail) {
  const root = element('div', 'task-collaboration-layer');
  root.append(renderCollaborationProcessOverview(detail));
  const grid = element('div', 'task-collaboration-grid');
  const discussion = element('section', 'task-discussion-pane');
  const discussionHead = element('div', 'task-layer-heading');
  discussionHead.append(element('h3', '', '评论与决策'), element('small', '', `${detail.comments.length} 条 · 含人工与子 Agent 评论`));
  const form = element('form', 'task-comment-form');
  form.onsubmit = event => submitCollaborationComment(event, detail.task.id);
  const reply = element('div', 'hint');
  reply.id = 'collaborationReplyHint';
  const replying = detail.comments.find(view => view.comment.id === state.collaborationReplyToId)?.comment;
  reply.textContent = replying
    ? `正在回复 ${collaborationEntityName(replying.authorType, replying.authorId)}`
    : '发布评论或显式提及 Agent/Team';
  const content = element('textarea');
  content.id = 'collaborationCommentContent';
  content.rows = 3;
  content.required = true;
  content.placeholder = '记录进展、问题或结论';
  const mention = element('select', 'control-select');
  mention.id = 'collaborationCommentMention';
  mention.append(new Option('不提及', ''));
  state.agentProfiles.filter(value => value.enabled).forEach(value => mention.append(new Option(`Agent · ${value.name}`, `AGENT:${value.id}`)));
  state.agentTeams.filter(value => value.enabled).forEach(value => mention.append(new Option(`Team · ${value.name}`, `TEAM:${value.id}`)));
  const conclusion = checkboxControl('collaborationCommentConclusion', '标记为当前结论');
  const submit = element('button', 'primary', '发布评论');
  submit.type = 'submit';
  form.append(reply, content, mention, conclusion, submit);
  const comments = element('div', 'task-comment-list');
  const orderedComments = [...detail.comments].sort((a, b) => String(a.comment.createdAt).localeCompare(String(b.comment.createdAt)));
  const renderComment = view => {
    const comment = view.comment;
    const stageLabel = collaborationStageLabel(detail, comment.taskId);
    const human = comment.authorType === 'USER';
    const cls = ['task-comment', comment.conclusion ? 'conclusion' : '', human ? 'human' : '', stageLabel ? 'stage' : ''].filter(Boolean).join(' ');
    const item = element('article', cls);
    const meta = element('div', 'task-comment-meta');
    const metaLine = [stageLabel, new Date(comment.createdAt).toLocaleString()].filter(Boolean).join(' · ');
    meta.append(element('strong', '', collaborationEntityName(comment.authorType, comment.authorId)),
      element('small', '', metaLine));
    item.append(meta);
    if (human) item.append(element('small', 'task-comment-tag', '人工评论'));
    if (comment.parentCommentId) {
      const parent = detail.comments.find(value => value.comment.id === comment.parentCommentId)?.comment;
      item.append(element('small', 'task-comment-reply', '回复 ' + collaborationEntityName(parent?.authorType, parent?.authorId)));
    }
    item.append(element('p', '', replaceEntityReferences(comment.content)));
    if ((view.mentions || []).length) item.append(element('small', 'hint',
      '提及：' + view.mentions.map(value => collaborationEntityName(value.type, value.id)).join('、')));
    const replyButton = element('button', 'secondary', '回复');
    replyButton.onclick = () => {
      state.collaborationReplyToId = comment.id;
      renderCollaborationTaskDetail();
      requestAnimationFrame(() => $('collaborationCommentContent')?.focus());
    };
    item.append(replyButton);
    return item;
  };
  orderedComments.forEach(view => comments.append(renderComment(view)));
  if (!detail.comments.length) comments.append(element('div', 'task-empty', '暂无评论'));
  discussion.append(discussionHead, form, comments);

  const flow = element('section', 'task-flow-pane');
  const flowHead = element('div', 'task-layer-heading');
  flowHead.append(element('h3', '', '协作动态'), element('small', '', '含各阶段子 Agent 动作 · 最新在前'));
  const timeline = element('div', 'task-activity-list');
  collaborationVisibleActivities(detail).slice().reverse().forEach(activity => {
    const presentation = collaborationActivityPresentation(activity, detail);
    const stageContext = collaborationStageLabel(detail, activity.taskId);
    if (stageContext) presentation.detail = stageContext + (presentation.detail ? ' · ' + presentation.detail : '');
    const item = element('article', `task-activity-item ${presentation.tone}`);
    const marker = element('span', 'task-activity-marker');
    const copy = element('div', 'task-activity-copy');
    const itemHead = element('div', 'task-activity-head');
    itemHead.append(element('strong', '', presentation.title),
      element('time', '', new Date(activity.createdAt).toLocaleString()));
    copy.append(itemHead);
    if (presentation.detail) copy.append(element('p', '', presentation.detail));
    item.append(marker, copy);
    timeline.append(item);
  });
  if (!timeline.children.length) timeline.append(element('div', 'task-empty', '任务尚未产生协作动态'));
  flow.append(flowHead, timeline);
  grid.append(discussion, flow);
  root.append(grid);
  return root;
}

async function submitCollaborationComment(event, taskId) {
  event.preventDefault();
  const mentionValue = $('collaborationCommentMention').value;
  const mentions = mentionValue ? [{type: mentionValue.split(':')[0], id: mentionValue.slice(mentionValue.indexOf(':') + 1)}] : [];
  try {
    await api(`/v1/collaboration/tasks/${taskId}/comments`, {method: 'POST', body: JSON.stringify({
      parentCommentId: state.collaborationReplyToId || null,
      content: $('collaborationCommentContent').value.trim(),
      conclusion: $('collaborationCommentConclusion').checked,
      mentions
    })});
    state.collaborationReplyToId = '';
    await renderCollaborationTaskDetail();
  } catch (error) { showNotice(`评论发布失败：${error.message}`, true); }
}

function renderExpertThreads(detail) {
  const threads = detail.expertThreads || [];
  if (!threads.length) return null;
  const section = element('section', 'task-section task-expert-threads');
  const head = element('div');
  head.append(element('h3', '', '专家线程'),
    element('small', 'hint', '同一专家在同一协作任务中的后续执行挂在同一个逻辑线程；每次执行都是不可复活的新 Run，跨 Run 只注入紧凑摘要。'));
  section.append(head);
  threads.forEach(thread => {
    const group = element('div', 'task-expert-thread');
    const header = element('strong', '', `${agentProfileName(thread.agentProfileId, '专家')} · ${thread.threadRole || 'EXPERT'}`);
    group.append(header);
    const runs = (thread.runs || []).slice().sort((a, b) => a.ordinal - b.ordinal);
    runs.forEach(run => {
      const row = element('div', 'task-expert-thread-run');
      const status = statusNames[run.status] || run.status;
      const shortRunId = (run.runId || '').replace(/^run_/, '');
      const detailRun = (detail.runs || []).find(value => value.runId === run.runId);
      const label = element('span', '', `#${run.ordinal} ${status} · ${shortRunId}`);
      row.append(label);
      if (detailRun) {
        const open = element('button', 'secondary', '打开会话');
        open.onclick = () => openCollaborationTaskSession(detail.task, detailRun.sessionId);
        row.append(open);
      }
      group.append(row);
    });
    section.append(group);
  });
  return section;
}

function renderTaskExecutionLayer(detail) {
  const root = element('div', 'task-execution-layer');
  const activeRuns = detail.runs.filter(run => !terminal.has(run.status));
  const failedRuns = detail.runs.filter(run => run.status === 'FAILED');
  const statePanel = element('section', 'task-section');
  if (detail.finalDeliveryReady) {
    statePanel.append(element('h3', '', '任务已完成'),
      element('p', 'hint', '最终交付已通过人工验收，以下工作区文件为当前交付版本。'));
  } else if (detail.task.status === 'IN_REVIEW') {
    statePanel.append(element('h3', '', '等待人工验收'),
      element('p', 'hint', failedRuns.length
        ? '存在失败的执行（可在下方“协作动态”查看原因），但阶段交付已就绪；请核验后验收（ACCEPT）或带原因要求返工（REQUEST_REWORK）。'
        : 'Leader 已形成最终结论且关联执行全部结束；用户验收通过后，当前工作区产物才标记为最终交付。'));
  } else if (activeRuns.length) {
    statePanel.append(element('h3', '', '任务尚未最终交付'),
      element('p', 'hint', `${activeRuns.length} 个执行仍未结束。WAITING_AGENT 表示 Leader 正在等待子专家；全部 Run 终态后才能发布最终结论并进入人工验收。`));
  } else {
    statePanel.append(element('h3', '', '等待最终收口'),
      element('p', 'hint', '关联执行已结束，仍需 Leader 形成最终结论，并由用户执行验收。'));
  }
  root.append(statePanel);

  const files = detail.workspaceFiles || [];
  if (files.length || detail.finalDeliveryReady) {
    const deliverables = element('section', 'task-section task-deliverables');
    deliverables.append(element('h3', '', detail.finalDeliveryReady ? '最终交付' : '当前工作区产物'));
    if (files.length) {
      deliverables.append(element('p', 'hint', detail.finalDeliveryReady
        ? '根任务和阶段专家共用的最终工作区文件。可直接预览或下载。'
        : '以下文件已经落入共享工作区，但在任务完成前仍可能被后续阶段修改。'));
      const grid = element('div', 'deliverable-grid');
      files.forEach(file => grid.append(renderWorkspaceFileCard(file.runId, file.path, file)));
      deliverables.append(grid);
    } else {
      deliverables.append(element('div', 'hint', '任务已完成，但共享工作区中尚未发现可交付文件。'));
    }
    root.append(deliverables);
  }
  const expertThreads = renderExpertThreads(detail);
  if (expertThreads) root.append(expertThreads);
  if (!detail.runs.length) root.append(element('div', 'task-empty', '尚未触发 Agent Run'));
  detail.runs.forEach(run => {
    const row = element('div', 'task-run-row');
    const copy = element('div');
    const stage = (detail.children || []).find(child => child.id === run.taskId);
    const label = stage ? `阶段 ${stage.stage} · ${stage.title}` : collaborationRunRelationshipName(run.relationship);
    const runStatus = statusNames[run.status] || run.status;
    copy.append(element('strong', '', `${label} · ${runStatus}`),
      element('small', '', `${agentProfileName(run.agentProfileId, '未指定专家')} · ${taskRunModelName(run)} · ${new Date(run.createdAt).toLocaleString()}`));
    if (run.status === 'WAITING_AGENT') {
      copy.append(element('small', 'hint', '当前 Leader Run 正在等待已派发的子专家返回，不代表任务已经完成。'));
    }
    const open = element('button', 'secondary', '打开会话');
    open.onclick = () => openCollaborationTaskSession(detail.task, run.sessionId);
    row.append(copy, open);
    root.append(row);
  });
  return root;
}

function renderHomeCollaboration() {
  const panel = element('div', 'home-collaboration');
  panel.dataset.moduleHelp = 'collaboration';
  const head = element('div', 'home-collaboration-head');
  const copy = element('div');
  copy.append(
    element('h1', '', '多智能体协作'),
    element('p', '', '输入一句话目标，由 Leader 查看专家目录、分派子任务并汇总结果。')
  );
  head.append(element('div', 'logo', 'π'), copy);
  const teamField = element('label', 'form-field full');
  teamField.append(element('span', '', '执行小队'));
  const teamRow = element('div', 'team-select-row');
  const team = element('select');
  team.id = 'homeCollaborationTeam';
  team.append(new Option('临时组合专家', ''));
  state.agentTeams.filter(value => value.enabled)
    .forEach(value => team.append(new Option(value.name, value.id)));
  team.value = state.agentTeams.some(value => value.id === state.selectedAgentTeamId)
    ? state.selectedAgentTeamId : '';
  team.onchange = () => {
    state.selectedAgentTeamId = team.value;
    applySelectedAgentTeam();
  };
  const manageTeams = element('button', 'secondary', '管理小队');
  manageTeams.type = 'button';
  manageTeams.onclick = openAgentStudio;
  teamRow.append(team, manageTeams);
  teamField.append(teamRow);
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
  maxExperts.max = '20';
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
  const preview = element('button', 'secondary', '预览路由');
  preview.onclick = previewHomeCollaboration;
  const start = element('button', 'primary', '启动协作');
  start.id = 'homeStartCollaboration';
  start.onclick = startCollaboration;
  actions.append(manage, preview, start);
  const routePreview = element('div', 'task-route-preview');
  routePreview.id = 'homeCollaborationRoutePreview';
  panel.append(head, renderHomeModelPicker(), teamField, leaderField, objectiveField,
    policy, checks, allowed, error, actions, routePreview);
  requestAnimationFrame(applySelectedAgentTeam);
  return panel;
}

async function previewHomeCollaboration() {
  const objective = $('homeCollaborationObjective')?.value.trim();
  if (!objective) return setFormError('homeCollaborationError', '请先填写一句话目标');
  const team = state.agentTeams.find(value => value.id === state.selectedAgentTeamId && value.enabled);
  const leaderId = $('homeCollaborationLeader')?.value
    || homeLeaders()[0]?.id;
  if (!team && !leaderId) return setFormError('homeCollaborationError', '没有可用的 Agent 或 AgentTeam');
  try {
    const preview = await api('/v1/collaboration/routing/preview', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), input: objective,
      targetType: team ? 'TEAM' : 'AGENT', targetId: team?.id || leaderId
    })});
    const target = $('homeCollaborationRoutePreview');
    target.replaceChildren(
      element('strong', '', `Leader ${preview.leaderName} · 建议并发 ${preview.estimatedConcurrency}`),
      element('pre', '', (preview.candidates || []).map(value => `${value.name}（${value.role}）：${value.reason}`).join('\n'))
    );
  } catch (error) { setFormError('homeCollaborationError', error.message); }
}

function applySelectedAgentTeam() {
  const team = state.agentTeams.find(value => value.id === state.selectedAgentTeamId && value.enabled);
  if (!team || !$('homeCollaborationLeader')) return;
  $('homeCollaborationLeader').value = team.leaderAgentProfileId;
  const members = new Set(parseStringListJson(team.memberAgentProfileIdsJson));
  document.querySelectorAll('[data-collaboration-agent]').forEach(input => {
    input.checked = members.has(input.value);
  });
  $('homeCollaborationMaxExperts').value = String(team.maxExperts || Math.max(1, members.size));
  $('homeCollaborationRequireReviewer').checked = Boolean(team.requireReviewer);
  $('homeCollaborationRequireRunner').checked = Boolean(team.requireRunner);
  $('homeCollaborationAllowExpertDelegation').checked = Number(team.maxDepth || 1) > 1;
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
  return enabled.filter(value => (value.collaborationRole || '').toUpperCase() === 'LEADER');
}

function renderRichText(text, className = 'text') {
  const root = element('div', `${className} rich-text`);
  const source = text || '';
  if (!source.trim()) return root;
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  for (let index = 0; index < lines.length;) {
    const line = lines[index];
    const trimmed = line.trim();
    if (!trimmed) { index++; continue; }
    if (trimmed.startsWith('```')) {
      const code = [];
      index++;
      while (index < lines.length && !lines[index].trim().startsWith('```')) code.push(lines[index++]);
      if (index < lines.length) index++;
      const pre = element('pre', 'code-block');
      pre.append(element('code', '', code.join('\n')));
      root.append(pre);
      continue;
    }
    const heading = /^(#{1,4})\s+(.+)$/.exec(trimmed);
    if (heading) {
      const node = element(`h${Math.min(4, heading[1].length + 2)}`, 'md-heading');
      appendInline(node, heading[2]);
      root.append(node);
      index++;
      continue;
    }
    if (/^\|.+\|$/.test(trimmed) && index + 1 < lines.length && /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(lines[index + 1].trim())) {
      const tableLines = [trimmed];
      index += 2;
      while (index < lines.length && /^\|.+\|$/.test(lines[index].trim())) tableLines.push(lines[index++].trim());
      root.append(renderMarkdownTable(tableLines));
      continue;
    }
    if (/^[-*]\s+/.test(trimmed)) {
      const list = element('ul', 'md-list');
      while (index < lines.length && /^[-*]\s+/.test(lines[index].trim())) {
        const item = element('li');
        appendInline(item, lines[index].trim().replace(/^[-*]\s+/, ''));
        list.append(item);
        index++;
      }
      root.append(list);
      continue;
    }
    if (/^\d+\.\s+/.test(trimmed)) {
      const list = element('ol', 'md-list');
      while (index < lines.length && /^\d+\.\s+/.test(lines[index].trim())) {
        const item = element('li');
        appendInline(item, lines[index].trim().replace(/^\d+\.\s+/, ''));
        list.append(item);
        index++;
      }
      root.append(list);
      continue;
    }
    if (/^>\s?/.test(trimmed)) {
      const quote = element('blockquote', 'md-quote');
      while (index < lines.length && /^>\s?/.test(lines[index].trim())) {
        const part = element('p');
        appendInline(part, lines[index].trim().replace(/^>\s?/, ''));
        quote.append(part);
        index++;
      }
      root.append(quote);
      continue;
    }
    const paragraph = element('p');
    let first = true;
    while (index < lines.length && lines[index].trim() && !isMarkdownBlockStart(lines[index], lines[index + 1])) {
      if (!first) paragraph.append(document.createTextNode(' '));
      appendInline(paragraph, lines[index].trim());
      first = false;
      index++;
    }
    root.append(paragraph);
  }
  return root;
}

function isMarkdownBlockStart(line, nextLine = '') {
  const trimmed = (line || '').trim();
  return trimmed.startsWith('```') || /^(#{1,4})\s+/.test(trimmed) || /^[-*]\s+/.test(trimmed)
    || /^\d+\.\s+/.test(trimmed) || /^>\s?/.test(trimmed)
    || (/^\|.+\|$/.test(trimmed) && /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test((nextLine || '').trim()));
}

function renderMarkdownTable(tableLines) {
  const table = element('table', 'md-table');
  const thead = element('thead');
  const header = element('tr');
  splitTableRow(tableLines[0]).forEach(value => {
    const th = element('th');
    appendInline(th, value);
    header.append(th);
  });
  thead.append(header);
  const tbody = element('tbody');
  tableLines.slice(1).forEach(line => {
    const row = element('tr');
    splitTableRow(line).forEach(value => {
      const cell = element('td');
      appendInline(cell, value);
      row.append(cell);
    });
    tbody.append(row);
  });
  table.append(thead, tbody);
  return table;
}

function splitTableRow(line) {
  return line.replace(/^\|/, '').replace(/\|$/, '').split('|').map(value => value.trim());
}

function appendInline(parent, text) {
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\)|https?:\/\/[^\s<>()]+|(?:[A-Za-z]:\\|\\\\)[^\n\r\t<>|"']+|artifact_[A-Za-z0-9_]+)/g;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > last) parent.append(document.createTextNode(text.slice(last, match.index)));
    appendInlineToken(parent, match[0]);
    last = match.index + match[0].length;
  }
  if (last < text.length) parent.append(document.createTextNode(text.slice(last)));
}

function appendInlineToken(parent, token) {
  const markdownLink = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token);
  if (markdownLink) {
    parent.append(createSmartLink(markdownLink[2], markdownLink[1]));
    return;
  }
  if (token.startsWith('`') && token.endsWith('`')) {
    parent.append(element('code', 'inline-code', token.slice(1, -1)));
    return;
  }
  if (token.startsWith('**') && token.endsWith('**')) {
    const strong = element('strong');
    appendInline(strong, token.slice(2, -2));
    parent.append(strong);
    return;
  }
  const {value, suffix} = trimLinkToken(token);
  parent.append(createSmartLink(value, value));
  if (suffix) parent.append(document.createTextNode(suffix));
}

function createSmartLink(target, label) {
  if (/^artifact_[A-Za-z0-9_]+$/.test(target)) return artifactAnchor(target, label);
  const link = element('a', 'content-link', label || target);
  if (/^https?:\/\//i.test(target)) {
    link.href = target;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
  } else if (isLocalFileTarget(target)) {
    link.href = localFileHref(target);
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.classList.add('local-file-link');
  } else {
    link.href = target;
  }
  return link;
}

function artifactAnchor(artifactId, label = artifactId) {
  const link = element('a', 'content-link artifact-link', label);
  link.href = `/v1/artifacts/${encodeURIComponent(artifactId)}/download`;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  return link;
}

function trimLinkToken(token) {
  const match = /^(.*?)([.,;:!?，。；：！？）)]*)$/.exec(token);
  return {value: match && match[1] ? match[1] : token, suffix: match ? match[2] : ''};
}

function isLocalFileTarget(value) {
  return /^file:\/\//i.test(value || '') || /^[A-Za-z]:\\/.test(value || '') || /^\\\\/.test(value || '');
}

function localFileHref(value) {
  if (/^file:\/\//i.test(value)) return value;
  if (/^\\\\/.test(value)) return `file:${value.replaceAll('\\', '/')}`;
  return `file:///${value.replaceAll('\\', '/')}`;
}

function renderDeliverables(message) {
  if (message.role !== 'assistant' || !message.finalAssistantForRun) return null;
  const artifacts = (message.runArtifacts || []).filter(isFinalArtifact);
  const urls = collectMatches(message.content || '', /https?:\/\/[^\s<>()]+/g);
  const paths = collectMatches(message.content || '', /(?:[A-Za-z]:\\|\\\\)[^\n\r\t<>|"']+/g)
    .filter(value => !/^https?:/i.test(value));
  const workspaceFiles = collectWorkspaceFiles(message.content || '');
  const artifactIds = collectMatches(message.content || '', /artifact_[A-Za-z0-9_]+/g)
    .filter(id => !artifacts.some(artifact => artifact.id === id));
  if (!artifacts.length && !urls.length && !paths.length && !workspaceFiles.length && !artifactIds.length) return null;
  const panel = element('section', 'deliverables');
  panel.append(element('div', 'deliverables-title', '交付成果'));
  const grid = element('div', 'deliverables-grid');
  artifacts.forEach(artifact => grid.append(renderArtifactCard(artifact)));
  artifactIds.forEach(id => grid.append(renderArtifactIdCard(id)));
  urls.forEach(url => grid.append(renderLinkCard('网址', url, url, 'url')));
  workspaceFiles.forEach(path => grid.append(renderWorkspaceFileCard(message.runId, path)));
  paths.forEach(path => grid.append(renderLocalFileCard(path)));
  panel.append(grid);
  return panel;
}

function isFinalArtifact(artifact) {
  const type = (artifact.type || '').toLowerCase();
  if (type === 'tool_result') return false;
  const name = artifact.name || '';
  if (['read_file', 'read_artifact', 'list_dir', 'execute_command'].includes(name)) return false;
  return true;
}

function collectWorkspaceFiles(text) {
  const values = [];
  const seen = new Set();
  const pattern = /(?:^|[\s`"'（(：:])([A-Za-z0-9_.\/-]+\.(?:html|htm|md|txt|pdf|docx?|pptx?|xlsx?|csv|json|xml|png|jpe?g|gif))(?![\w.-])/g;
  for (const match of text.matchAll(pattern)) {
    const path = trimLinkToken(match[1]).value.replaceAll('\\', '/');
    if (!path || path.includes('://') || path.startsWith('/') || path.startsWith('../') || path.includes('/../')) continue;
    if (!seen.has(path)) {
      seen.add(path);
      values.push(path);
    }
  }
  return values.slice(0, 8);
}

function collectMatches(text, regex) {
  const values = [];
  const seen = new Set();
  for (const match of text.matchAll(regex)) {
    const token = trimLinkToken(match[0]).value.trim();
    if (token && !seen.has(token)) {
      seen.add(token);
      values.push(token);
    }
  }
  return values.slice(0, 12);
}

function renderArtifactCard(artifact) {
  const card = element('article', 'deliverable-card artifact');
  card.append(element('strong', '', artifact.name || '未命名 Artifact'));
  card.append(element('small', '', `${artifact.type || 'artifact'} · ${formatBytes(artifact.size || 0)}`));
  const actions = element('div', 'deliverable-actions');
  const open = element('button', 'secondary', '打开');
  open.onclick = () => openArtifactPreview(artifact.id);
  const download = element('button', 'secondary', '下载');
  download.onclick = () => downloadArtifact(artifact.id);
  const preview = element('button', 'secondary', '预览');
  preview.onclick = async () => {
    try {
      const existing = card.querySelector('.artifact-preview');
      if (existing) { existing.remove(); return; }
      const value = await api(`/v1/artifacts/${encodeURIComponent(artifact.id)}/content?limit=12000`);
      card.append(element('pre', 'artifact-preview', value.content || ''));
    } catch (error) {
      showNotice(`Artifact 预览失败：${error.message}`, true);
    }
  };
  actions.append(open, download, preview);
  card.append(actions);
  return card;
}

function renderArtifactIdCard(id) {
  const card = element('article', 'deliverable-card artifact');
  card.append(element('strong', '', '关联 Artifact'));
  card.append(element('small', '', '模型生成的交付文件'));
  const actions = element('div', 'deliverable-actions');
  const open = element('button', 'secondary', '打开');
  open.onclick = () => openArtifactPreview(id);
  const download = element('button', 'secondary', '下载');
  download.onclick = () => downloadArtifact(id);
  actions.append(open, download);
  card.append(actions);
  return card;
}

function renderLinkCard(kind, label, href, type) {
  const card = element('article', `deliverable-card ${type || ''}`);
  card.append(element('strong', '', kind));
  const link = element('a', 'content-link', label);
  link.href = href;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  card.append(link);
  return card;
}

function renderLocalFileCard(path) {
  const card = renderLinkCard('本地文件', path, localFileHref(path), 'file');
  card.append(element('small', '', '浏览器允许 file 链接时可直接打开'));
  const actions = element('div', 'deliverable-actions');
  const copy = element('button', 'secondary', '复制路径');
  copy.onclick = async () => {
    try {
      await navigator.clipboard.writeText(path);
      showNotice('路径已复制');
    } catch {
      showNotice(path);
    }
  };
  actions.append(copy);
  card.append(actions);
  return card;
}

function renderWorkspaceFileCard(runId, path, metadata = null) {
  const card = element('article', 'deliverable-card workspace-file');
  card.append(element('strong', '', path));
  card.append(element('small', '', metadata ? `工作区文件 · ${formatBytes(metadata.size)}` : '工作区文件'));
  const actions = element('div', 'deliverable-actions');
  const open = element('button', 'secondary', '打开');
  open.onclick = () => openWorkspaceFilePreview(runId, path);
  const download = element('button', 'secondary', '下载');
  download.onclick = () => downloadWorkspaceFile(runId, path);
  actions.append(open, download);
  card.append(actions);
  return card;
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function renderMessage(message) {
  if (message.archived || message.role === 'summary') return;
  if (message.role === 'tool') {
    const details = element('details', 'tool');
    details.append(
      element('summary', '', `工具结果 · ${toolCallDisplayName(message.toolCallId)}`),
      element('pre', '', replaceEntityReferences(message.content || ''))
    );
    $('stack').append(details);
    return;
  }
  const row = element('article', `message ${message.role}`);
  const avatar = element('div', 'avatar', message.role === 'user' ? '你' : 'π');
  const body = element('div', 'body');
  body.append(
    element('div', 'who', message.role === 'user' ? '你' : 'PaiCLI'),
    renderRichText(replaceEntityReferences(message.content || ''), 'text')
  );
  const deliverables = renderDeliverables(message);
  if (deliverables) body.append(deliverables);
  if (message.reasoningContent) {
    const reasoning = element('details', 'reason');
    reasoning.append(
      element('summary', '', '查看思考过程'),
      element('pre', '', replaceEntityReferences(message.reasoningContent))
    );
    body.append(reasoning);
  }
  row.append(avatar, body);
  $('stack').append(row);
}

async function loadMessages(options = {}) {
  if (!state.sessionId) return renderEmpty();
  const forceScroll = Boolean(options.forceScroll);
  const saved = options.restoreSaved ? savedMessageScroll(state.sessionId) : null;
  const shouldFollow = forceScroll || (!saved && messagesNearBottom());
  const previousTop = $('messages').scrollTop;
  const previousHeight = $('messages').scrollHeight;
  const messages = await api(`/v1/sessions/${state.sessionId}/messages`);
  indexMessageToolCalls(messages);
  markFinalAssistantMessages(messages);
  $('stack').replaceChildren();
  const hasPlans = await renderSessionPlanPanel();
  await renderCollaborationBoard();
  messages.forEach(renderMessage);
  if (!$('stack').children.length) renderEmpty();
  configurePlanPolling(hasPlans);
  if (saved) {
    if (saved.atBottom) scrollBottom();
    else restoreMessageScroll(saved.top, saved.height);
  } else if (shouldFollow) scrollBottom();
  else restoreMessageScroll(previousTop, previousHeight);
}

function markFinalAssistantMessages(messages) {
  const finalByRun = new Map();
  messages.forEach(message => {
    if (message.role === 'assistant' && message.runId && message.content && message.content.trim()) {
      finalByRun.set(message.runId, message.id);
    }
  });
  messages.forEach(message => {
    message.finalAssistantForRun = Boolean(message.runId && finalByRun.get(message.runId) === message.id);
  });
}

async function renderSessionPlanPanel(options = {}) {
  const sessionId = options.sessionId || state.sessionId;
  const existing = options.replace ? $('stack').querySelector('.session-plan-panel') : null;
  try {
    const values = await api(`/v1/sessions/${sessionId}/plans?limit=3`);
    if (state.sessionId !== sessionId) return false;
    const activePlan = values.find(view => ['ACTIVE', 'WAITING_APPROVAL'].includes(view.plan.status));
    state.planStatus = activePlan?.plan.status || '';
    setStatus(state.runStatus);
    if (!values.length) {
      if (existing) replaceTopPanel(existing, null);
      return false;
    }
    const panel = element('section', 'session-plan-panel');
    const title = element('div', 'section-title');
    title.append(
      element('h3', '', '关联 Plan'),
      element('span', '', `${values.length} 个计划`)
    );
    const list = element('div', 'session-plan-list');
    values.forEach(view => list.append(renderSessionPlanItem(view)));
    panel.append(title, list);
    if (existing) replaceTopPanel(existing, panel);
    else if (options.replace) $('stack').prepend(panel);
    else $('stack').append(panel);
    return values.some(view => ['ACTIVE', 'WAITING_APPROVAL'].includes(view.plan.status));
  } catch (error) {
    if (state.sessionId !== sessionId) return false;
    const panel = element('section', 'session-plan-panel');
    panel.append(element('div', 'form-error', `Plan 面板加载失败：${error.message}`));
    if (existing) replaceTopPanel(existing, panel);
    else if (options.replace) $('stack').prepend(panel);
    else $('stack').append(panel);
    return false;
  }
}

function replaceTopPanel(existing, replacement) {
  if (!existing) return;
  const container = $('messages');
  const previousTop = container.scrollTop;
  const panelTop = existing.offsetTop;
  const previousHeight = existing.offsetHeight;
  if (replacement) existing.replaceWith(replacement);
  else existing.remove();
  requestAnimationFrame(() => {
    if (previousTop <= panelTop) return;
    const nextHeight = replacement?.offsetHeight || 0;
    container.scrollTop = Math.max(0, previousTop + nextHeight - previousHeight);
  });
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
  const taskName = conciseTaskName(plan.summary || plan.objective, '未命名计划');
  const item = workbenchItem(
    `${plan.status} · ${taskName}`,
    `v${plan.version} · ${completed}/${steps.length} 步 · ${current ? `${current.status}：${current.title}` : '暂无步骤'}`
  );
  item.classList.add('session-plan-item');
  item.querySelector('.managed-main').append(element('p', 'task-description', plan.objective || plan.summary || ''));
  const progress = element('div', 'plan-progress');
  const bar = element('span', '');
  bar.style.width = `${steps.length ? Math.round(completed / steps.length * 100) : 0}%`;
  progress.append(bar);
  const stepList = element('div', 'plan-step-strip');
  steps.slice(0, 8).forEach(step => {
    const button = element('button', `plan-step run-link ${step.status.toLowerCase()}`,
      `${step.status} · ${step.title} · ${step.runId ? '打开 Run' : 'Run 未生成'}`);
    button.type = 'button';
    button.disabled = !step.runId;
    button.title = step.runId ? `打开“${step.title}”的关联执行` : '该步骤尚未绑定执行';
    button.onclick = () => openPlanStepRun(plan, step);
    stepList.append(button);
  });
  if (steps.length > 8) stepList.append(element('span', 'plan-step', `+${steps.length - 8}`));
  item.querySelector('.managed-main').append(progress, stepList);
  if (plan.status === 'ACTIVE') actionButton(item, '调度', () => dispatchPlan(plan.id), true);
  actionButton(item, '详情', () => inspectPlan(plan.id));
  actionButton(item, '打开工作台', async () => { await openWorkbench(); });
  return item;
}

function configurePlanPolling(enabled) {
  clearInterval(state.planRefreshTimer);
  state.planRefreshTimer = 0;
  if (!enabled || !state.sessionId) return;
  state.planRefreshTimer = setInterval(async () => {
    if (state.planRefreshPending) return;
    const sessionId = state.sessionId;
    if (!sessionId) return;
    state.planRefreshPending = true;
    try {
      const active = await renderSessionPlanPanel({replace: true, sessionId});
      if (state.sessionId === sessionId && !active) configurePlanPolling(false);
    } finally {
      state.planRefreshPending = false;
    }
  }, 6000);
}

async function renderCollaborationBoard() {
  if (!state.runId) return;
  try {
    const board = await api(`/v1/runs/${state.runId}/collaboration`);
    if (!board.enabled && !board.tasks.length) return;
    const panel = element('section', 'collaboration-board');
    const policy = board.policy || {};
    if (board.parent?.sessionId) {
      const navigation = element('div', 'collaboration-navigation');
      navigation.append(
        element('span', '', `当前为子专家会话 · ${board.parent.agentName || '协作任务'}`),
        actionButtonElement('返回父专家', () => openParentSession(board.parent))
      );
      panel.append(navigation);
      $('stack').append(panel);
      return;
    }
    const title = element('div', 'section-title');
    title.append(
      element('h3', '', '协作任务看板'),
      element('span', '', `路由推断：${routingComplexityLabel(policy.complexity)} · ${routingRiskLabel(policy.risk)} · ${board.tasks.length}/${policy.maxExperts || 0} 专家`)
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
  const taskName = conciseTaskName(task.task, '协作任务');
  head.append(
    element('strong', '', taskName),
    element('span', `status ${terminal.has(task.status) ? '' : 'active'}`, task.status || 'UNKNOWN')
  );
  item.append(head);
  item.append(element('div', 'hint',
    `${task.agentName || profile.name || '子专家'} · ${profile.role || 'EXPERT'} · step ${task.currentStep || 0}`));
  if (task.blockedReason) item.append(element('div', 'hint', `阻塞：${task.blockedReason}`));
  if ((task.dependencies || []).length) {
    item.append(element('div', 'hint',
      `依赖 ${task.dependencies.length} 个节点 · 失败策略 ${task.failurePolicy || 'BLOCK_GRAPH'}`));
  }
  const description = task.task || '';
  item.append(element('p', 'collaboration-task-summary', description.length > 360
    ? `${description.slice(0, 360)}…` : description));
  const actions = element('div', 'collaboration-actions');
  const openChild = element('button', 'secondary', '打开子会话');
  openChild.onclick = () => openChildSession(task.childSessionId);
  const mountTrace = element('button', 'secondary', '挂载到执行详情');
  mountTrace.onclick = () => mountChildTrace(task);
  actions.append(openChild, mountTrace);
  if (task.delegationStatus === 'WAITING_HUMAN') {
    actions.append(
      actionButtonElement('批准继续', () => decideDelegationNode(task, 'APPROVE')),
      actionButtonElement('拒绝执行', () => decideDelegationNode(task, 'REJECT'))
    );
  }
  item.append(actions);
  (task.pendingApprovals || []).forEach(approval => item.append(renderChildApproval(approval)));
  if (task.error) item.append(element('div', 'form-error', task.error));
  if ((task.toolCalls || []).length || (task.events || []).length) {
    const details = element('details', 'collaboration-trace');
    const blocked = (task.pendingApprovals || []).length ? ' · 有待审批' : '';
    details.append(element('summary', '', `子执行链路${blocked}`));
    const trace = element('div', 'child-trace');
    (task.toolCalls || []).forEach(call => trace.append(renderTraceLine('tool', `${call.status} · ${toolDisplayName(call.name)}`, call.error)));
    (task.events || []).forEach(event => trace.append(renderTraceLine('event', event.type, event.data)));
    details.append(trace);
    item.append(details);
  }
  if (task.result) {
    const details = element('details', 'collaboration-result');
    details.append(element('summary', '', '查看专家结果'), element('pre', '', task.result));
    item.append(details);
  }
  return item;
}

async function decideDelegationNode(task, decision) {
  try {
    await api(`/v1/runs/${task.parentRunId || state.runId}/delegations/${task.delegationId}/decision`, {
      method: 'POST',
      body: JSON.stringify({decision, reason: 'Console human graph decision'})
    });
    await loadMessages();
  } catch (error) {
    alert(`协作节点决策失败：${error.message}`);
  }
}

function actionButtonElement(label, action) {
  const button = element('button', 'secondary', label);
  button.type = 'button';
  button.onclick = action;
  return button;
}

async function openParentSession(parent) {
  if (!parent?.sessionId) return;
  await openChildSession(parent.sessionId);
}

function renderChildApproval(approval) {
  const card = element('div', 'approval child-approval');
  card.append(element('strong', '', '子 Agent 等待确认'), element('div', '', approval.reason || '需要审批'));
  const actions = element('div', 'actions');
  const approve = element('button', 'primary', '允许');
  const deny = element('button', 'primary deny', '拒绝');
  approve.onclick = () => resolveApproval(approval.id, 'APPROVED');
  deny.onclick = () => resolveApproval(approval.id, 'DENIED');
  actions.append(approve, deny);
  card.append(actions);
  return card;
}

function renderTraceLine(kind, title, detail = '') {
  const line = element('div', `trace-line ${kind}`);
  line.append(element('span', '', title));
  if (detail) line.append(element('small', '', replaceEntityReferences(detail).slice(0, 260)));
  return line;
}

async function openChildSession(sessionId) {
  if (!sessionId) return false;
  let session = state.sessions.find(item => item.id === sessionId);
  if (!session) {
    try { session = await api(`/v1/sessions/${sessionId}`); }
    catch (error) {
      showNotice(`打开子会话失败：${error.message}`, true);
      return false;
    }
  }
  await selectSession(session.id, false);
  $('chatTitle').textContent = session.title || '子 Agent 会话';
  return true;
}

function routingComplexityLabel(value) {
  return ({SIMPLE: '简单', MEDIUM: '中等', COMPLEX: '复杂'})[value] || '中等';
}

function routingRiskLabel(value) {
  return ({LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险'})[value] || '中风险';
}

async function openCollaborationTaskSession(task, sessionId) {
  if (!task?.id || !sessionId) return;
  const origin = {
    taskId: task.id,
    taskTitle: task.title || '协作任务',
    sessionId,
    view: 'execution'
  };
  state.collaborationTaskReturn = origin;
  sessionStorage.setItem('paicli_collaboration_task_return', JSON.stringify(origin));
  syncCollaborationTaskReturnAction();
  if (!await openChildSession(sessionId)) clearCollaborationTaskReturn();
}

function syncCollaborationTaskReturnAction() {
  const button = $('returnCollaborationTask');
  if (!button) return;
  const origin = state.collaborationTaskReturn;
  const visible = Boolean(origin && origin.sessionId === state.sessionId);
  button.hidden = !visible;
  button.title = visible ? `返回协作任务：${origin.taskTitle}` : '';
}

function clearCollaborationTaskReturn() {
  state.collaborationTaskReturn = null;
  sessionStorage.removeItem('paicli_collaboration_task_return');
  syncCollaborationTaskReturnAction();
}

function returnToCollaborationTask() {
  const origin = state.collaborationTaskReturn;
  if (!origin) return;
  state.selectedCollaborationTaskId = origin.taskId;
  state.collaborationTaskView = origin.view;
  state.homeMode = 'tasks';
  localStorage.setItem('paicli_home_mode', 'tasks');
  localStorage.setItem('paicli_selected_task', origin.taskId);
  localStorage.setItem('paicli_task_view', origin.view);
  clearCollaborationTaskReturn();
  showHome();
}

function mountChildTrace(task) {
  if (!task) return;
  clearEvents();
  const header = {id: '', type: 'agent.mounted', data: JSON.stringify({
    agentName: task.agentName, childRunId: task.childRunId, childSessionId: task.childSessionId,
    status: task.status, currentStep: task.currentStep
  })};
  addEvent(header, parseData(header.data));
  (task.pendingApprovals || []).forEach(approval => addEvent({
    id: approval.id, type: 'agent.approval.pending', data: JSON.stringify(approval)
  }, approval));
  (task.toolCalls || []).forEach(call => addEvent({
    id: call.id, type: `agent.tool.${String(call.status || '').toLowerCase()}`, data: JSON.stringify(call)
  }, call));
  (task.events || []).forEach(event => addEvent({
    id: event.id, type: `agent.${event.type}`, data: event.data || '{}'
  }, parseData(event.data)));
  showNotice('已挂载子 Agent 执行链路');
}

function renderSessions() {
  $('sessions').replaceChildren();
  const linkedTaskSessions = new Set(state.collaborationTaskHistory.flatMap(item => item.linkedSessionIds || []));
  const entries = [
    ...state.sessions.filter(session => !linkedTaskSessions.has(session.id))
      .map(session => ({type: sessionHistoryType(session), projectKey: session.projectKey || 'default',
        groupId: session.groupId || null, updatedAt: session.updatedAt, session})),
    ...state.collaborationTaskHistory.map(history => ({type: 'task',
      projectKey: history.task.projectKey || 'default', groupId: null,
      updatedAt: history.task.updatedAt, history}))
  ];
  const projects = [...new Set(entries.map(entry => entry.projectKey))]
    .sort((left, right) => left === currentProjectKey() ? -1 : right === currentProjectKey() ? 1 : left.localeCompare(right));
  projects.forEach((projectKey, projectIndex) => {
    const projectEntries = entries.filter(entry => entry.projectKey === projectKey);
    const project = element('section', 'session-project');
    const projectHead = element('div', 'session-project-head');
    projectHead.append(element('strong', '', projectKey), element('span', '', String(projectEntries.length)));
    project.append(projectHead);
    const sections = [...state.groups.map(group => ({...group, removable: true})),
      {id: null, name: '未分组', removable: false}];
    sections.forEach(group => {
      const members = projectEntries.filter(entry => entry.groupId === (group.id || null))
        .sort((left, right) => new Date(right.updatedAt || 0) - new Date(left.updatedAt || 0));
      if (!members.length && !(group.removable && projectIndex === 0)) return;
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
      members.forEach(entry => section.append(entry.type === 'task'
        ? renderTaskHistoryItem(entry.history) : renderSessionItem(entry.session, entry.type)));
      project.append(section);
    });
    $('sessions').append(project);
  });
  if (!entries.length) $('sessions').append(element('div', 'nothing', '暂无历史记录'));
}

function sessionHistoryType(session) {
  return /^协作\s*[:：]/.test(session.title || '') ? 'expert' : 'chat';
}

function historyTypeLabel(type) {
  return type === 'task' ? '任务' : type === 'expert' ? '专家' : '普通';
}

function historyTitle(type, title) {
  if (type === 'expert') return String(title || '未命名协作').replace(/^协作\s*[:：]\s*/, '');
  return title || (type === 'task' ? '未命名任务' : '未命名对话');
}

function renderHistoryButton(type, title, meta) {
  const button = element('button', 'session');
  const line = element('span', 'session-title-line');
  line.append(element('span', `history-type history-type-${type}`, historyTypeLabel(type)),
    element('span', 'session-title-text', historyTitle(type, title)));
  button.append(line, element('small', 'meta', meta));
  return button;
}

function renderSessionItem(session, type = sessionHistoryType(session)) {
  const row = element('div', `session-row${session.id === state.sessionId ? ' active' : ''}`);
  const button = renderHistoryButton(type, session.title, '对话记录');
  button.onclick = () => openSessionHistory(session, type);
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

function openSessionHistory(session, type) {
  state.homeMode = type === 'expert' ? 'collaboration' : 'chat';
  localStorage.setItem('paicli_home_mode', state.homeMode);
  syncGlobalModeSwitch();
  selectSession(session.id);
}

function renderTaskHistoryItem(history) {
  const task = history.task;
  const active = !state.sessionId && state.homeMode === 'tasks' && task.id === state.selectedCollaborationTaskId;
  const row = element('div', `session-row${active ? ' active' : ''}`);
  const meta = `${collaborationStatusNames[task.status] || task.status} · ${collaborationAssigneeName(task)}`;
  const button = renderHistoryButton('task', task.title, meta);
  button.onclick = () => openCollaborationTaskHistory(task);
  const more = element('button', 'session-more', '⋯');
  more.title = '任务操作';
  const menu = element('div', 'session-menu history-task-menu');
  const open = element('button', 'secondary', '打开任务');
  open.onclick = () => openCollaborationTaskHistory(task);
  const remove = element('button', 'delete-session', '删除任务');
  remove.onclick = () => deleteCollaborationTask(task);
  menu.append(open, remove);
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

function openCollaborationTaskHistory(task, notice = '') {
  state.selectedCollaborationTaskId = task.id;
  state.collaborationTaskView = 'task';
  state.collaborationTaskNotice = notice;
  state.collaborationTaskSignature = '';
  state.homeMode = 'tasks';
  localStorage.setItem('paicli_home_mode', 'tasks');
  localStorage.setItem('paicli_selected_task', task.id);
  localStorage.setItem('paicli_task_view', 'task');
  showHome();
}

async function loadSidebarHistory() {
  [state.sessions, state.groups, state.collaborationTaskHistory] = await Promise.all([
    api('/v1/sessions'), api('/v1/session-groups'), api('/v1/collaboration/history?limit=1000')
  ]);
  renderSessions();
}

async function refreshSessions(selectFallback = true) {
  try {
    await loadSidebarHistory();
    if (!state.sessions.some(item => item.id === state.sessionId)) {
      state.sessionId = selectFallback ? state.sessions[0]?.id || '' : '';
    }
    if (state.sessionId) await selectSession(state.sessionId, false);
    else renderEmpty();
  } catch (error) {
    showNotice(`连接失败：${error.message}`, true);
  }
}

async function selectSession(id, rerender = true) {
  state.stream?.abort();
  configurePlanPolling(false);
  configureApprovalPolling(false);
  configureCollaborationTaskPolling(false);
  state.runId = '';
  state.planStatus = '';
  const previousSession = state.sessionId;
  if (previousSession) rememberMessageScroll(previousSession);
  if (previousSession && previousSession !== id && state.pendingAttachments.length) {
    await Promise.allSettled(state.pendingAttachments.map(attachment =>
      api(`/v1/sessions/${previousSession}/attachments/${attachment.id}`, {method: 'DELETE'})));
  }
  state.sessionId = id;
  syncCollaborationTaskReturnAction();
  state.live = null;
  clearPendingAttachments();
  localStorage.setItem('paicli_session', id);
  if (rerender) renderSessions();
  let session = state.sessions.find(item => item.id === id);
  if (!session) {
    try { session = await api(`/v1/sessions/${id}`); }
    catch (_) { session = null; }
  }
  $('chatTitle').textContent = session?.title || '对话';
  $('runMeta').textContent = session ? `项目 · ${session.projectKey}` : '尚未开始';
  $('input').value = localStorage.getItem(`paicli_draft_${id}`) || '';
  updateComposerVisibility();
  resizeInput();
  await refreshComposerOptions();
  setStatus();
  clearEvents();
  await syncLatestRun({watch: false});
  await loadMessages({restoreSaved: true, forceScroll: !savedMessageScroll(id)});
  if (state.runId && state.runStatus && !terminal.has(state.runStatus)) {
    state.eventCursors.delete(state.runId);
    watch(state.runId);
  }
  $('sidebar').classList.remove('open');
}

async function syncLatestRun(options = {}) {
  if (!state.sessionId) return null;
  const runs = await api(`/v1/sessions/${state.sessionId}/runs`);
  if (!runs.length) {
    state.runId = '';
    $('runMeta').textContent = '尚未开始';
    setStatus();
    await loadApprovals();
    return null;
  }
  const run = runs[0];
  const changed = state.runId !== run.id;
  state.runId = run.id;
  $('runMeta').textContent = runMetaLabel(run);
  setStatus(run.status);
  await loadApprovals();
  if (options.watch !== false && !terminal.has(run.status) && (changed || !state.stream)) watch(run.id);
  return run;
}

async function createSession(initialTitle = '新对话') {
  try {
    const currentGroup = state.sessions.find(item => item.id === state.sessionId)?.groupId || null;
    const session = await api('/v1/sessions', {
      method: 'POST',
      body: JSON.stringify({title: initialTitle, projectKey: 'default', groupId: currentGroup})
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
  configurePlanPolling(false);
  configureApprovalPolling(false);
  state.sessionId = '';
  state.runId = '';
  state.runStatus = '';
  state.planStatus = '';
  syncCollaborationTaskReturnAction();
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

async function deleteCollaborationTask(task) {
  if (!confirm(`删除协作任务“${task.title || '未命名任务'}”？任务、阶段、评论和协作动态会删除；已结束的执行、会话和交付文件会保留。`)) return;
  try {
    await api(`/v1/collaboration/tasks/${task.id}`, {method: 'DELETE'});
    if (state.selectedCollaborationTaskId === task.id) {
      state.selectedCollaborationTaskId = '';
      state.collaborationTaskNotice = '';
      state.collaborationTaskSignature = '';
      localStorage.removeItem('paicli_selected_task');
      localStorage.removeItem('paicli_task_view');
    }
    await loadSidebarHistory();
    if (state.homeMode === 'tasks' && $('collaborationTaskWorkspace')) {
      await refreshCollaborationTaskWorkspace();
    }
    showNotice('协作任务已删除');
  } catch (error) {
    const message = error.status === 409
      ? '任务仍有执行中的 Run。请先使用“取消任务”，待执行停止后再删除协作任务记录。'
      : `删除任务失败：${error.message}`;
    state.collaborationTaskNotice = message;
    state.collaborationTaskSignature = '';
    if (state.selectedCollaborationTaskId === task.id && state.homeMode === 'tasks'
        && !state.sessionId && $('collaborationTaskDetail')) {
      await renderCollaborationTaskDetail();
    } else {
      openCollaborationTaskHistory(task, message);
    }
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
  if (planInProgress()) return showNotice('当前计划仍在执行，请等待计划结束后再发起新任务', true);
  if (state.runStatus && !terminal.has(state.runStatus)) {
    return showNotice('当前任务仍在运行', true);
  }
  if (shouldUsePlan(text)) return createPlanFromComposer(text, true);
  try {
    if (!state.sessionId) await createSession(conciseTaskName(text, '新对话'));
    const run = await api(`/v1/sessions/${state.sessionId}/runs`, {
      method: 'POST',
      body: JSON.stringify({
        input: text,
        thinkingMode: state.thinkingMode,
        reasoningEffort: reasoningEffortForRequest(),
        attachmentIds: state.pendingAttachments.map(item => item.id),
        modelProfileId: state.modelProfileId || null,
        agentProfileId: state.agentProfileId || null,
        executionShell: state.executionShell
      })
    });
    applySessionTaskTitle(text);
    $('input').value = '';
    localStorage.removeItem(`paicli_draft_${state.sessionId}`);
    clearPendingAttachments();
    resizeInput();
    state.runId = run.id;
    $('runMeta').textContent = runMetaLabel(run);
    setStatus(run.status);
    await loadMessages({forceScroll: true});
    clearEvents();
    watch(run.id);
  } catch (error) {
    showNotice(error.message, true);
  }
}

function shouldUsePlan(text) {
  if (/(按计划执行|创建计划|指定计划|持久化计划|plan\s*:|\/plan|用plan|转为计划)/i.test(text)) return true;
  return false;
}

function conciseTaskName(value, fallback = '未命名任务', maxLength = 36) {
  const source = String(value || '').replaceAll('\r', '\n');
  let title = source.split('\n').map(line => line.trim())
    .find(line => line.length) || '';
  title = title
    .replace(/^(?:#{1,6}\s*|[-*+]\s+|\d+[.)、]\s*)+/, '')
    .replace(/^(?:(?:用户|协作|计划)?(?:任务|目标|请求|需求)|用户目标|任务目标)\s*[:：]\s*/i, '')
    .replace(/^(?:请帮我|麻烦帮我|帮我|请)\s*/i, '')
    .replace(/[`*_~]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  const sentence = title.match(/^(.{8,}?)[。！？!?；;]/u);
  if (sentence) title = sentence[1].trim();
  if (!title) title = fallback;
  const characters = Array.from(title);
  return characters.length <= maxLength ? title : `${characters.slice(0, maxLength - 1).join('').trim()}…`;
}

function applySessionTaskTitle(task) {
  const session = state.sessions.find(item => item.id === state.sessionId);
  if (!session || !/^(?:新对话|未命名对话|new session|new conversation|untitled)$/i.test((session.title || '').trim())) return;
  session.title = conciseTaskName(task, session.title);
  $('chatTitle').textContent = session.title;
  renderSessions();
}

async function createPlanFromComposer(text = null, autoStart = true) {
  const objective = (text || $('input').value).trim();
  if (!objective) return showNotice('请输入要执行的计划目标', true);
  if (planInProgress()) return showNotice('当前计划仍在执行', true);
  if (state.runStatus && !terminal.has(state.runStatus)) return showNotice('当前任务仍在运行', true);
  try {
    if (!state.sessionId) await createSession(conciseTaskName(objective, '新计划'));
    showNotice('正在创建持久化 Plan');
    const view = await api('/v1/plans/generate', {
      method: 'POST',
      body: JSON.stringify({sessionId: state.sessionId, projectKey: currentProjectKey(), objective})
    });
    applySessionTaskTitle(objective);
    $('input').value = '';
    localStorage.removeItem(`paicli_draft_${state.sessionId}`);
    clearPendingAttachments();
    resizeInput();
    if (autoStart) {
      const started = await api(`/v1/plans/${view.plan.id}/start`, {method: 'POST', body: '{}'});
      const activeSteps = (started.steps || []).filter(step =>
        ['RUNNING', 'WAITING_JOB', 'VALIDATING', 'COMPLETED'].includes(step.status)).length;
      showNotice(activeSteps
        ? `Plan 已创建并启动：首批 ${activeSteps} 步已进入执行`
        : 'Plan 已创建并启动：调度器正在领取首批步骤');
    } else {
      showNotice('Plan 已创建');
    }
    await loadMessages({forceScroll: true});
    await syncLatestRun();
    await loadPlans();
  } catch (error) {
    showNotice(`Plan 创建失败：${error.message}`, true);
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

async function addGithubMcp() {
  const tokenEnv = prompt('GitHub MCP Token 环境变量引用', 'env:GITHUB_MCP_TOKEN');
  if (!tokenEnv) return;
  await saveMcp({
    name: 'github',
    url: 'https://api.githubcopilot.com/mcp/',
    enabled: true,
    headers: {Authorization: tokenEnv}
  });
  showNotice('GitHub MCP 已保存；请确认 Server 环境里存在对应 Token，然后点击测试');
  await refreshCapabilities();
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

const workbenchBatchConfig = {
  runs: {deleteButton: 'deleteSelectedRuns', selectButton: 'selectAllRuns', noun: 'Run'},
  memories: {deleteButton: 'deleteSelectedMemories', selectButton: 'selectAllMemories', noun: 'Memory'},
  artifacts: {deleteButton: 'deleteSelectedArtifacts', selectButton: 'selectAllArtifacts', noun: 'Artifact'},
  policies: {deleteButton: 'deleteSelectedPolicies', selectButton: 'selectAllPolicies', noun: '审批策略'}
};
const workbenchBatchSelections = Object.fromEntries(Object.keys(workbenchBatchConfig)
  .map(kind => [kind, new Set()]));
const workbenchBatchVisible = Object.fromEntries(Object.keys(workbenchBatchConfig)
  .map(kind => [kind, new Set()]));

function setWorkbenchBatchVisible(kind, ids) {
  const visible = new Set(ids);
  workbenchBatchVisible[kind] = visible;
  [...workbenchBatchSelections[kind]].forEach(id => {
    if (!visible.has(id)) workbenchBatchSelections[kind].delete(id);
  });
  syncWorkbenchBatchControls(kind);
}

function selectableWorkbenchItem(item, kind, id, selectable = true) {
  const checkbox = element('input', 'workbench-select');
  checkbox.type = 'checkbox';
  checkbox.checked = workbenchBatchSelections[kind].has(id);
  checkbox.disabled = !selectable;
  checkbox.setAttribute('aria-label', selectable ? `选择此 ${workbenchBatchConfig[kind].noun}` : '运行中的 Run 不可删除');
  checkbox.title = selectable ? '选择后可批量永久删除' : '仅终态 Run 可以永久删除';
  checkbox.onclick = event => event.stopPropagation();
  checkbox.onchange = () => {
    if (checkbox.checked) workbenchBatchSelections[kind].add(id);
    else workbenchBatchSelections[kind].delete(id);
    syncWorkbenchBatchControls(kind);
  };
  item.prepend(checkbox);
  return item;
}

function syncWorkbenchBatchControls(kind) {
  const config = workbenchBatchConfig[kind];
  const count = workbenchBatchSelections[kind].size;
  const visible = workbenchBatchVisible[kind];
  const deleteButton = $(config.deleteButton);
  const selectButton = $(config.selectButton);
  if (deleteButton) {
    deleteButton.disabled = count === 0;
    deleteButton.textContent = count ? `永久删除已选 (${count})` : '永久删除已选';
  }
  if (selectButton) {
    selectButton.disabled = visible.size === 0;
    selectButton.textContent = visible.size > 0 && [...visible].every(id => workbenchBatchSelections[kind].has(id))
      ? '清空选择' : kind === 'runs' ? '全选终态' : '全选';
  }
}

function toggleWorkbenchBatchSelection(kind, event) {
  event?.stopPropagation();
  const selected = workbenchBatchSelections[kind];
  const visible = workbenchBatchVisible[kind];
  const allSelected = visible.size > 0 && [...visible].every(id => selected.has(id));
  visible.forEach(id => allSelected ? selected.delete(id) : selected.add(id));
  const container = kind === 'runs' ? $('queueList')
    : kind === 'memories' ? $('memoryList') : kind === 'artifacts' ? $('artifactList') : $('policyList');
  container?.querySelectorAll('.workbench-select:not(:disabled)').forEach(checkbox => {
    checkbox.checked = !allSelected;
  });
  syncWorkbenchBatchControls(kind);
}

async function deleteWorkbenchSelection(kind, event) {
  event?.stopPropagation();
  const config = workbenchBatchConfig[kind];
  const ids = [...workbenchBatchSelections[kind]];
  if (!ids.length) return;
  if (!confirm(`永久删除已选的 ${ids.length} 个 ${config.noun}？数据库记录和关联内容会被真实删除，此操作不可恢复。`)) return;
  const button = $(config.deleteButton);
  button.disabled = true;
  try {
    if (kind === 'runs') {
      await api('/v1/productivity/queue/batch', {
        method: 'POST', body: JSON.stringify({runIds: ids, action: 'DELETE'})
      });
      workbenchBatchSelections[kind].clear();
      await loadProductivityData();
      if (ids.includes(state.runId)) {
        await syncLatestRun({watch: false});
        await loadMessages();
      }
    } else {
      const endpoint = kind === 'memories' ? '/v1/memories/batch-delete'
        : kind === 'artifacts' ? '/v1/artifacts/batch-delete' : '/v1/approvals/policies/batch-delete';
      await api(endpoint, {method: 'POST', body: JSON.stringify({ids})});
      workbenchBatchSelections[kind].clear();
      if (kind === 'memories') await loadManagedMemories();
      else if (kind === 'artifacts') await loadArtifacts();
      else await loadApprovalPolicies();
    }
    showNotice(`已从数据库永久删除 ${ids.length} 个 ${config.noun}`);
  } catch (error) {
    showNotice(`批量删除失败：${error.message}`, true);
    syncWorkbenchBatchControls(kind);
  }
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
  const shouldFollow = messagesNearBottom();
  liveAssistant().text.textContent += state.pendingText;
  state.pendingText = '';
  if (shouldFollow) scrollBottom();
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
  if (type === 'model.tool_calls') {
    const names = (data.calls || []).map(call => `${call.name} (${call.toolCallId})`).join('、');
    return `模型请求 ${data.count || 0} 个工具${names ? `：${names}` : ''}`;
  }
  if (type === 'context.prepared') {
    return `上下文已组装 · 原始估算 ${data.rawEstimatedInputTokens ?? data.estimatedInputTokens ?? 0}`
      + ` · 校准估算 ${data.estimatedInputTokens ?? 0}`;
  }
  if (type === 'memory.extracted') {
    const ids = (data.items || []).map(item => item.memoryId).join('、');
    return `Memory 已提取 ${data.count || 0} 条${ids ? `：${ids}` : ''}`;
  }
  if (type === 'tool.requested') return `请求工具：${toolDisplayName(data.name)}`;
  if (type === 'tool.started') {
    return data.shell
      ? `开始执行 · ${data.shell} · ${data.cwd || '.'}`
      : `开始执行：${toolDisplayName(data.name)}`;
  }
  if (type === 'tool.completed') {
    const execution = data.shell ? ` · ${data.shell} · exit ${data.exitCode ?? '-'}` : '';
    const artifact = data.externalized
      ? (data.outputTruncated ? ' · 截断输出已保存为 Artifact' : ' · 完整输出已保存为 Artifact')
      : '';
    return `工具完成 · ${data.durationMs || 0}ms${execution}${artifact}`;
  }
  if (type === 'tool.failed') {
    const execution = data.shell ? ` · ${data.shell}${data.timedOut ? ' · 已超时' : ''}` : '';
    return `工具失败${execution}：${data.error || ''}`;
  }
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
  top.append(element('span', '', event.type));
  item.append(top, element('div', 'summary', replaceEntityReferences(eventSummary(event.type, data))));
  const details = element('details');
  details.append(
    element('summary', 'hint', '原始数据'),
    element('pre', 'raw', JSON.stringify(data, null, 2))
  );
  item.append(details);
  if (event.type === 'context.prepared' && data.fieldGroups) {
    const labels = {
      actualModelContext: '实际进入模型 Context',
      serverEnforced: '服务端强制',
      auditOnly: '仅审计记录'
    };
    const groups = element('div', 'context-field-groups');
    Object.entries(data.fieldGroups).forEach(([key, fields]) => {
      groups.append(element('small', 'hint', `${labels[key] || key}：${(fields || []).join('、')}`));
    });
    item.append(groups);
  }
  container.append(item);
  while (container.children.length > 160) container.firstElementChild.remove();
  if (followTail) container.scrollTop = container.scrollHeight;
}

function updateReasoningActivity(event, data) {
  state.reasoningChars += (data.content || '').length;
  if (!state.reasoningActivity) {
    const item = element('div', 'event');
    const top = element('div', 'event-top');
    top.append(element('span', '', 'model.reasoning.delta'));
    const summary = element('div', 'summary', '正在接收推理内容');
    item.append(top, summary);
    $('events').querySelector('.nothing')?.remove();
    $('events').append(item);
    state.reasoningActivity = {item, summary};
  }
  state.reasoningActivity.summary.textContent = `推理流已合并 · ${state.reasoningChars} 字符`;
}

async function handleEvent(event, runId) {
  if (state.runId !== runId) return true;
  const data = parseData(event.data);
  if (event.type === 'model.delta') {
    queueLiveText(data.content);
  } else if (event.type === 'model.reasoning.delta') {
    updateReasoningActivity(event, data);
  } else if (event.type === 'run.status_changed') {
    addEvent(event, data);
    setStatus(data.status);
    if (data.status === 'WAITING_AGENT' || data.status === 'WAITING_APPROVAL') await loadApprovals();
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
    if (!planInProgress()) showNotice('回答完成');
    return true;
  } else if (event.type === 'run.failed') {
    addEvent(event, data);
    setStatus('FAILED');
    finishLive();
    await loadMessages();
    if (!planInProgress()) showNotice(data.error || '任务失败', true);
    return true;
  } else if (event.type === 'run.canceled') {
    addEvent(event, data);
    setStatus('CANCELED');
    finishLive();
    await loadMessages();
    if (!planInProgress()) showNotice('任务已取消');
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
  let cursor = state.eventCursors.get(runId);
  if (cursor === undefined) {
    try {
      cursor = await primeRunEventCursor(runId, controller);
    } catch (error) {
      if (error.name === 'AbortError' || controller.signal.aborted || state.runId !== runId) return;
      streamError = error;
      cursor = 0;
    }
  }
  if (controller.signal.aborted || state.runId !== runId) return;
  const dispatchFrame = async frame => {
    const event = {type: 'message', data: '', id: 0};
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('id:')) event.id = Number(line.slice(3));
      else if (line.startsWith('event:')) event.type = line.slice(6).trim();
      else if (line.startsWith('data:')) event.data += line.slice(5).trim();
    }
    if (event.id) {
      cursor = Math.max(cursor || 0, event.id);
      state.eventCursors.set(runId, cursor);
    }
    if (event.data && await handleEvent(event, runId)) terminalSeen = true;
  };
  try {
    const response = await fetch(`/v1/runs/${runId}/events?after=${cursor || 0}`, {
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
      if (run.status === 'WAITING_AGENT' || run.status === 'WAITING_APPROVAL') await loadApprovals();
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

async function primeRunEventCursor(runId, controller) {
  let cursor = 0;
  let page = [];
  const recent = [];
  do {
    page = await api(`/v1/runs/${runId}/timeline?after=${cursor}&limit=500`);
    if (controller.signal.aborted || state.runId !== runId) {
      throw new DOMException('Run view changed', 'AbortError');
    }
    for (const event of page) {
      cursor = Math.max(cursor, Number(event.id || 0));
      if (!['model.delta', 'model.reasoning.delta'].includes(event.type)) {
        recent.push(event);
        if (recent.length > 160) recent.shift();
      }
    }
  } while (page.length === 500);
  for (const event of recent) addEvent(event, parseData(event.data));
  state.eventCursors.set(runId, cursor);
  return cursor;
}

async function retryRun(branch) {
  if (!state.runId || !terminal.has(state.runStatus)) return;
  if (branch) return branchRun();
  try {
    const result = await api(`/v1/runs/${state.runId}/retry`, {
      method: 'POST', body: JSON.stringify({
        branch,
        modelProfileId: state.modelProfileId || null,
        agentProfileId: state.agentProfileId || null,
        executionShell: state.executionShell
      })
    });
    state.runId = result.run.id;
    $('runMeta').textContent = runMetaLabel(result.run);
    setStatus(result.run.status);
    await loadMessages({forceScroll: true});
    watch(result.run.id);
    showNotice('已重新执行');
  } catch (error) { showNotice(`操作失败：${error.message}`, true); }
}

async function branchRun() {
  try {
    const session = await api(`/v1/runs/${state.runId}/branch`, {method: 'POST', body: '{}'});
    await refreshSessions();
    await selectSession(session.id);
    showNotice('已创建分支，等待你的下一条消息');
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
  fillSelect($('evaluationAgentTeam'), state.agentTeams.filter(value => value.enabled)
    .map(value => ({id: value.id, label: `团队评测 · ${value.name}`})), '单 Agent 基线');
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
      const enabledCaseCount = cases.filter(value => value.enabled).length;
      const summary = `${enabledCaseCount}/${cases.length} 个启用用例 · 默认 ${suite.defaultTrials} Trial · ${suite.passThreshold} 分通过${latest ? ` · 最近 ${latest.status}${latest.averageScore == null ? '' : ` ${latest.averageScore.toFixed(1)} 分`}` : ''}`;
      const item = workbenchItem(suite.name, `${suite.description || '未填写说明'} · ${summary}`);
      actionButton(item, '新建用例', () => openEvaluationCaseDialog(suite), true);
      actionButton(item, enabledCaseCount ? '运行' : '先启用用例', () => startEvaluation(suite, cases, block));
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
          const repositorySummary = value.caseType === 'REPOSITORY' ? ` · 仓库任务 · fixture ${value.fixtureRef || '未配置'}` : '';
          const child = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`,
            `${value.prompt}${repositorySummary} · ${ruleCount} 条内容规则${value.maxToolCalls ? ` · ≤${value.maxToolCalls} 工具` : ''}${value.maxTokens ? ` · ≤${value.maxTokens} 输出 Token` : ''}`);
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
  $('evaluationSuiteDatasetVersion').value = 'custom-v1';
  setFormError('evaluationSuiteError'); $('evaluationSuiteDialog').showModal(); $('evaluationSuiteName').focus();
}

async function submitEvaluationSuite(event) {
  event.preventDefault(); setFormError('evaluationSuiteError'); $('saveEvaluationSuite').disabled = true;
  try {
    await api('/v1/evaluations/suites', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(), name: $('evaluationSuiteName').value.trim(),
      description: $('evaluationSuiteDescription').value.trim(),
      defaultTrials: +$('evaluationSuiteTrials').value, passThreshold: +$('evaluationSuiteThreshold').value,
      datasetVersion: $('evaluationSuiteDatasetVersion').value.trim() || 'custom-v1'
    })});
    $('evaluationSuiteDialog').close(); await loadEvaluations(); showNotice('评测套件已创建');
  } catch (error) { setFormError('evaluationSuiteError', error.message); }
  finally { $('saveEvaluationSuite').disabled = false; }
}

function openEvaluationCaseDialog(suite) {
  $('evaluationCaseForm').reset(); $('evaluationCaseForm').dataset.suiteId = suite.id;
  $('evaluationCaseSuite').textContent = `所属套件：${suite.name}`; $('evaluationCaseEnabled').checked = true;
  ['evaluationMaxTools', 'evaluationMaxTokens', 'evaluationMaxDuration'].forEach(id => { $(id).value = '0'; });
  $('evaluationCaseType').value = 'RULE'; $('evaluationGraderShell').value = 'bash';
  $('evaluationGraderTimeout').value = '90'; $('evaluationHiddenFiles').value = '[]';
  $('evaluationPatchMaxFiles').value = '40'; $('evaluationPatchMaxBytes').value = '2000000';
  $('evaluationAssertions').value = '{}'; $('evaluationFixtureSpec').value = '{}';
  $('evaluationJudgeSpec').value = '{}';
  $('evaluationFixtureInspection').textContent = '';
  toggleRepositoryEvaluationFields();
  setFormError('evaluationCaseError'); $('evaluationCaseDialog').showModal(); $('evaluationCaseName').focus();
}

function evaluationRuleList(id) {
  return $(id).value.split(/[\n,，]+/).map(value => value.trim()).filter(Boolean);
}

function toggleRepositoryEvaluationFields() {
  const repository = $('evaluationCaseType').value === 'REPOSITORY';
  document.querySelectorAll('[data-repository-field]').forEach(node => { node.hidden = !repository; });
}

function evaluationJson(id, fallback) {
  const text = $(id).value.trim();
  if (!text) return fallback;
  return JSON.parse(text);
}

async function inspectEvaluationFixture() {
  const fixtureRef = $('evaluationFixtureRef').value.trim();
  if (!fixtureRef) return setFormError('evaluationCaseError', '请先填写 Fixture 引用');
  try {
    const result = await api(`/v1/evaluations/fixtures/inspect?fixtureRef=${encodeURIComponent(fixtureRef)}`);
    $('evaluationFixtureSha').value = result.sha256;
    $('evaluationFixtureInspection').textContent = `${result.fileCount} 个文件 · ${result.bytes} bytes · ${result.sha256}`;
    setFormError('evaluationCaseError');
  } catch (error) { setFormError('evaluationCaseError', error.message); }
}

async function submitEvaluationCase(event) {
  event.preventDefault(); setFormError('evaluationCaseError'); $('saveEvaluationCase').disabled = true;
  try {
    const suiteId = $('evaluationCaseForm').dataset.suiteId;
    const caseType = $('evaluationCaseType').value;
    const repository = caseType === 'REPOSITORY';
    const hiddenFiles = repository ? evaluationJson('evaluationHiddenFiles', []) : [];
    if (!Array.isArray(hiddenFiles)) throw new Error('隐藏文件映射必须是 JSON 数组');
    const assertions = evaluationJson('evaluationAssertions', {});
    const fixture = evaluationJson('evaluationFixtureSpec', {});
    const judge = evaluationJson('evaluationJudgeSpec', {});
    if ([assertions, fixture, judge].some(value => !value || Array.isArray(value) || typeof value !== 'object')) {
      throw new Error('断言、夹具和 Judge 配置必须是 JSON 对象');
    }
    await api(`/v1/evaluations/suites/${suiteId}/cases`, {method: 'POST', body: JSON.stringify({
      name: $('evaluationCaseName').value.trim(), prompt: $('evaluationCasePrompt').value.trim(),
      requiredTools: evaluationRuleList('evaluationRequiredTools'), forbiddenTools: evaluationRuleList('evaluationForbiddenTools'),
      requiredResponse: evaluationRuleList('evaluationRequiredResponse'), forbiddenResponse: evaluationRuleList('evaluationForbiddenResponse'),
      maxToolCalls: +$('evaluationMaxTools').value, maxTokens: +$('evaluationMaxTokens').value,
      maxDurationMs: +$('evaluationMaxDuration').value, enabled: $('evaluationCaseEnabled').checked,
      caseType,
      fixtureRef: repository ? $('evaluationFixtureRef').value.trim() : null,
      fixtureSha256: repository ? $('evaluationFixtureSha').value.trim() : null,
      grader: repository ? {
        shell: $('evaluationGraderShell').value,
        failToPassCommand: $('evaluationFailToPass').value.trim(),
        passToPassCommand: $('evaluationPassToPass').value.trim(),
        timeoutSeconds: +$('evaluationGraderTimeout').value,
        hiddenFiles
      } : {},
      patchPolicy: repository ? {
        maxChangedFiles: +$('evaluationPatchMaxFiles').value,
        maxPatchBytes: +$('evaluationPatchMaxBytes').value,
        forbiddenPaths: evaluationRuleList('evaluationForbiddenPaths')
      } : {}, assertions, fixture, judge
    })});
    $('evaluationCaseDialog').close(); await loadEvaluations(); showNotice('评测用例已创建');
  } catch (error) { setFormError('evaluationCaseError', error.message); }
  finally { $('saveEvaluationCase').disabled = false; }
}

async function startEvaluation(suite, cases = [], block = null) {
  if (!cases.some(value => value.enabled)) {
    const caseList = block?.querySelector('.evaluation-case-list');
    if (caseList) caseList.open = true;
    showNotice('该套件没有启用用例。请先展开用例并启用已满足前置条件的案例。', true);
    return;
  }
  const teamId = $('evaluationAgentTeam')?.value || '';
  const team = state.agentTeams.find(value => value.id === teamId);
  if (!confirm(`运行“${suite.name}”？每个启用用例将执行 ${suite.defaultTrials} 次${team ? `，执行者为团队“${team.name}”` : ''}。`)) return;
  try {
    const body = state.modelProfileId ? {modelProfileId: state.modelProfileId} : {};
    if (teamId) body.agentTeamId = teamId;
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
    const team = state.agentTeams.find(value => value.id === execution.agentTeamId);
    header.append(element('strong', '', `${report.suite.name} · ${score}`),
      element('small', '', `${execution.status} · ${execution.trialCount} Trial/用例 · ${execution.passThreshold} 分通过 · Gate ${execution.gateStatus || 'PENDING'}${team ? ` · 团队 ${team.name}` : ''}`));
    let fingerprint = {};
    try {
        fingerprint = execution.fingerprintJson ? JSON.parse(execution.fingerprintJson) : {};
    } catch (_error) {
        fingerprint = {comparisonKey: 'invalid legacy fingerprint'};
    }
    if (fingerprint.comparisonKey) header.append(element('small', '',
      `数据集 ${report.suite.datasetVersion || 'custom-v1'} · Grader ${fingerprint.graderVersion || '-'} · 指纹 ${fingerprint.comparisonKey.slice(0, 12)}`));
    if ((report.summary?.repositoryTrials || 0) > 0) header.append(element('small', '',
      `仓库任务 ${report.summary.resolvedTrials}/${report.summary.repositoryTrials} resolved · 稳定通过 ${report.summary.stableCases} Case · 每 resolved ${report.summary.tokensPerResolved || 0} Token`));
    const refresh = element('button', 'secondary', '刷新报告'); refresh.onclick = () => loadEvaluationReport(executionId); header.append(refresh);
    if (execution.status === 'COMPLETED') {
      const gate = element('button', 'secondary', '执行发布门禁');
      gate.onclick = async () => {
        const result = await api(`/v1/evaluations/executions/${executionId}/release-gate`, {method: 'POST'});
        showNotice(result.passed ? '发布门禁通过' : `发布门禁失败：${result.blockers.join('；')}`, !result.passed);
        await loadEvaluationReport(executionId, false);
      };
      header.append(gate);
    }
    const nodes = [header];
    report.trials.forEach(value => {
      const trial = value.trial; const details = value.details || {};
      const status = trial.score == null ? '运行中' : `${trial.score} 分 · ${trial.passed ? '通过' : '未通过'}`;
      const item = element('div', `evaluation-trial ${trial.score == null ? '' : trial.passed ? 'pass' : 'fail'}`);
      item.append(element('strong', '', `${value.caseName} · Trial ${trial.ordinal} · ${status}`));
      if (details.toolCalls != null) item.append(element('div', 'evaluation-checks',
        `${details.toolCalls} 次工具调用 · ${details.outputTokens ?? details.tokens ?? 0} 输出 Token · ${details.totalTokens ?? details.tokens ?? 0} 总 Token · ${details.durationMs || 0} ms`));
      const failures = (details.checks || []).filter(check => !check.passed);
      if (failures.length) item.append(element('div', 'evaluation-checks', failures.map(check => `${check.rule}：${check.evidence}（-${check.deduction}）`).join('\n')));
      if (details.caseType === 'REPOSITORY') {
        const repository = details.repository || {};
        const f2p = repository.failToPass || {};
        const p2p = repository.passToPass || {};
        item.append(element('div', 'evaluation-checks',
          `resolved=${details.resolved} · integrity=${details.integrityPassed} · security=${details.securityPassed} · budget=${details.budgetPassed}\n` +
          `F2P ${f2p.passed ? 'PASS' : f2p.executed ? 'FAIL' : 'NOT RUN'} · P2P ${p2p.passed ? 'PASS' : p2p.executed ? 'FAIL' : 'NOT RUN'}\n` +
          `修改 ${(repository.changedFiles || []).length} 文件 / ${repository.changedBytes || 0} bytes${repository.error ? `\n${repository.error}` : ''}`));
        if ((repository.changedFiles || []).length) {
          item.append(element('pre', 'evaluation-checks', repository.changedFiles.join('\n')));
        }
      }
      if (details.judge?.enabled) item.append(element('div', 'evaluation-checks',
        `Judge ${details.judge.verdict} · ${details.judge.score} 分 · 校准 ${details.judge.calibrationId} / agreement ${details.judge.calibrationAgreement}\n${details.judge.reason || ''}`));
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
    [state.templates, state.modelProfiles, state.agentProfiles, state.agentTeams] = await Promise.all([
      api(`/v1/productivity/templates?projectKey=${project}`),
      api(`/v1/productivity/model-profiles?projectKey=${project}`),
      api(`/v1/productivity/agent-profiles?projectKey=${project}`),
      api(`/v1/productivity/agent-teams?projectKey=${project}`)
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
      const option = element('option', '',
        `${value.defaultProfile ? '★ ' : ''}${modelProvider(value)} · ${value.name}`);
      option.value = value.id; profile.append(option);
    });
    if (state.modelProfiles.some(value => value.id === state.modelProfileId)) profile.value = state.modelProfileId;
    else {
      state.modelProfileId = '';
      localStorage.removeItem('paicli_model_profile');
    }
    const agent = $('agentProfile');
    agent.replaceChildren(element('option', '', '默认专家'));
    agent.firstElementChild.value = '';
    state.agentProfiles.filter(value => value.enabled).forEach(value => {
      const option = element('option', '', `${value.collaborationRole || 'EXPERT'} · ${value.name}`);
      option.value = value.id; agent.append(option);
    });
    if (state.agentProfiles.some(value => value.id === state.agentProfileId && value.enabled)) agent.value = state.agentProfileId;
    else state.agentProfileId = '';
    renderModelControls();
    renderSessions();
    scheduleEstimate();
    if (!state.sessionId) renderEmpty();
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
    const [usage, templates, profiles, agents, teams, skills, queue, schedules, notifications] = await Promise.all([
      api(`/v1/productivity/usage?projectKey=${project}&days=30`),
      api(`/v1/productivity/templates?projectKey=${project}`),
      api(`/v1/productivity/model-profiles?projectKey=${project}`),
      api(`/v1/productivity/agent-profiles?projectKey=${project}`),
      api(`/v1/productivity/agent-teams?projectKey=${project}`),
      api(`/v1/skills?projectKey=${project}`),
      api(`/v1/productivity/queue?projectKey=${project}`),
      api(`/v1/productivity/schedules?projectKey=${project}`),
      api(`/v1/productivity/notifications?projectKey=${project}`)
    ]);
    state.templates = templates; state.modelProfiles = profiles; state.agentProfiles = agents;
    state.agentTeams = teams; state.availableSkills = skills;
    renderUsage(usage); renderTemplates(templates); renderAgentStudio(agents); renderAgentTeams(teams);
    renderProfiles(profiles); renderQueue(queue);
    renderSchedules(schedules, templates); renderNotifications(notifications);
  } catch (error) { showNotice(`效率工作台加载失败：${error.message}`, true); }
}

function renderUsage(value) {
  const budget = value.budget;
  const tokens = value.inputTokens + value.outputTokens;
  const labels = [
    ['调用', value.calls.toLocaleString()], ['Token', tokens.toLocaleString()],
    ['缓存命中', value.cachedTokens.toLocaleString()],
    ['Cache Hit', `${(value.cacheHitRatio * 100).toFixed(1)}%`],
    ['Reusable Prefix', `${(value.reusablePrefixRatio * 100).toFixed(1)}%`],
    ['平均 Uncached Input', Math.round(value.averageUncachedInputTokens).toLocaleString()],
    ['TTFT', `${Math.round(value.averageTtftMs)} ms`],
    ['平均响应', `${Math.round(value.averageDurationMs)} ms`],
    ['失败率', `${(value.failureRate * 100).toFixed(1)}%`], ['重试', value.retries.toLocaleString()],
    ['估算成本', `$${value.estimatedCost.toFixed(4)}`],
    ['Cost / Success', `$${value.tokenCostPerSuccessfulRun.toFixed(4)}`]
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
      value.thinkingMode ? `思考 ${value.thinkingMode === 'enabled' ? (value.reasoningEffort || 'high') : '关闭'}` : '思考跟随对话',
      tools.length ? `工具 ${tools.slice(0, 8).map(toolDisplayName).join('、')}` : '工具不限制'
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

function renderAgentTeams(values = state.agentTeams) {
  const list = $('agentTeamList');
  if (!list) return;
  list.replaceChildren(...values.map(team => {
    const leader = state.agentProfiles.find(value => value.id === team.leaderAgentProfileId);
    const members = parseStringListJson(team.memberAgentProfileIdsJson)
      .map(id => agentProfileName(id));
    const item = workbenchItem(
      `${team.enabled ? '●' : '○'} ${team.name}`,
      `${leader ? `Leader ${leader.name}` : 'Leader 已失效'} · ${members.length ? members.join('、') : '暂无成员'} · 最多 ${team.maxExperts} 人`
    );
    if (team.enabled) actionButton(item, '用于协作', () => useAgentTeam(team), true);
    actionButton(item, '团队指标', () => showAgentTeamMetrics(team));
    actionButton(item, '编辑', () => openAgentTeamDialog(team));
    actionButton(item, '删除', async () => {
      if (!confirm(`删除执行小队“${team.name}”？`)) return;
      await api(`/v1/productivity/agent-teams/${team.id}`, {method: 'DELETE'});
      if (state.selectedAgentTeamId === team.id) state.selectedAgentTeamId = '';
      await refreshAgentStudio();
    });
    return item;
  }));
  if (!values.length) list.append(element('div', 'hint', '暂无执行小队；新建后可在专家协作首页一键套用。'));
}

async function showAgentTeamMetrics(team) {
  try {
    const metrics = await api(`/v1/collaboration/teams/${team.id}/metrics`);
    showNotice(`${team.name}\n任务 ${metrics.totalTasks} · 完成率 ${(metrics.taskCompletionRate * 100).toFixed(1)}% · 阻塞 ${metrics.blockedTasks}\nRun ${metrics.totalRuns} · 成功率 ${(metrics.runSuccessRate * 100).toFixed(1)}% · 委派 ${metrics.delegatedRuns} · 人工介入 ${metrics.humanInterventions}`);
  } catch (error) { showNotice(`团队指标加载失败：${error.message}`, true); }
}

function useAgentTeam(team) {
  state.selectedAgentTeamId = team.id;
  state.homeMode = 'collaboration';
  localStorage.setItem('paicli_home_mode', 'collaboration');
  $('agentStudioDialog').close();
  showHome();
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
  const [profiles, agents, skills, teams] = await Promise.all([
    api(`/v1/productivity/model-profiles?projectKey=${project}`),
    api(`/v1/productivity/agent-profiles?projectKey=${project}`),
    api(`/v1/skills?projectKey=${project}`),
    api(`/v1/productivity/agent-teams?projectKey=${project}`)
  ]);
  state.modelProfiles = profiles;
  state.agentProfiles = agents;
  state.availableSkills = skills;
  state.agentTeams = teams;
  renderAgentStudio(agents);
  renderAgentTeams(teams);
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
  const allowedAgentNames = allowedAgentProfileIds.map(id => agentProfileName(id));
  const selectedTeam = state.agentTeams.find(value =>
    value.id === state.selectedAgentTeamId && value.enabled);
  const complexity = $('homeCollaborationComplexity').value;
  const risk = $('homeCollaborationRisk').value;
  const maxExperts = Math.max(1, Math.min(Number($('homeCollaborationMaxExperts').value || 3), 20));
  const title = `协作：${conciseTaskName(objective, '协作任务', 32)}`;
  const prompt = [
    `协作目标：${objective}`,
    selectedTeam ? `执行小队：${selectedTeam.name}。` : '执行小队：本次临时组合。',
    `任务复杂度：${complexity}；风险等级：${risk}；最多可派发专家数：${maxExperts}。`,
    allowedAgentNames.length ? `本次只允许选择这些专家：${allowedAgentNames.join('、')}` : '本次允许从全部启用专家中选择。',
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
        reasoningEffort: reasoningEffortForRequest(),
        modelProfileId: state.modelProfileId || null,
        agentProfileId: leader.id,
        collaboration: {
          enabled: true,
          complexity,
          risk,
          allowedAgentProfileIds,
          maxExperts,
          maxDepth: selectedTeam?.maxDepth
            || ($('homeCollaborationAllowExpertDelegation').checked ? 2 : 1),
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
  let values;
  if (role === 'LEADER') values = ['list_agent_profiles','spawn_agent','list_agents','get_agent_result','cancel_agent','read_file','list_dir','search_knowledge'];
  else if (role === 'REVIEWER') values = ['list_dir','read_file','search_knowledge','session_search'];
  else if (role === 'RUNNER') values = ['list_dir','read_file','execute_command','search_knowledge'];
  else values = ['list_dir','read_file','write_file','search_knowledge'];
  return [...new Set([...values, ...expertPlanTools])];
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
  fillSelect($('agentModelProfile'), state.modelProfiles.map(value => (
    {id: value.id, label: modelProfileLabel(value)}
  )), '使用项目默认模型');
  if (editing) $('agentModelProfile').value = profile.modelProfileId || '';
  else if (state.modelProfiles.some(value => value.id === state.modelProfileId)) $('agentModelProfile').value = state.modelProfileId;
  $('agentThinkingMode').value = editing ? profile.thinkingMode || '' : '';
  $('agentReasoningEffort').value = editing ? profile.reasoningEffort || '' : '';
  $('agentExecutionShell').value = editing ? profile.executionShell || 'bash' : state.executionShell;
  updateAgentModelControls();
  fillTagPicker($('agentTools'), $('agentToolsPicker'), agentToolOptions.map(([id, label]) => ({id, label: `${id} · ${label}`})),
      editing ? [...new Set([...parseStringListJson(profile.toolNamesJson), ...expertPlanTools])]
        : defaultToolsForRole($('agentRole').value));
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
      thinkingMode: $('agentThinkingMode').value,
      reasoningEffort: $('agentThinkingMode').value === 'disabled' ? '' : $('agentReasoningEffort').value,
      executionShell: $('agentExecutionShell').value,
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

function openAgentTeamDialog(team = null) {
  const editing = Boolean(team);
  state.editingAgentTeamId = editing ? team.id : '';
  $('agentTeamForm').reset();
  $('agentTeamDialogTitle').textContent = editing ? '编辑执行小队' : '新建执行小队';
  $('saveAgentTeam').textContent = editing ? '保存小队' : '创建小队';
  $('agentTeamName').value = editing ? team.name : '';
  $('agentTeamDescription').value = editing ? team.description || '' : '';
  $('agentTeamInstructions').value = editing ? team.teamInstructions || '' : '';
  $('agentTeamCapabilityTags').value = editing ? parseStringListJson(team.capabilityTagsJson).join(', ') : '';
  $('agentTeamRoutingPolicy').value = editing ? team.routingPolicy || 'CAPABILITY' : 'CAPABILITY';
  $('agentTeamCompletionPolicy').value = editing ? team.completionPolicy || 'LEADER_ACCEPTS' : 'LEADER_ACCEPTS';
  const leaders = state.agentProfiles
    .filter(value => value.enabled && (value.collaborationRole || '').toUpperCase() === 'LEADER')
    .map(value => ({id: value.id, label: value.name}));
  fillSelect($('agentTeamLeader'), leaders, '选择 Leader');
  if (editing) $('agentTeamLeader').value = team.leaderAgentProfileId;
  fillSelect($('agentTeamFallback'), state.agentProfiles.filter(value => value.enabled)
    .map(value => ({id: value.id, label: `${value.name} · ${value.collaborationRole || 'EXPERT'}`})), '不设置');
  if (editing) $('agentTeamFallback').value = team.fallbackAgentProfileId || '';
  const selectedMembers = new Set(editing ? parseStringListJson(team.memberAgentProfileIdsJson) : []);
  const memberNodes = state.agentProfiles
    .filter(value => value.enabled && (value.collaborationRole || '').toUpperCase() !== 'LEADER')
    .map(value => {
      const label = element('label', 'agent-choice');
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.value = value.id;
      input.dataset.agentTeamMember = 'true';
      input.checked = selectedMembers.has(value.id);
      label.append(input, element('strong', '', value.name),
        element('small', '', `${value.collaborationRole || 'EXPERT'} · ${value.description || '未填写说明'}`));
      return label;
    });
  $('agentTeamMembers').replaceChildren(...memberNodes);
  if (!memberNodes.length) $('agentTeamMembers').append(element('div', 'hint', '暂无可用成员专家'));
  $('agentTeamMaxExperts').value = String(editing ? team.maxExperts : 3);
  $('agentTeamMaxDepth').value = String(editing ? team.maxDepth : 1);
  $('agentTeamMaxConcurrency').value = String(editing ? team.maxConcurrency : 3);
  $('agentTeamRequireReviewer').checked = editing && team.requireReviewer;
  $('agentTeamRequireRunner').checked = editing && team.requireRunner;
  $('agentTeamEnabled').checked = editing ? team.enabled : true;
  setFormError('agentTeamFormError', leaders.length ? '' : '请先创建并启用一个 Leader');
  $('agentTeamDialog').showModal();
  $('agentTeamName').focus();
}

async function submitAgentTeam(event) {
  event.preventDefault();
  setFormError('agentTeamFormError');
  const button = $('saveAgentTeam');
  button.disabled = true;
  try {
    const id = state.editingAgentTeamId;
    const members = [...document.querySelectorAll('[data-agent-team-member]:checked')]
      .map(input => input.value);
    const memberRoles = Object.fromEntries(members.map(memberId => {
      const profile = state.agentProfiles.find(value => value.id === memberId);
      return [memberId, `${profile?.collaborationRole || 'EXPERT'} · ${profile?.description || profile?.name || memberId}`];
    }));
    await api(id ? `/v1/productivity/agent-teams/${id}` : '/v1/productivity/agent-teams', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify({
        projectKey: currentProjectKey(),
        name: $('agentTeamName').value.trim(),
        description: $('agentTeamDescription').value.trim(),
        teamInstructions: $('agentTeamInstructions').value.trim(),
        capabilityTags: $('agentTeamCapabilityTags').value.split(/[,，]/).map(value => value.trim()).filter(Boolean),
        routingPolicy: $('agentTeamRoutingPolicy').value,
        completionPolicy: $('agentTeamCompletionPolicy').value,
        fallbackAgentProfileId: $('agentTeamFallback').value || null,
        maxConcurrency: Number($('agentTeamMaxConcurrency').value || 1),
        memberRoles,
        leaderAgentProfileId: $('agentTeamLeader').value,
        memberAgentProfileIds: members,
        maxExperts: Number($('agentTeamMaxExperts').value || 1),
        maxDepth: Number($('agentTeamMaxDepth').value || 1),
        requireReviewer: $('agentTeamRequireReviewer').checked,
        requireRunner: $('agentTeamRequireRunner').checked,
        enabled: $('agentTeamEnabled').checked
      })
    });
    $('agentTeamDialog').close();
    state.editingAgentTeamId = '';
    await Promise.all([refreshAgentStudio(), refreshComposerOptions()]);
    showNotice(id ? '执行小队已保存' : '执行小队已创建');
  } catch (error) {
    setFormError('agentTeamFormError', error.message);
  } finally {
    button.disabled = false;
  }
}

function renderProfiles(values) {
  $('profileList').replaceChildren(...values.map(value => {
    const item = workbenchItem(`${value.defaultProfile ? '★ ' : ''}${modelProvider(value)} · ${value.name}`,
      `${value.localModel ? '本地模型' : '远程模型'} · ${value.model} · ${value.maxContextTokens.toLocaleString()} ctx · Key ${value.apiKeyEnv || '无'} · fallback ${value.fallbackModel || '无'}`);
    actionButton(item, state.modelProfileId === value.id ? '已选用' : '选用',
      () => { selectModelProfile(value.id, false); renderProfiles(values); });
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
  if ($('queueSummary')) $('queueSummary').textContent = values.length ? `${values.length} 个任务` : '无任务';
  setWorkbenchBatchVisible('runs', values.filter(value => terminal.has(value.run.status)).map(value => value.run.id));
  $('queueList').replaceChildren(...values.map(value => {
    const run = value.run; const remaining = value.remainingBudgetTokens < 0 ? '预算不限' : `项目余量 ${value.remainingBudgetTokens.toLocaleString()} Token`;
    const executor = agentProfileName(run.agentProfileId, '默认专家');
    const model = modelProfileName(run.modelProfileId);
    const item = workbenchItem(`${run.status} · ${value.sessionTitle}`, `${executor} · ${model} · step ${run.currentStep} · 优先级 ${run.priority} · ${Math.round(value.elapsedMs / 1000)}s · 已用 ${value.usedTokens.toLocaleString()} Token · ${remaining} · retry ${run.retryCount}`);
    if (run.status === 'QUEUED') { actionButton(item, '提高优先级', () => setRunPriority(run.id, run.priority + 1)); actionButton(item, '取消', () => cancelQueueRun(run.id)); }
    if (!terminal.has(run.status) && run.status !== 'QUEUED') actionButton(item, '终止', () => cancelQueueRun(run.id));
    if (run.status === 'FAILED' || run.status === 'CANCELED') actionButton(item, '重新排队', () => batchQueue([run.id], 'REQUEUE'), true);
    return selectableWorkbenchItem(item, 'runs', run.id, terminal.has(run.status));
  }));
  if (!values.length) $('queueList').append(element('div', 'hint', '当前没有排队、运行、待审批或失败任务'));
}
async function setRunPriority(id, priority) { await api(`/v1/productivity/queue/${id}/priority`, {method: 'PATCH', body: JSON.stringify({priority})}); await loadProductivityData(); }
async function batchQueue(runIds, action) { await api('/v1/productivity/queue/batch', {method: 'POST', body: JSON.stringify({runIds, action})}); await loadProductivityData(); }
async function cancelQueueRun(id) {
  if (!confirm('终止这次执行？它会变为 CANCELED 终态，之后仍可从队列重新排队。')) return;
  await batchQueue([id], 'CANCEL');
}

function renderSchedules(values, templates) {
  $('scheduleList').replaceChildren(...values.map(value => {
    const template = templates.find(item => item.id === value.templateId);
    const model = state.modelProfiles.find(item => item.id === value.modelProfileId);
    const agent = state.agentProfiles.find(item => item.id === value.agentProfileId);
    const team = state.agentTeams.find(item => item.id === value.agentTeamId);
    const executor = team ? `小队 ${team.name}` : agent ? `专家 ${agent.name}` : '普通 Run';
    const modelLabel = model ? `模型 ${model.name}` : '模型继承模板/项目默认';
    const item = workbenchItem(`${value.enabled ? '●' : '○'} ${value.name}`, `${value.scheduleType} ${value.scheduleValue || ''} · ${template?.name || '模板已失效'} · ${modelLabel} · ${executor} · 下次 ${value.nextRunAt ? new Date(value.nextRunAt).toLocaleString() : '未安排'}`);
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
  fillSelect($('scheduleModelProfile'), state.modelProfiles.map(value => ({id: value.id, label: modelProfileLabel(value)})), '使用模板模型或项目默认模型');
  fillSelect($('scheduleAgentProfile'), state.agentProfiles.filter(value => value.enabled)
    .map(value => ({id: value.id, label: `${value.name} · ${value.collaborationRole || 'EXPERT'}`})), '不指定专家');
  fillSelect($('scheduleAgentTeam'), state.agentTeams.filter(value => value.enabled)
    .map(value => ({id: value.id, label: `${value.name} · Leader/最多 ${value.maxExperts} 位专家`})), '不指定小队');
  if (state.modelProfiles.some(value => value.id === state.modelProfileId)) {
    $('scheduleModelProfile').value = state.modelProfileId;
  }
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
      variables, modelProfileId: $('scheduleModelProfile').value || null,
      agentProfileId: $('scheduleAgentProfile').value || null,
      agentTeamId: $('scheduleAgentTeam').value || null,
      enabled: $('scheduleEnabled').checked, nextRunAt
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
    state.managedMemories = values;
    renderManagedMemories(values);
  } catch (error) { showNotice(`Memory 加载失败：${error.message}`, true); }
}

let memoryWikiPages = [];
let memoryWikiSelectedId = '';

async function openMemoryWiki() {
  $('memoryWikiSearch').value = '';
  $('memoryWikiDialog').showModal();
  await loadMemoryWiki();
  $('memoryWikiSearch').focus();
}

async function loadMemoryWiki() {
  const query = $('memoryWikiSearch').value.trim();
  $('memoryWikiIndex').replaceChildren(element('div', 'hint', '正在读取 Memory Wiki…'));
  try {
    memoryWikiPages = await api(`/v1/memories/wiki?projectKey=${encodeURIComponent(currentProjectKey())}&query=${encodeURIComponent(query)}&limit=200`);
    renderMemoryWikiIndex();
    const selected = memoryWikiPages.find(page => page.id === memoryWikiSelectedId) || memoryWikiPages[0];
    if (selected) await showMemoryWikiPage(selected.id);
    else $('memoryWikiPage').replaceChildren(element('div', 'hint', '没有匹配的 Memory 页面。已有 Memory 会自动保留，并在这里显示。'));
  } catch (error) {
    $('memoryWikiIndex').replaceChildren(element('div', 'form-error', `Wiki 加载失败：${error.message}`));
  }
}

function renderMemoryWikiIndex() {
  const index = $('memoryWikiIndex');
  index.replaceChildren();
  memoryWikiPages.forEach(page => {
    const button = element('button', page.id === memoryWikiSelectedId ? 'selected' : '');
    button.type = 'button';
    button.append(element('strong', '', page.title), element('small', '', `${page.memoryType} · ${page.tags || '无标签'}`));
    button.onclick = () => showMemoryWikiPage(page.id).catch(error => showNotice(`Wiki 页面加载失败：${error.message}`, true));
    index.append(button);
  });
  if (!memoryWikiPages.length) index.append(element('div', 'hint', '暂无页面'));
}

async function showMemoryWikiPage(id) {
  const page = memoryWikiPages.find(value => value.id === id);
  if (!page) return;
  memoryWikiSelectedId = id;
  renderMemoryWikiIndex();
  const content = $('memoryWikiPage');
  content.replaceChildren();
  const title = element('h3', '', page.title);
  const key = element('div', 'hint', `内部标识 · ${page.memoryKey}`);
  const body = element('p', '', page.content);
  const meta = element('div', 'memory-wiki-meta');
  [`${page.layer}/${page.memoryType}`, `置信度 ${Math.round(Number(page.confidence || 0) * 100)}%`,
    page.origin === 'automatic' ? 'LLM 自动提取' : '人工维护', page.enabled ? '已启用' : '已停用',
    page.pinned ? '已置顶' : '', page.confirmedAt ? '已确认' : ''].filter(Boolean)
    .forEach(value => meta.append(element('span', '', value)));
  const edit = element('button', 'secondary', '修订此页'); edit.type = 'button';
  edit.onclick = () => { $('memoryWikiDialog').close(); openMemoryRevision(page); };
  content.append(title, key, body, meta, edit, memoryWikiLinksBlock('关联页面', page.outgoingLinks || []),
    memoryWikiLinksBlock('反向引用', page.incomingLinks || []));
  const sourceBlock = element('section', 'memory-wiki-block');
  sourceBlock.append(element('h4', '', '可审计来源'), element('div', 'hint', '正在读取来源…'));
  content.append(sourceBlock);
  const sources = await api(`/v1/memories/${encodeURIComponent(id)}/sources`);
  if (memoryWikiSelectedId !== id) return;
  sourceBlock.replaceChildren(element('h4', '', '可审计来源'));
  if (!sources.length) sourceBlock.append(element('div', 'hint', '此页面由人工创建，尚无自动提取来源。'));
  sources.forEach(source => sourceBlock.append(element('div', 'memory-wiki-source',
    `${source.sourceType} · ${source.sourceId || '未标识来源'} · r${source.sourceRevision || '1'}\n${source.excerpt || '无摘要'}`)));
}

function memoryWikiLinksBlock(title, links) {
  const block = element('section', 'memory-wiki-block');
  block.append(element('h4', '', title));
  if (!links.length) {
    block.append(element('div', 'hint', '暂无关联'));
    return block;
  }
  const list = element('div', 'memory-wiki-links');
  links.forEach(link => {
    const button = element('button', 'secondary', `${link.title} · ${link.relation}`);
    button.type = 'button';
    button.onclick = () => showMemoryWikiPage(link.id).catch(error => showNotice(`Wiki 页面加载失败：${error.message}`, true));
    list.append(button);
  });
  block.append(list);
  return block;
}

const MEMORY_LAYER_ORDER = ['L1', 'L2', 'L3'];
const MEMORY_LAYER_LABELS = {
  L1: 'L1 短期事实',
  L2: 'L2 过程经验',
  L3: 'L3 长期偏好'
};

function renderManagedMemories(values) {
  const container = $('memoryList');
  container.replaceChildren();
  setWorkbenchBatchVisible('memories', values.map(memory => memory.id));
  if (!values.length) {
    container.append(element('div', 'hint', '暂无启用的 Memory'));
    return;
  }
  const byLayer = new Map(MEMORY_LAYER_ORDER.map(layer => [layer, []]));
  values.forEach(memory => {
    const layer = MEMORY_LAYER_ORDER.includes(memory.layer) ? memory.layer : 'L3';
    byLayer.get(layer).push(memory);
  });
  MEMORY_LAYER_ORDER.forEach(layer => {
    const memories = [...byLayer.get(layer)].sort((left, right) =>
      memoryConfidence(right) - memoryConfidence(left)
      || memoryDisplayTitle(left).localeCompare(memoryDisplayTitle(right), 'zh-Hans-CN'));
    container.append(memoryLayerSection(layer, memories, values));
  });
}

function memoryLayerSection(layer, memories, allMemories) {
  const section = element('section', 'memory-layer-block');
  const average = memories.length
    ? Math.round(memories.reduce((sum, memory) => sum + memoryConfidence(memory), 0) / memories.length * 100)
    : 0;
  const title = element('div', 'memory-layer-title');
  title.append(
    element('strong', '', MEMORY_LAYER_LABELS[layer] || layer),
    element('small', '', memories.length ? `${memories.length} 条 · 平均置信度 ${average}%` : '暂无 Memory')
  );
  const list = element('div', 'managed-list memory-layer-list');
  if (memories.length) list.append(...memories.map(memory => memoryItem(memory, allMemories)));
  else list.append(element('div', 'hint', `${layer} 暂无 Memory`));
  section.append(title, list);
  return section;
}

function memoryItem(memory, allMemories) {
  const confidence = Math.round(memoryConfidence(memory) * 100);
  const source = memory.origin === 'automatic' ? `自动 · 置信度 ${confidence}%` : `人工 · 置信度 ${confidence}%`;
  const item = workbenchItem(`${memory.pinned ? '📌 ' : ''}${memoryDisplayTitle(memory)}`,
    `${source} · ${memory.layer}/${memory.memoryType} · ${memory.content}`);
  actionButton(item, memory.pinned ? '取消置顶' : '置顶', () => setMemoryState(memory.id, {pinned: !memory.pinned}));
  actionButton(item, '确认', () => setMemoryState(memory.id, {confirmed: true}));
  actionButton(item, memory.enabled ? '停用' : '启用', () => setMemoryState(memory.id, {enabled: !memory.enabled}));
  if (allMemories.length > 1) actionButton(item, '合并到…', () => openMemoryMerge(memory, allMemories));
  actionButton(item, '修订', () => openMemoryRevision(memory));
  return selectableWorkbenchItem(item, 'memories', memory.id);
}

function memoryDisplayTitle(memory) {
  const content = String(memory?.content || '').replace(/\[\[[^\]]+]]/g, '').replace(/\s+/g, ' ').trim();
  if (!content) return memory?.memoryKey || '未命名记忆';
  const sentence = content.search(/[。！？.!?；;]/);
  const summary = (sentence >= 0 ? content.slice(0, sentence + 1) : content).trim();
  if (summary.length <= 36) return summary;
  const boundary = summary.lastIndexOf(' ', 36);
  return `${summary.slice(0, boundary > 16 ? boundary : 36).trim()}…`;
}

function memoryConfidence(memory) {
  const value = Number(memory?.confidence);
  return Number.isFinite(value) ? value : 0;
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
    setWorkbenchBatchVisible('artifacts', values.map(artifact => artifact.id));
    $('artifactList').replaceChildren(...values.map(artifact => {
      const item = workbenchItem(artifact.name || '未命名 Artifact', `${artifact.type} · ${Math.ceil(artifact.size / 1024)} KB · 关联执行产物`);
      actionButton(item, '预览', () => previewArtifact(artifact.id));
      actionButton(item, '下载', () => downloadArtifact(artifact.id));
      actionButton(item, '复用', () => reuseArtifact(artifact.id), true);
      actionButton(item, '删除', () => deleteArtifact(artifact.id));
      return selectableWorkbenchItem(item, 'artifacts', artifact.id);
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

async function openArtifactPreview(id) {
  const preview = preparePreviewWindow('正在打开 Artifact...');
  if (!preview) return showNotice('浏览器拦截了预览窗口，请允许弹窗后再试', true);
  try {
    const response = await fetch(`/v1/artifacts/${id}/download`, {headers: headers(false)});
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    await openBlobResponse(response, `artifact-${id}.txt`, preview);
  } catch (error) {
    preview.close();
    showNotice(`打开 Artifact 失败：${error.message}`, true);
  }
}

async function openWorkspaceFilePreview(runId, path) {
  const preview = preparePreviewWindow('正在打开成果...');
  if (!preview) return showNotice('浏览器拦截了预览窗口，请允许弹窗后再试', true);
  try {
    const response = await fetch(workspaceFileUrl(runId, path), {headers: headers(false)});
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    if (/\.html?$/i.test(path)) {
      const html = await bundleWorkspaceHtmlPreview(runId, path, await response.text());
      await renderSandboxedHtmlPreview(preview, path, html);
      return;
    }
    await openBlobResponse(response, path.split('/').pop() || 'workspace-file', preview);
  } catch (error) {
    preview.close();
    showNotice(`打开文件失败：${error.message}`, true);
  }
}

function openLongTermMemory() {
  $('longTermMemoryForm').reset();
  setFormError('longTermMemoryError');
  $('longTermMemoryDialog').showModal();
  $('longTermMemoryKey').focus();
}

async function submitLongTermMemory(event) {
  event.preventDefault();
  setFormError('longTermMemoryError');
  const button = $('saveLongTermMemory'); button.disabled = true;
  try {
    await api('/v1/memories', {method: 'POST', body: JSON.stringify({
      projectKey: currentProjectKey(),
      memoryKey: $('longTermMemoryKey').value.trim(),
      content: $('longTermMemoryContent').value.trim(),
      tags: $('longTermMemoryTags').value.trim()
    })});
    $('longTermMemoryDialog').close();
    await loadManagedMemories();
    showNotice('已新增 L3 长期记忆');
  } catch (error) { setFormError('longTermMemoryError', error.message); }
  finally { button.disabled = false; }
}

async function bundleWorkspaceHtmlPreview(runId, entryPath, html) {
  const document = new DOMParser().parseFromString(html, 'text/html');
  document.querySelectorAll('base, meta[http-equiv="Content-Security-Policy" i]').forEach(node => node.remove());

  for (const script of document.querySelectorAll('script[src]')) {
    const asset = await fetchWorkspacePreviewAsset(runId, entryPath, script.getAttribute('src'));
    if (!asset) continue;
    script.src = await blobDataUrl(await asset.response.blob());
    script.removeAttribute('integrity');
    script.removeAttribute('crossorigin');
  }
  for (const link of document.querySelectorAll('link[rel~="stylesheet"][href]')) {
    const asset = await fetchWorkspacePreviewAsset(runId, entryPath, link.getAttribute('href'));
    if (!asset) continue;
    const style = document.createElement('style');
    style.textContent = await inlineWorkspaceCssAssets(runId, asset.path, await asset.response.text());
    if (link.media) style.media = link.media;
    link.replaceWith(style);
  }
  for (const style of document.querySelectorAll('style')) {
    style.textContent = await inlineWorkspaceCssAssets(runId, entryPath, style.textContent || '');
  }
  const resourceAttributes = [
    ['img[src]', 'src'], ['source[src]', 'src'], ['audio[src]', 'src'],
    ['video[src]', 'src'], ['video[poster]', 'poster'], ['input[type="image"][src]', 'src']
  ];
  for (const [selector, attribute] of resourceAttributes) {
    for (const node of document.querySelectorAll(selector)) {
      const asset = await fetchWorkspacePreviewAsset(runId, entryPath, node.getAttribute(attribute));
      if (asset) node.setAttribute(attribute, await blobDataUrl(await asset.response.blob()));
    }
  }

  const policy = document.createElement('meta');
  policy.httpEquiv = 'Content-Security-Policy';
  policy.content = "default-src 'none'; script-src data: 'unsafe-inline'; style-src data: 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";
  document.head.prepend(policy);
  return `<!doctype html>\n${document.documentElement.outerHTML}`;
}

async function inlineWorkspaceCssAssets(runId, ownerPath, css) {
  const matches = [...css.matchAll(/url\(\s*(['"]?)([^'"\)]+)\1\s*\)/gi)];
  let result = css;
  for (const match of matches) {
    const asset = await fetchWorkspacePreviewAsset(runId, ownerPath, match[2]);
    if (!asset) continue;
    result = result.replace(match[0], `url("${await blobDataUrl(await asset.response.blob())}")`);
  }
  return result;
}

async function fetchWorkspacePreviewAsset(runId, ownerPath, reference) {
  const path = resolveWorkspaceReference(ownerPath, reference);
  if (!path) return null;
  const response = await fetch(workspaceFileUrl(runId, path), {headers: headers(false)});
  if (!response.ok) throw new Error(`依赖文件 ${path} 读取失败：${response.status}`);
  return {path, response};
}

function resolveWorkspaceReference(ownerPath, reference) {
  let value = (reference || '').trim().replaceAll('\\', '/');
  if (!value || value.startsWith('#') || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(value)) return '';
  value = value.split('#', 1)[0].split('?', 1)[0];
  try { value = decodeURIComponent(value); } catch (_) { return ''; }
  const parts = value.startsWith('/') ? [] : ownerPath.replaceAll('\\', '/').split('/').slice(0, -1);
  for (const part of value.split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') {
      if (!parts.length) return '';
      parts.pop();
    } else {
      parts.push(part);
    }
  }
  return parts.join('/');
}

function blobDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error || new Error('工作区依赖读取失败'));
    reader.readAsDataURL(blob);
  });
}

async function renderSandboxedHtmlPreview(preview, path, html) {
  const frame = await waitForPreviewFrame(preview);
  const document = preview.document;
  document.title = `${path} · PaiCLI 安全预览`;
  frame.title = `${path} 安全预览`;
  frame.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-downloads');
  frame.setAttribute('referrerpolicy', 'no-referrer');
  const previewUrl = preview.URL.createObjectURL(new preview.Blob([html], {type: 'text/html'}));
  frame.src = previewUrl;
  frame.hidden = false;
  document.getElementById('previewLoading')?.remove();
  frame.onload = () => setTimeout(() => preview.URL.revokeObjectURL(previewUrl), 1000);
}

async function waitForPreviewFrame(preview) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (preview.closed) throw new Error('预览窗口已关闭');
    try {
      const frame = preview.document?.getElementById('workspacePreviewFrame');
      if (frame) return frame;
    } catch (_) { }
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('预览窗口初始化超时');
}

function preparePreviewWindow(message) {
  const preview = window.open('/workspace-preview.html', '_blank');
  if (!preview) return null;
  preview.opener = null;
  return preview;
}

async function openArtifact(id) {
  try {
    const response = await fetch(`/v1/artifacts/${id}/download`, {headers: headers(false)});
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    await openBlobResponse(response, `artifact-${id}.txt`);
  } catch (error) { showNotice(`打开 Artifact 失败：${error.message}`, true); }
}

async function openWorkspaceFile(runId, path) {
  try {
    const response = await fetch(workspaceFileUrl(runId, path), {headers: headers(false)});
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    await openBlobResponse(response, path.split('/').pop() || 'workspace-file');
  } catch (error) { showNotice(`打开文件失败：${error.message}`, true); }
}

async function downloadWorkspaceFile(runId, path) {
  try {
    const response = await fetch(workspaceFileUrl(runId, path), {headers: headers(false)});
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    await downloadBlobResponse(response, path.split('/').pop() || 'workspace-file');
  } catch (error) { showNotice(`下载文件失败：${error.message}`, true); }
}

function workspaceFileUrl(runId, path) {
  return `/v1/runs/${encodeURIComponent(runId)}/workspace-file?path=${encodeURIComponent(path)}`;
}

async function openBlobResponse(response, fallbackName, previewWindow = null) {
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  if (previewWindow) previewWindow.location.href = url;
  else showNotice('浏览器拦截了预览窗口，请允许弹窗后再试', true);
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

async function downloadBlobResponse(response, fallbackName) {
  downloadBlob(await response.blob(), filenameFromResponse(response, fallbackName));
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function filenameFromResponse(response, fallbackName) {
  const disposition = response.headers.get('Content-Disposition') || '';
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  return encoded ? decodeURIComponent(encoded) : fallbackName;
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
      const item = workbenchItem(
        `${plan.status} · ${conciseTaskName(plan.summary || plan.objective, '未命名计划')}`,
        `${plan.objective} · v${plan.version} · ${new Date(plan.updatedAt).toLocaleString()}`
      );
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
    const stateText = Object.entries(view.state.stepCounts || {}).map(([status, count]) => `${status}=${count}`).join(' · ') || '无步骤';
    const stepNames = new Map(view.steps.map(step => [step.id, `${step.ordinal}. ${step.title}`]));
    $('planDetailTitle').textContent = `${view.plan.status} · ${conciseTaskName(view.plan.summary || view.plan.objective, 'Plan')}`;
    const content = $('planDetailContent');
    content.replaceChildren();
    content.append(auditSummary([
      ['计划', conciseTaskName(view.plan.summary || view.plan.objective, '未命名计划')],
      ['步骤状态', stateText],
      ['模型 Token', String(view.state.totalTokens || 0)]
    ]));
    content.append(auditTextSection('完整目标', view.plan.objective));
    content.append(auditListSection('步骤与 Run', view.steps.map(step => {
      const card = auditCard(`${step.status} · ${step.ordinal}. ${step.title}`,
        `${step.type} · ${step.executionMode}`, step.description);
      const actions = element('div', 'managed-actions');
      const openRun = actionButtonElement(step.runId ? '打开关联执行' : '尚未生成执行',
        () => openPlanStepRun(view.plan, step));
      openRun.disabled = !step.runId;
      actions.append(openRun);
      if (step.status === 'WAITING_APPROVAL' && step.type === 'USER_APPROVAL') {
        actions.append(
          actionButtonElement('批准', () => decidePlanStep(step, 'APPROVED')),
          actionButtonElement('拒绝', () => decidePlanStep(step, 'REJECTED'))
        );
      }
      card.append(actions);
      const stepChecks = checks.filter(check => check.stepId === step.id);
      if (stepChecks.length) card.append(auditDetails('验证证据',
        stepChecks.map(check => `${check.status} · ${check.name}\n${check.evidence || check.actual || check.expected || check.error || ''}`).join('\n\n')));
      return card;
    })));
    content.append(auditTextSection('Graph 边', view.edges.map(edge =>
      `${edge.type} · ${stepNames.get(edge.fromStepId) || '未知步骤'} → ${stepNames.get(edge.toStepId) || '未知步骤'} · ${edge.conditionExpression}`
      + `${edge.type === 'REWORK' ? ` · ${edge.traversalCount}/${edge.maxTraversals}` : ''}`).join('\n') || '无'));
    content.append(auditTextSection('阻塞原因', (view.state.blockers || []).map(blocker =>
      `${blocker.kind} · ${stepNames.get(blocker.stepId) || '计划级'} · ${blocker.detail}`).join('\n') || '无'));
    content.append(auditTextSection('调度批次', batches.map(batch =>
      `第 ${batch.ordinal} 批 · ${batch.readOnlyEligible ? '可并行只读' : '串行'} · ${batch.stepIds.map(stepId => stepNames.get(stepId) || '未知步骤').join('、')}`).join('\n') || '无'));
    content.append(auditTextSection('异步任务', jobs.map(job =>
      `${job.status} · ${job.kind}${job.runId ? ' · 已关联执行' : ''}`).join('\n') || '无'));
    if (!$('planDetailDialog').open) $('planDetailDialog').showModal();
  } catch (error) { showNotice(`Plan 详情加载失败：${error.message}`, true); }
}

async function decidePlanStep(step, decision) {
  const reason = prompt(`填写${decision === 'APPROVED' ? '批准' : '拒绝'}原因（可留空）`) || '';
  try {
    await api(`/v1/plan-steps/${step.id}/decision`, {
      method: 'POST', body: JSON.stringify({decision, reason})
    });
    showNotice(`人工节点已${decision === 'APPROVED' ? '批准' : '拒绝'}`);
    await inspectPlan(step.planId);
    await loadPlans();
  } catch (error) {
    showNotice(`人工节点处理失败：${error.message}`, true);
  }
}

async function openPlanStepRun(plan, step) {
  if (!step?.runId) return showNotice('该步骤尚未绑定 Run', true);
  try {
    const audit = await api(`/v1/runs/${encodeURIComponent(step.runId)}/audit`);
    $('runAuditTitle').textContent = `${audit.run.status} · ${step.title}`;
    const content = $('runAuditContent');
    content.replaceChildren();
    content.append(auditSummary([
      ['Run ID', audit.run.id],
      ['Session ID', audit.session.id],
      ['执行者', agentProfileName(audit.run.agentProfileId, '默认专家')],
      ['模型', modelProfileName(audit.run.modelProfileId)],
      ['所属会话', audit.session.title],
      ['计划步骤', `${step.ordinal}. ${step.title}`]
    ]));
    const sessionActions = element('div', 'managed-actions');
    sessionActions.append(actionButtonElement('打开所属 Session', async () => {
      $('runAuditDialog').close();
      if ($('planDetailDialog').open) $('planDetailDialog').close();
      await openChildSession(audit.session.id);
    }));
    content.append(sessionActions);
    content.append(auditTextSection('Run 输入', audit.run.input || ''));
    content.append(auditListSection('模型输出', (audit.messages || [])
      .filter(message => message.role === 'assistant').map(message => {
      const card = auditCard('模型回答', message.createdAt || '',
        message.content || '');
      if (message.reasoningContent) card.append(auditDetails('模型推理', message.reasoningContent));
      return card;
    })));
    content.append(auditListSection('工具调用', (audit.toolCalls || []).map(call => {
      const card = auditCard(`${call.status} · ${call.toolName} · ${call.id}`,
        `Provider Call：${call.providerCallId || '无'} · ${call.createdAt || ''}`,
        call.error || call.result || '尚无结果');
      card.append(auditDetails('持久化参数', formatAuditValue(call.arguments)));
      if (call.result) card.append(auditDetails('完整结果', formatAuditValue(call.result)));
      return card;
    })));
    if (audit.parent?.runId) {
      content.append(auditTextSection('父 Run',
        `runId: ${audit.parent.runId}\nsessionId: ${audit.parent.sessionId}\nagentName: ${audit.parent.agentName || ''}`));
    }
    content.append(auditListSection('子 Run', (audit.children || []).map(child =>
      auditCard(`${child.agentName} · ${child.childRunId}`,
        `delegationId: ${child.delegationId} · sessionId: ${child.childSessionId}`,
        `parentRunId: ${child.parentRunId}\nstatus: ${child.status}`))));
    content.append(auditListSection('审批', (audit.approvals || []).map(approval => {
      const card = auditCard(`审批 · ${approval.status}`, approval.createdAt || '', approval.reason || '');
      if (approval.status === 'PENDING') {
        const actions = element('div', 'managed-actions');
        actions.append(
          actionButtonElement('允许', async () => {
            await resolveApproval(approval.id, 'APPROVED');
            await openPlanStepRun(plan, step);
          }),
          actionButtonElement('拒绝', async () => {
            await resolveApproval(approval.id, 'DENIED');
            await openPlanStepRun(plan, step);
          })
        );
        card.append(actions);
      }
      return card;
    })));
    content.append(auditListSection('验证证据', (audit.validationChecks || []).map(check =>
      auditCard(`${check.status} · ${check.name}`, check.kind,
        check.evidence || check.actual || check.expected || check.error || ''))));
    if (audit.workingPlan) {
      content.append(auditTextSection('执行计划（WorkingPlan）',
        `${audit.workingPlan.objective || ''}

${audit.workingPlan.itemsJson || '[]'}
（修订 ${audit.workingPlan.revision} · ${audit.workingPlan.status}）`));
    }
    if (audit.reflection) {
      content.append(auditTextSection('失败反思（Reflection）',
        `${audit.reflection.failureClass} · ${audit.reflection.decision}
${audit.reflection.diagnosis || ''}
下一步：${audit.reflection.nextAction || ''}`));
    }
    const harnessVerifications = audit.verifications || [];
    if (harnessVerifications.length) {
      content.append(auditListSection('完成验证（Verification）', harnessVerifications.map(event =>
        auditCard('run.verification', event.createdAt || '', formatAuditValue(event.data)))));
    }
    content.append(auditListSection('Run 事件', (audit.events || []).map(event =>
      auditCard(event.type, event.createdAt || '', formatAuditValue(event.data)))));
    if (!$('runAuditDialog').open) $('runAuditDialog').showModal();
  } catch (error) {
    showNotice(`Run 审计详情加载失败：${error.message}`, true);
  }
}

function auditSummary(values) {
  const summary = element('div', 'audit-summary');
  values.forEach(([label, value]) => {
    const item = element('div', 'capability-status-item');
    item.append(element('strong', '', label), element('small', '', value || '—'));
    summary.append(item);
  });
  return summary;
}

function auditTextSection(title, text) {
  const section = element('section', 'audit-section');
  section.append(element('h3', '', title), element('pre', 'raw', text || '无'));
  return section;
}

function auditListSection(title, cards) {
  const section = element('section', 'audit-section');
  section.append(element('h3', '', title));
  const list = element('div', 'audit-list');
  if (cards.length) list.append(...cards);
  else list.append(element('div', 'hint', '无'));
  section.append(list);
  return section;
}

function auditCard(title, meta = '', body = '') {
  const card = element('article', 'audit-card');
  const head = element('div', 'audit-card-head');
  head.append(element('strong', '', title), element('small', 'hint', meta));
  card.append(head);
  if (body) card.append(element('p', '', body));
  return card;
}

function auditDetails(title, value) {
  const details = element('details', '');
  details.append(element('summary', '', title), element('pre', '', value || ''));
  return details;
}

function formatAuditValue(value) {
  if (typeof value !== 'string') return JSON.stringify(value, null, 2);
  try { return JSON.stringify(JSON.parse(value), null, 2); }
  catch { return value; }
}

async function loadApprovalPolicies() {
  try {
    const values = await api(`/v1/approvals/policies?projectKey=${encodeURIComponent(currentProjectKey())}`);
    setWorkbenchBatchVisible('policies', values.map(policy => policy.id));
    $('policyList').replaceChildren(...values.map(policy => {
      const item = workbenchItem(`${policy.scope} · ${policy.toolName}`, `参数指纹 ${policy.argumentsSha256.slice(0, 16)}…`);
      actionButton(item, '撤销', async () => {
        await api(`/v1/approvals/policies/${policy.id}`, {method: 'DELETE'});
        await loadApprovalPolicies();
      });
      return selectableWorkbenchItem(item, 'policies', policy.id);
    }));
    if (!values.length) $('policyList').append(element('div', 'hint', '暂无持久化审批策略'));
  } catch (error) { showNotice(`审批策略加载失败：${error.message}`, true); }
}

async function loadApprovals() {
  if (!state.runId) {
    $('approvals').replaceChildren();
    return;
  }
  const requestedRunId = state.runId;
  let board = {tasks: []};
  try {
    board = await api(`/v1/runs/${requestedRunId}/collaboration`);
  } catch (_) { /* A normal Run has no collaboration board. */ }
  const values = await api(`/v1/approvals?runId=${encodeURIComponent(requestedRunId)}`);
  if (state.runId !== requestedRunId) return;
  const taskNames = new Map((board.tasks || []).map(task =>
    [task.childRunId, task.agentName || task.profile?.name || '子专家']));
  const cards = [];
  for (const approval of values) {
    const card = element('div', 'approval');
    const owner = approval.runId === state.runId ? '当前专家'
      : taskNames.get(approval.runId) || '协作子专家';
    card.append(
      element('strong', '', `${owner}需要你的确认`),
      element('div', 'approval-context', approval.runId === state.runId ? '当前执行' : `${owner}执行`),
      element('div', '', approval.reason)
    );
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
    cards.push(card);
    if (!state.notifiedApprovalIds.has(approval.id)) {
      state.notifiedApprovalIds.add(approval.id);
      if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
        new Notification('PaiCLI · 子专家等待审批', {body: `${owner}：${approval.reason}`});
      }
    }
  }
  $('approvals').replaceChildren(...cards);
  const effective = effectiveConversationStatus();
  $('status').textContent = values.length ? `待审批 ${values.length}` : statusNames[effective] || '空闲';
  $('status').className = `status${values.length || (effective && !terminal.has(effective)) ? ' active' : ''}`
    + `${effective === 'FAILED' ? ' error' : ''}`;
}

async function resolveApproval(id, decision, rememberScope = null) {
  try {
    await api(`/v1/approvals/${id}`, {
      method: 'POST', body: JSON.stringify({decision, rememberScope})
    });
    showNotice(decision === 'APPROVED' ? '已允许，继续执行' : '已拒绝');
    await loadApprovals();
    await refreshApprovalInbox();
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
  const kimiK3 = selectedModelIsKimiK3();
  if (kimiK3) {
    state.thinkingMode = 'enabled';
    if (!['low', 'high', 'max'].includes(state.reasoningEffort)) state.reasoningEffort = 'max';
    localStorage.setItem('paicli_thinking_mode', state.thinkingMode);
    localStorage.setItem('paicli_reasoning_effort', state.reasoningEffort);
  }
  document.querySelectorAll('[data-thinking]').forEach(button => {
    button.classList.toggle('active', button.dataset.thinking === state.thinkingMode);
    button.disabled = kimiK3;
  });
  document.querySelectorAll('[data-effort]').forEach(button => {
    button.classList.toggle('active', button.dataset.effort === state.reasoningEffort);
    button.disabled = !kimiK3 && state.thinkingMode !== 'enabled';
  });
  $('thinkingLabel').textContent = kimiK3 ? 'Kimi 始终思考' : '深度思考';
  $('effortLabel').textContent = kimiK3 ? 'K3 推理强度' : '推理等级';
}

function updateAgentModelControls() {
  const profile = state.modelProfiles.find(value => value.id === $('agentModelProfile').value)
    || state.modelProfiles.find(value => value.defaultProfile);
  const kimiK3 = (profile?.model || '').toLowerCase().startsWith('kimi-k3');
  $('agentThinkingMode').disabled = kimiK3;
  if (kimiK3) {
    $('agentThinkingMode').value = 'enabled';
    if (!$('agentReasoningEffort').value) $('agentReasoningEffort').value = 'max';
  }
  $('agentReasoningEffort').disabled = !kimiK3 && $('agentThinkingMode').value === 'disabled';
  $('agentReasoningHint').textContent = kimiK3
    ? 'Kimi K3 始终思考；可选择低、高或最高推理强度。'
    : '仅在思考模式开启时生效。';
}

document.querySelectorAll('[data-home-mode]').forEach(button => {
  button.onclick = () => setHomeMode(button.dataset.homeMode);
});
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
$('planSend').onclick = () => createPlanFromComposer(null, true);
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
$('returnCollaborationTask').onclick = returnToCollaborationTask;
$('approvalInbox').onclick = async () => {
  renderApprovalInbox(await refreshApprovalInbox());
  $('approvalInboxDialog').showModal();
};
$('closeApprovalInbox').onclick = () => $('approvalInboxDialog').close();
$('evaluationCenter').onclick = openEvaluationCenter;
$('closeWorkbench').onclick = () => $('workbenchDialog').close();
$('closePlanDetail').onclick = () => $('planDetailDialog').close();
$('closeRunAudit').onclick = () => $('runAuditDialog').close();
$('closeAgentStudio').onclick = () => $('agentStudioDialog').close();
$('closeEvaluationCenter').onclick = () => $('evaluationDialog').close();
$('searchAll').onclick = searchAll;
$('globalSearch').onkeydown = event => { if (event.key === 'Enter') searchAll(); };
$('refreshMemories').onclick = loadManagedMemories;
$('addLongTermMemory').onclick = openLongTermMemory;
$('selectAllMemories').onclick = event => toggleWorkbenchBatchSelection('memories', event);
$('deleteSelectedMemories').onclick = event => deleteWorkbenchSelection('memories', event);
$('openMemoryWiki').onclick = () => openMemoryWiki().catch(error => showNotice(`Wiki 加载失败：${error.message}`, true));
$('closeMemoryWiki').onclick = () => $('memoryWikiDialog').close();
$('searchMemoryWiki').onclick = () => loadMemoryWiki();
$('memoryWikiSearch').onkeydown = event => { if (event.key === 'Enter') { event.preventDefault(); loadMemoryWiki(); } };
$('refreshArtifacts').onclick = loadArtifacts;
$('selectAllArtifacts').onclick = event => toggleWorkbenchBatchSelection('artifacts', event);
$('deleteSelectedArtifacts').onclick = event => deleteWorkbenchSelection('artifacts', event);
$('refreshPlans').onclick = loadPlans;
$('refreshPolicies').onclick = loadApprovalPolicies;
$('selectAllPolicies').onclick = event => toggleWorkbenchBatchSelection('policies', event);
$('deleteSelectedPolicies').onclick = event => deleteWorkbenchSelection('policies', event);
$('refreshProductivity').onclick = loadProductivityData;
$('refreshQueue').onclick = event => { event.stopPropagation(); loadProductivityData(); };
$('selectAllRuns').onclick = event => toggleWorkbenchBatchSelection('runs', event);
$('deleteSelectedRuns').onclick = event => deleteWorkbenchSelection('runs', event);
$('refreshEvaluations').onclick = loadEvaluations;
$('addEvaluationSuite').onclick = openEvaluationSuiteDialog;
$('installEvaluationStarterPack').onclick = installEvaluationStarterPack;
$('installAgentStarterPack').onclick = installAgentStarterPack;
$('addAgentFromStudio').onclick = () => openAgentProfileDialog();
$('addAgentTeam').onclick = () => openAgentTeamDialog();
$('addTemplate').onclick = openTemplateDialog;
$('addProfile').onclick = openProfileDialog;
$('addSchedule').onclick = openScheduleDialog;
$('addNotification').onclick = openNotificationDialog;
$('cancelTemplate').onclick = () => $('templateDialog').close();
$('cancelAgentProfile').onclick = () => { state.editingAgentProfileId = ''; $('agentProfileDialog').close(); };
$('cancelAgentTeam').onclick = () => { state.editingAgentTeamId = ''; $('agentTeamDialog').close(); };
$('cancelProfile').onclick = () => $('profileDialog').close();
$('cancelSchedule').onclick = () => $('scheduleDialog').close();
$('cancelNotification').onclick = () => $('notificationDialog').close();
$('cancelEvaluationSuite').onclick = () => $('evaluationSuiteDialog').close();
$('cancelEvaluationCase').onclick = () => $('evaluationCaseDialog').close();
$('templateForm').onsubmit = submitTemplate;
$('agentProfileForm').onsubmit = submitAgentProfile;
$('agentTeamForm').onsubmit = submitAgentTeam;
$('agentProfileDialog').addEventListener('close', () => { state.editingAgentProfileId = ''; });
$('agentTeamDialog').addEventListener('close', () => { state.editingAgentTeamId = ''; });
$('agentThinkingMode').onchange = () => {
  updateAgentModelControls();
};
$('agentModelProfile').onchange = updateAgentModelControls;
$('profileForm').onsubmit = submitProfile;
$('scheduleForm').onsubmit = submitSchedule;
$('notificationForm').onsubmit = submitNotification;
$('evaluationSuiteForm').onsubmit = submitEvaluationSuite;
$('evaluationCaseForm').onsubmit = submitEvaluationCase;
$('evaluationCaseType').onchange = toggleRepositoryEvaluationFields;
$('inspectEvaluationFixture').onclick = inspectEvaluationFixture;
$('profileLocal').onchange = updateProfilePriceFields;
$('agentRole').onchange = updateAgentRoleHelp;
$('scheduleType').onchange = updateScheduleFields;
$('scheduleTemplate').onchange = () => { $('scheduleVariables').value = selectedTemplateVariables(); };
$('scheduleAgentProfile').onchange = () => {
  if ($('scheduleAgentProfile').value) $('scheduleAgentTeam').value = '';
};
$('scheduleAgentTeam').onchange = () => {
  if ($('scheduleAgentTeam').value) $('scheduleAgentProfile').value = '';
};
$('notificationType').onchange = () => updateNotificationFields(true);
$('cancelMemoryMerge').onclick = () => $('memoryMergeDialog').close();
$('cancelMemoryRevision').onclick = () => $('memoryRevisionDialog').close();
$('cancelLongTermMemory').onclick = () => $('longTermMemoryDialog').close();
$('memoryMergeForm').onsubmit = submitMemoryMerge;
$('memoryRevisionForm').onsubmit = submitMemoryRevision;
$('longTermMemoryForm').onsubmit = submitLongTermMemory;
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
$('messages').addEventListener('scroll', () => rememberMessageScroll(), {passive: true});
$('modelProfile').onchange = () => {
  selectModelProfile($('modelProfile').value, false);
};
$('executionShell').onchange = () => selectExecutionShell($('executionShell').value);
document.querySelectorAll('[data-export]').forEach(button => button.onclick = () => exportSession(button.dataset.export).catch(error => showNotice(`导出失败：${error.message}`, true)));
$('sessionImport').onchange = () => { const file = $('sessionImport').files[0]; if (file) importSession(file).catch(error => showNotice(`导入失败：${error.message}`, true)); };
$('closeCapabilities').onclick = () => $('capabilityDialog').close();
$('importSkill').onclick = importSkill;
$('addMcp').onclick = () => addMcp().catch(error => showNotice(`MCP 配置失败：${error.message}`, true));
$('addGithubMcp').onclick = () => addGithubMcp().catch(error => showNotice(`GitHub MCP 配置失败：${error.message}`, true));
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
hydrateModuleExplainers();
new MutationObserver(records => {
  if (records.some(record => record.addedNodes.length)) hydrateModuleExplainers();
}).observe(document.body, {childList: true, subtree: true});
clearEvents();
renderModelControls();
selectExecutionShell(state.executionShell);
syncCollaborationTaskReturnAction();
renderEmpty();
refreshComposerOptions();
refreshSessions(false);
refreshApprovalInbox();
state.approvalInboxRefreshTimer = setInterval(refreshApprovalInbox, 3000);
