# Delivery phases

## Phase 1 — Durable Agent Runtime

- [x] Multi-module Maven project
- [x] Session / Run / Message / Event / ToolCall schema
- [x] SQLite WAL store
- [x] Persistent worker state transitions
- [x] Tool-call idempotency boundary
- [x] REST API
- [x] Replayable SSE
- [x] Local executor
- [x] Sandbox Agent executable skeleton
- [x] Offline demo model

## Phase 2 — Docker hand execution

- [x] Docker lifecycle manager
- [x] Random per-container token and no host port exposure
- [x] Per-run workspace mount
- [x] CPU / memory / PID / timeout limits
- [x] Internal network without external routing
- [x] Write and command approval state
- [x] Approval recovery without a second model decision
- [x] JSONL audit records
- [x] Orphan container cleanup after restart
- [x] Fake Docker lifecycle contract test
- [x] Real Docker Desktop end-to-end verification

## Phase 3 — Real model and context engineering

- [x] OpenAI-compatible streaming provider
- [x] GLM / DeepSeek configuration
- [x] Static system and session context layers
- [x] Tool result truncation and LocalArtifactStore
- [x] Conversation summary with safe tool boundaries
- [x] Token usage and limits

## Phase 4 — Manual memory and product API

- [x] Explicit memory CRUD only
- [x] Project memory retrieval
- [x] API key authentication
- [x] OpenAPI documentation
- [x] Initial run timeline page (replaced by the Phase 6 chat Console)
- [x] Backup and restore command

## Phase 5 — DeepSeek V4 and real Docker acceptance

- [x] DeepSeek V4 Flash / Pro configuration
- [x] Thinking toggle and reasoning effort
- [x] Streaming `reasoning_content`
- [x] Durable reasoning round-trip across tool calls
- [x] DeepSeek prompt cache usage parsing
- [x] Legacy SQLite schema migration
- [x] Real Docker Desktop end-to-end verification

## Phase 6 — Runtime correctness and chat console

- [x] Preserve every streamed tool call in provider order
- [x] Durable sequential execution and per-tool approval
- [x] Active model HTTP/SSE cancellation
- [x] Bounded project `AGENTS.md` / `PAI.md` injection
- [x] Numbered SQLite schema migration records
- [x] Chat-oriented responsive Web Console with collapsible execution details
- [x] Buffered model delta persistence and bounded Console activity rendering
- [x] Per-Run thinking toggle and reasoning-effort controls in the Console
- [x] Persistent conversation groups, session moves, and safe session deletion

## Phase 7 — Managed capability providers

- [x] Stable Server Tool Provider SPI behind the durable ToolCall boundary
- [x] Project/global Skill discovery, bounded prompt index, on-demand `load_skill`, and validated HTTPS Git import
- [x] Multi-format project document upload, Tika extraction, persisted hybrid vector index, and `search_knowledge`
- [x] Agent-triggered `session_search` over historical project messages with BM25 and per-session summaries
- [x] Opt-in web search/fetch with bounded responses and SSRF controls
- [x] Remote Streamable HTTP MCP discovery/calls with namespaced tools and mandatory Approval
- [x] Durable Multi-Agent child Runs with ToolCall idempotency, depth limit, and fair queue ordering
- [x] Schema migrations 5–7 and Store/provider tests
- [x] Multimodal image upload, durable Run binding, bounded image processing, and OpenAI-compatible content parts
- [x] Console capability manager for Skills, knowledge documents, and image attachments
- [x] Unified chat attachment picker for images and multi-format documents with Run-bound priority RAG
- [x] Recoverable tool-failure observations: failed tool results return to the model without failing the Run
- [x] Scanned-PDF page rendering, multimodal OCR, and current-Run visual fallback
- [x] SSE starvation fix plus terminal-event close/reconciliation in Console

## Phase 8 — Enterprise-detail refinement on the single-node runtime

- [x] Structure-aware document chunks for headings, sentences, lists, tables, and fenced code
- [x] BM25 + real remote embedding retrieval, RRF fusion, overlap dedupe, and automatic context recall
- [x] Batched OpenAI-compatible embeddings with explicit offline lexical degradation
- [x] Main-model structured conversation summary with deterministic failure fallback
- [x] Durable L0-to-L1/L2/L3 automatic memory extraction, confidence filtering, revisions, and hybrid recall
- [x] Skill bundled-resource discovery and bounded `read_skill_resource`
- [x] Model retry/backoff, same-endpoint fallback model, request rate limit, per-Run step/token budgets, and usage table
- [x] MCP argument/schema budgets and failure circuit breaker
- [x] Multi-Agent child/depth limits, approved child cancellation, and descendant cancellation propagation
- [x] Capability status UI, Micrometer runtime metrics, storage health indicator, and migrations 8–9
- [x] 48 automated tests across the full Maven reactor

## Phase 9 — Operational hardening and delivery gates

- [x] Bounded Sandbox/Docker CLI output collection and timeout process-tree termination
- [x] Required-token Sandbox startup and constant-time control-channel authentication
- [x] Production API-key fail-fast, management/OpenAPI protection, Console CSP and tab-scoped key storage
- [x] Shared SQLite connection policy, isolated migration catalog and scheduled WAL maintenance
- [x] Opt-in Event/Audit retention plus orphan Artifact and stale temporary-file cleanup
- [x] Query-aware historical-message prefilter while preserving public search-count semantics
- [x] Fsync plus atomic replacement for Artifacts, attachments, knowledge documents and indexes
- [x] Checked backup/restore archives with server-state, SHA-256, traversal and SQLite-header validation
- [x] Queue/Approval/Memory/SSE gauges plus model-retry and tool-failure counters
- [x] Maven `-Xlint:all`, GitHub Actions verification, Dependabot and CycloneDX SBOM
- [x] 57 automated tests across Common, Server and Sandbox Agent boundaries

## Explicit non-goals

- Kubernetes, MicroVM, multi-region, multi-tenant
- Kafka, Redis, PostgreSQL, MinIO
- Cross-project memory dreaming/association graphs
- Autonomous planner/reviewer Agent Team and complex team Console
- Local stdio MCP process management and browser automation
- External vector database in the default Lite profile
- Audio/video understanding and historical replay of raw image attachments
