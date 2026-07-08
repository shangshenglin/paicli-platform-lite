# Architecture

## Deployment boundary

The platform uses one Spring Boot server process and one SQLite database. `paicli-sandbox-agent` is a separate executable boundary used by Docker mode.

```text
Client
  -> REST/SSE
PaiCLI Server
  -> SQLite: Session / Run / Message / Event / ToolCall
             Approval / Artifact / Layered Memory / Model Usage
  -> ModelClient
       -> DemoModelClient
       -> OpenAiCompatibleModelClient (SSE)
  -> ContextManager -> Prompt layers / Memory / Compaction / Token budget
  -> ToolRouter -> SandboxDriver
                         -> LocalSandboxDriver (Phase 1)
                         -> DockerSandboxDriver
                -> ServerToolProvider
                         -> Skill / Knowledge / Web / MCP / Delegation
```

## Recovery contract

1. A run is committed as `QUEUED` before a worker can see it.
2. A worker atomically changes it to `RUNNING`.
3. The assistant message and every tool call from the model turn are committed together before execution.
4. Tool results are committed before the run is queued for its next model step.
5. Expected tool-level failures are committed as failed ToolCalls plus tool-role observations, then the Run is re-queued so the model can correct arguments or choose an alternative. Approval denial, cancellation, and unexpected Runtime exceptions remain terminal.
6. On startup interrupted `RUNNING`, `WAITING_MODEL`, and `WAITING_TOOL` runs return to `QUEUED`.
7. A completed tool call is reused by idempotency key rather than executed again.

This is intentionally event-backed state, not full event sourcing: current state stays in relational tables while `run_events` provides replay and diagnosis.

## Product boundary

All `/v1/**` endpoints are protected when `PAICLI_API_KEY` is configured. The browser page is a single-tenant chat Console with a separate execution-detail stream and sends the same API key header; it is not a multi-tenant enterprise Console. Conversation groups are persisted in `session_groups`; deleting a group moves its sessions to the ungrouped section. Deleting a Session is rejected while it has an active Run and otherwise removes its dependent runtime records. OpenAPI is available at `/docs`.

Memory extraction is asynchronous and durable. A completed Run first creates a `memory_extractions` row; the worker then extracts typed L1/L2/L3 units with confidence and source metadata. Same-key changes retain old values in `memory_revisions`. Retrieval is query-aware and combines lexical/semantic relevance, confidence, time decay and stable L3 preferences. Explicit CRUD remains the correction/deletion boundary. Large tool results are stored under `data/artifacts` and replaced in model history by a bounded preview plus artifact id.

DeepSeek V4 thinking output follows the same durable boundary as tool calls: `reasoning_content`, assistant `tool_calls`, and the matching tool result are persisted before the next model request. The context builder restores all three fields together. DeepSeek traffic is forced to HTTP/1.1 for stable long-lived SSE behavior, while other OpenAI-compatible providers keep the generic transport behavior.

Thinking is selectable per Run from the Console or create-Run API. `disabled` is the fast path; `enabled` accepts `high` or `max` reasoning effort. Stream deltas are buffered into bounded event batches before SQLite persistence, while the browser merges display updates on animation frames. This keeps durable replay without turning token-sized chunks into synchronous database writes or thousands of DOM nodes.

All tool calls from one model turn are preserved. They execute sequentially in provider order so a later call cannot bypass an earlier approval. Canceling a Run closes the active model future and response stream; Docker cancellation also removes the Run container.

Project rules are bounded context rather than autonomous memory. The assembler reads global, project-key, and Run-workspace `AGENTS.md` / `PAI.md` layers under controlled data roots, with more specific layers taking precedence.

## SQLite constraints

SQLite is suitable because this edition is single tenant and single node. WAL mode permits concurrent readers while writes remain short. The design keeps repository interfaces local so a future PostgreSQL adapter does not change the Agent Runtime.

`schema_migrations` records numbered, idempotent schema levels. Existing pre-version databases are upgraded in place and registered without deleting user data. Versions 5–7 add delegation, fair queues and multimodal attachments; version 8 adds layered Memory jobs/revisions; version 9 adds per-turn model usage. Session deletion removes these dependent rows in the same transaction.

## Phase 7 server tool providers

RAG, Skill, web access, MCP, and Multi-Agent delegation are Server-side tool providers. Their model-visible calls still enter the existing pipeline: the complete model turn is committed first, calls execute in provider order, results pass through the Artifact materializer, and Event/SSE/Audit remain authoritative. Providers do not call the model or Sandbox behind the runtime's back.

- Skills are read only from controlled global/project roots, sorted by stable name, and loaded on demand through `load_skill`. Bundled references/templates are listed but only read through bounded, traversal-safe `read_skill_resource`. Git import stages, validates symlinks/file budgets, and never executes repository code.
- RAG stores explicit project documents under `data/projects/{projectKey}/knowledge`. Tika extracts text; image-only PDFs are rendered by PDFBox and transcribed by the configured multimodal model before entering the same chunk/index path. Chunks preserve heading paths, sentences, lists, tables and fenced code. BM25 and real Ollama/OpenAI-compatible embeddings are ranked independently and fused with RRF, boosts, overlap dedupe and per-document quotas. Context assembly automatically retrieves top evidence each turn. With no embedding service, the system explicitly uses lexical-only degradation.
- Web access is opt-in. Search calls a configured SearXNG-compatible JSON endpoint; fetch revalidates every redirect and rejects loopback, link-local, and private targets.
- Remote HTTP MCP servers are configured in ignored local data. Server-held headers can reference environment variables; schema, arguments and responses are bounded, consecutive failures open a short circuit, and every MCP tool requires durable Approval.
- `spawn_agent` requires Approval and atomically creates a delegation plus internal child Session/Run. It is idempotent by parent ToolCall, limited by depth/child count, and descendant cancellation propagates. Child Runs use the normal worker and recovery paths; no second in-memory agent runtime exists.

## Model gateway and observability

The OpenAI-compatible client rate-limits requests and retries only before an SSE response is accepted, so streamed deltas are never duplicated. Retriable HTTP failures use exponential backoff and may fall back to a configured model on the same endpoint. Per-Run step/token budgets are checked before new model calls, while already persisted resumable ToolCalls still execute. Every turn writes `model_usage`; Actuator exposes bounded Micrometer counters/timers and a storage/disk health indicator. Run id remains the trace correlation key across Event, Audit, ToolCall and Artifact records.

## Multimodal and document input

The Console uploads up to four PNG/JPEG/GIF images as staged Session attachments. The Server validates actual image bytes, bounds source size, flattens alpha, and resizes/re-encodes oversized inputs before writing them under `data/input-attachments`. Creating a Run atomically binds the staged attachment ids to its user Message; an attachment cannot be reused by another Run. Only the current Run's user images are loaded into `ModelMessage` and serialized as OpenAI-compatible `image_url` content parts. Historical image bytes are not repeatedly injected, model credentials and images never enter Sandbox, and Session deletion removes attachment rows and files with the other runtime state.

The same picker accepts text, Markdown, PDF, Office, CSV, HTML, JSON, XML, RTF, EPUB and OpenDocument files. A document upload is extracted by Tika, structure-chunked and indexed under the Session project before its generic attachment row is staged. Binding that id to a Run makes the document an explicit priority RAG source. Query-specific evidence is preferred; if the request is generic (for example “summarize the attachment”), representative chunks are sampled across the attached document. Normal documents are never sent wholesale to the model. If a PDF has no text layer, the Server renders bounded pages and uses the configured multimodal model for OCR. When OCR is unavailable, a chat upload is retained as a visual-PDF attachment and its bounded page images are injected only into the current Run; it is not falsely reported as indexed.

SSE streaming uses a zero-capacity executor handoff so long-lived subscriptions expand to the configured maximum instead of sitting behind two core threads. The Console treats persisted terminal events as an explicit close signal, cancels the reader immediately, reconciles status through `GET /v1/runs/{id}`, and reconnects only while the Run remains non-terminal. This keeps terminal UI state independent of TCP close timing.

## Sandbox boundary

`LocalSandboxDriver` is explicitly a development executor, not a security sandbox. Docker mode starts one restricted container per active Run and keeps the workspace on the host. The container remains on a Docker `--internal` network with no published port; the Server uses `docker exec` to call the authenticated Sandbox Agent HTTP endpoint on the container loopback interface. Model credentials remain in the Server process and are never placed in the sandbox environment.

Dangerous tools create a durable Approval row before any container call. Approval moves the same persisted ToolCall back to the worker; the model is not asked to recreate the action. This preserves the exact arguments the user reviewed.

On restart, `RUNNING` tool calls become `REQUESTED`, interrupted Runs become `QUEUED`, and managed orphan containers are removed. The worker resumes the persisted tool call directly.
