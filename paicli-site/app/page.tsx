const githubUrl = "https://github.com/shangshenglin/paicli-platform-lite";

const features = [
  {
    index: "01",
    title: "可恢复 Agent Runtime",
    text: "Session、Run、消息、事件与工具调用完整落库。进程重启后从持久化状态继续，而不是重新猜测现场。",
    accent: "green",
  },
  {
    index: "02",
    title: "持久化安全审批",
    text: "危险工具先保存参数再请求批准，支持仅本次、本对话与本项目策略。恢复时始终执行用户看过的动作。",
    accent: "amber",
  },
  {
    index: "03",
    title: "分层自动 Memory",
    text: "Run 完成后异步提取 L1/L2/L3 记忆，保留来源、置信度与修订历史，并提供完整人工纠错边界。",
    accent: "violet",
  },
  {
    index: "04",
    title: "混合知识检索",
    text: "结构化分块结合 BM25、Embedding 与 RRF 融合，支持 PDF、Office、Markdown、表格和扫描件 OCR。",
    accent: "blue",
  },
  {
    index: "05",
    title: "隔离工具执行",
    text: "开发环境可使用 Local 模式；需要执行命令时切换到按 Run 隔离、限额并自动回收的 Docker Sandbox。",
    accent: "red",
  },
  {
    index: "06",
    title: "统一业务工作台",
    text: "在一个中文 Console 中管理重试、分支、审批、Memory、知识、Artifact 与全局检索。",
    accent: "cyan",
  },
];

const runtimeSteps = [
  ["01", "任务落库", "Run 以 QUEUED 提交"],
  ["02", "模型推理", "流式 delta 批量持久化"],
  ["03", "工具审批", "原参数进入持久化 Approval"],
  ["04", "隔离执行", "结果先落库再进入下一轮"],
];

function Arrow() {
  return <span aria-hidden="true">↗</span>;
}

export default function Home() {
  return (
    <main>
      <nav className="nav" aria-label="主导航">
        <a className="brand" href="#top" aria-label="PaiCLI 首页">
          <span className="brand-mark">π</span>
          <span>PaiCLI</span>
          <small>PLATFORM LITE</small>
        </a>
        <div className="nav-links">
          <a href="#features">核心能力</a>
          <a href="#architecture">运行机制</a>
          <a href="#deploy">本地部署</a>
        </div>
        <a className="nav-github" href={githubUrl} target="_blank" rel="noreferrer">
          GitHub <Arrow />
        </a>
      </nav>

      <section className="hero" id="top">
        <div className="hero-copy">
          <div className="eyebrow"><span />单机 · 私有 · 可审计</div>
          <h1>把模型的思考，<br /><em>变成可恢复的执行。</em></h1>
          <p className="hero-lead">
            PaiCLI Platform Lite 是面向个人开发者与私有环境的受管 Agent Runtime。
            它让模型、工具、审批、记忆与隔离执行形成一条真实可运行的闭环。
          </p>
          <div className="hero-actions">
            <a className="button primary" href="#deploy">5 分钟本地启动 <Arrow /></a>
            <a className="button secondary" href={githubUrl} target="_blank" rel="noreferrer">查看源代码</a>
          </div>
          <div className="metrics" aria-label="项目指标">
            <div><strong>59</strong><span>自动化测试</span></div>
            <div><strong>10</strong><span>交付阶段</span></div>
            <div><strong>17</strong><span>Java 版本</span></div>
            <div><strong>1</strong><span>单机运行节点</span></div>
          </div>
        </div>

        <div className="runtime-card" aria-label="PaiCLI Runtime 状态示意">
          <div className="runtime-head">
            <div><span className="live-dot" /> RUNTIME LIVE</div>
            <span>run_a81f</span>
          </div>
          <div className="runtime-prompt">
            <small>USER REQUEST</small>
            <p>分析项目配置，并生成一份部署检查报告。</p>
          </div>
          <div className="runtime-flow">
            <div className="flow-line active"><i>01</i><span><b>上下文已组装</b><small>Rules · Memory · Knowledge</small></span><time>14ms</time></div>
            <div className="flow-line active"><i>02</i><span><b>模型推理完成</b><small>2 tool calls persisted</small></span><time>1.8s</time></div>
            <div className="flow-line waiting"><i>03</i><span><b>等待危险工具审批</b><small>execute_command · 参数已锁定</small></span><time>NOW</time></div>
            <div className="flow-line"><i>04</i><span><b>隔离执行</b><small>Docker Sandbox</small></span><time>—</time></div>
          </div>
          <div className="approval-panel">
            <div><small>APPROVAL REQUIRED</small><strong>允许执行只读检查命令？</strong></div>
            <div className="approval-actions"><span>拒绝</span><b>仅本次允许</b></div>
          </div>
          <div className="runtime-foot"><span>SQLite WAL</span><span>SSE connected</span><span>step 2 / 60</span></div>
        </div>
      </section>

      <section className="trust-strip" aria-label="产品特性">
        <span>TOOL CALLS FIRST PERSISTED</span><i />
        <span>MODEL KEYS STAY ON SERVER</span><i />
        <span>OPENAI-COMPATIBLE</span><i />
        <span>DOCKER OPTIONAL</span>
      </section>

      <section className="section features" id="features">
        <div className="section-kicker">CORE CAPABILITIES / 01</div>
        <div className="section-heading">
          <h2>不是聊天壳，<br />而是完整的执行系统。</h2>
          <p>围绕真实 Agent 工作流设计：每一个副作用都有状态，每一次恢复都有依据，每一项高风险动作都能被审计。</p>
        </div>
        <div className="feature-grid">
          {features.map((feature) => (
            <article className={`feature-card ${feature.accent}`} key={feature.index}>
              <span className="feature-index">{feature.index}</span>
              <div className="feature-symbol"><span /><span /><span /></div>
              <h3>{feature.title}</h3>
              <p>{feature.text}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="section architecture" id="architecture">
        <div className="section-kicker">DURABLE LOOP / 02</div>
        <div className="section-heading compact">
          <h2>崩溃可以发生，<br />状态不能丢失。</h2>
          <p>当前状态留在关系表，运行事件用于重放与诊断。重启后 Worker 继续处理同一个 Run，而不是让模型再做一次决定。</p>
        </div>
        <div className="runtime-steps">
          {runtimeSteps.map(([number, title, text], index) => (
            <div className="runtime-step" key={number}>
              <span>{number}</span>
              <strong>{title}</strong>
              <p>{text}</p>
              {index < runtimeSteps.length - 1 && <i aria-hidden="true">→</i>}
            </div>
          ))}
        </div>
        <div className="architecture-map">
          <div className="map-column client-column">
            <small>CLIENT</small><strong>中文 Web Console</strong><span>REST · SSE · OpenAPI</span>
          </div>
          <div className="map-connector"><span>REQUEST</span><i /><span>EVENTS</span></div>
          <div className="map-core">
            <small>PAICLI SERVER</small>
            <strong>Agent Runtime</strong>
            <div className="core-items"><span>Context</span><span>Worker</span><span>Approval</span><span>Memory</span></div>
          </div>
          <div className="map-connector"><span>TOOLS</span><i /><span>RESULT</span></div>
          <div className="map-column sandbox-column">
            <small>EXECUTION</small><strong>Sandbox Agent</strong><span>Local / Docker</span>
          </div>
          <div className="storage-row"><span>SQLite WAL</span><span>Local Artifact</span><span>Workspace</span><span>Audit JSONL</span></div>
        </div>
      </section>

      <section className="section console-section">
        <div className="section-kicker">ONE WORKBENCH / 03</div>
        <div className="section-heading">
          <h2>看见 Agent<br />正在做什么。</h2>
          <p>用户对话与执行详情并排呈现。审批、工具参数、推理活动和最终结果在同一个任务视图中收敛。</p>
        </div>
        <div className="console-frame">
          <div className="console-top"><span className="traffic"><i /><i /><i /></span><b>localhost:8080</b><span>PAICLI CONSOLE</span></div>
          <div className="console-body">
            <aside className="console-sidebar">
              <strong><i>π</i> PaiCLI</strong>
              <button>＋ 新建对话</button>
              <small>历史对话</small>
              <span className="selected">部署检查报告 <i>运行中</i></span>
              <span>知识库整理</span><span>架构设计评审</span>
              <div><b>业务工作台</b><b>能力管理</b></div>
            </aside>
            <div className="console-chat">
              <header><b>部署检查报告</b><span>工具执行中</span></header>
              <article className="user-bubble"><small>你</small><p>检查当前项目是否适合私有部署，并输出风险项。</p></article>
              <article className="agent-bubble"><small>π · PaiCLI</small><p>我会先读取项目配置与部署说明，再生成分级检查报告。</p>
                <div className="tool-chip"><span>✓</span><b>list_dir</b><small>12 items</small></div>
                <div className="tool-chip running"><span>↻</span><b>read_file</b><small>README.md</small></div>
              </article>
              <div className="composer">给 PaiCLI 发送消息… <b>↑</b></div>
            </div>
            <aside className="console-detail">
              <header><b>执行详情</b><span>清空</span></header>
              <div><small>14:32:06 · RUN</small><b>模型请求已完成</b><p>toolCalls: 2 · tokens: 3,842</p></div>
              <div><small>14:32:08 · TOOL</small><b>list_dir 已完成</b><p>duration: 48ms</p></div>
              <div className="detail-active"><small>14:32:08 · TOOL</small><b>正在读取 README.md</b><p>参数已持久化</p></div>
            </aside>
          </div>
        </div>
      </section>

      <section className="section deploy" id="deploy">
        <div className="deploy-copy">
          <div className="section-kicker">RUN LOCALLY / 04</div>
          <h2>没有 Docker，<br />也能先跑起来。</h2>
          <p>Local 模式覆盖聊天、模型、Memory、知识库、联网、Skill 与 Artifact。只有隔离执行命令和写文件时才需要 Docker。</p>
          <ul><li><span>✓</span> Java 17</li><li><span>✓</span> Maven Wrapper 已内置</li><li><span>✓</span> 默认数据留在本机</li></ul>
          <a className="button secondary light" href={githubUrl} target="_blank" rel="noreferrer">阅读完整中文文档 <Arrow /></a>
        </div>
        <div className="terminal-card">
          <div className="terminal-head"><span><i /><i /><i /></span><b>PowerShell</b><small>LOCAL MODE</small></div>
          <pre><code><span className="comment"># 构建并运行完整测试</span>{"\n"}<span className="prompt">PS ›</span> .\mvnw.cmd clean package{"\n\n"}<span className="comment"># 启动本地模式，无需 Docker</span>{"\n"}<span className="prompt">PS ›</span> .\scripts\start-local.ps1{"\n\n"}<span className="success">✓ PaiCLI started on http://127.0.0.1:8080</span></code></pre>
          <div className="terminal-note"><span>需要执行危险工具？</span><b>.\scripts\start-docker.ps1</b></div>
        </div>
      </section>

      <section className="final-cta">
        <div className="cta-grid" aria-hidden="true" />
        <span className="brand-mark large">π</span>
        <div><small>PAICLI PLATFORM LITE</small><h2>让每一次 Agent 执行，<br />都有迹可循。</h2></div>
        <a className="button primary" href={githubUrl} target="_blank" rel="noreferrer">在 GitHub 查看项目 <Arrow /></a>
      </section>

      <footer>
        <a className="brand footer-brand" href="#top"><span className="brand-mark">π</span><span>PaiCLI</span></a>
        <p>单机 · 单租户 · 私有部署的受管 Agent Runtime</p>
        <div><a href="#features">能力</a><a href="#architecture">架构</a><a href="#deploy">部署</a><a href={githubUrl}>GitHub</a></div>
      </footer>
    </main>
  );
}
