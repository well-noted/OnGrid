# OnGrid

<p align="center">
  <img src="assets/onGridLogo.png" width="120" alt="OnGrid logo" />
</p>

An Android app that discovers [Ollama](https://ollama.com) servers on your local Wi-Fi network, lets you chat with any model they expose, wires those models up to [Model Context Protocol (MCP)](https://modelcontextprotocol.io) tool servers so the AI can take real actions, and hosts a persistent **agentic workspace** where named AI agents maintain long-term memory, autonomous behaviors, and rich built-in tools.

## Features

### Core Chat
- **Automatic server discovery** — scans your local `/24` subnet for Ollama instances on port `11434` and shows them as soon as they are found. Manual entry is available as a fallback.
- **Model selection** — lists every model available on a discovered server and lets you switch models mid-conversation.
- **Streaming chat** — responses are streamed token-by-token using Ollama's `/api/chat` endpoint. A foreground service keeps the connection alive when the app is backgrounded.
- **Markdown rendering** — assistant responses are rendered with full Markdown formatting (headings, code blocks, bold/italic, lists).
- **Token usage indicator** — displays prompt + generated token counts after each turn; warns when the context window is approaching capacity.
- **Time/date awareness** — the current date and time are automatically injected into every conversation so the model is always grounded in the present.
- **Thinking Mode** — enable extended reasoning ("thinking") for models that support it; configure the token budget (up to 32 768 tokens) and watch live thinking output stream in-chat.
- **MCP tool integration** — add any number of MCP servers (HTTP or SSE transport). OnGrid discovers their tool list via the JSON-RPC `tools/list` method and exposes those tools to the active Ollama model as native function-calling tools. When the model requests a tool call, OnGrid executes it against the MCP server and feeds the result back automatically.
- **Conversation history** — all chats are persisted locally in a Room database and listed on the Conversations screen for easy recall.
- **Persistent settings** — saved Ollama servers and their cached model lists are stored with DataStore so they survive restarts.

### Agentic Workspace
- **Named agents** — create persistent AI agents with a name, role, avatar, and a custom system prompt. Each agent maintains its own long-term memory store and a living *brief* that summarises its state across conversations.
- **Pinned memories** — agents can call the built-in `form_memory` tool mid-conversation to pin important facts to their permanent memory. Memories survive restarts and are injected into every new conversation with that agent.
- **Semantic memory recall** — when enabled, past conversations are chunked, embedded via Ollama's `/api/embed` endpoint, and stored locally. At the start of each new conversation the most semantically relevant chunks are retrieved using in-process cosine similarity and surfaced to the model.
- **Recent context** — optionally inject a summary of recent conversation turns into the context window of a new chat to provide continuity.
- **Mood tracking** — agents can track their current emotional tone (e.g. *Curious*, *Frustrated*, *Excited*) across conversations. Mood is reflected in responses and is recalculated by the utility model after each exchange.
- **Auto-brief updates** — after every conversation, the utility model automatically rewrites the agent brief to capture what happened, outstanding tasks, and any decisions made.
- **Behavioral settings** — fine-tune per-agent cognition: toggle dreaming, mood tracking, and auto-brief; set the maximum context token budget.
- **Home screen shortcuts** — the launcher exposes a dynamic shortcut for each active agent so you can jump straight into a conversation from the home screen.

### Dream Protocol
- **Background memory processing** — WorkManager schedules periodic and on-demand *dream cycles* during which the utility model deduplicates overlapping memories, synthesises them into crisp facts, and removes stale entries.
- **Dream schedules** — configure per-agent dream triggers: a specific time of day, or automatically when the device connects to Wi-Fi.
- **Dream logs** — a rolling log of each dream cycle (what was changed, merged, or deleted) is visible inside the Agent detail screen.
- **Mood refresh** — when mood tracking is enabled, the dream cycle re-evaluates the agent's mood from recent conversation turns.
- **Brief maintenance** — tasks in the agent brief that have been open longer than three days are flagged for review during the dream cycle.

### Skills
- **Import skills** — load `.skill` or `.zip` files (containing a `SKILL.md` manifest) directly from your device. Each skill provides the model with a named set of instructions or capabilities.
- **Activate skills** — toggle skills on or off per conversation; active skill content is injected into the system context at chat start.
- **`use_skill` tool** — the model can call `use_skill` during a conversation to self-activate a skill from the library, enabling dynamic capability loading based on context.
- **Skill suggestions** — the utility model can automatically suggest relevant skills based on conversation content.

### Projects & Project Memories
- **Projects** — organise conversations around named projects.
- **Automatic memory extraction** — after each conversation the utility model scans the exchange for noteworthy facts and stores them as project memories, building a growing knowledge base for each project.
- **Project detail view** — browse, review, and delete project memories from the dedicated Project detail screen.

### Built-in Tools
Agents have access to a set of built-in tools that do not require an external MCP server:

| Tool | Description |
|---|---|
| `form_memory` | Pin a fact to the agent's long-term memory store (agent workspace only) |
| `fetch_url` | Fetch and return the readable text content of a webpage (powered by Jsoup) |
| `web_search` | Search the web for facts and general knowledge via DuckDuckGo (no API key required) |
| `use_skill` | Activate a skill from the local library mid-conversation (agent workspace only) |

### Utility Agent
A secondary Ollama model (configured separately in Settings) handles all background AI tasks so the primary chat model is not interrupted:
- Generating conversation titles
- Extracting project memories
- Updating agent briefs
- Computing mood
- Tagging conversations
- Running dream-cycle memory deduplication
- Suggesting related skills and conversations

### Direct Share
- **Android Share Sheet integration** — share text or content from any app directly into OnGrid. A bottom sheet lets you choose which agent or conversation receives the shared content.

## Screens

| Screen | Purpose |
|---|---|
| **Main (tabs)** | Home screen with tabbed access to Conversations, Agents, and Projects |
| **Discovery** | Scan for / manually add Ollama servers; view available models |
| **Conversations** | Browse, search, and resume past conversations |
| **Chat** | Streaming chat; inline tool-call indicators; thinking panel; MCP tool sheet; skill activations |
| **MCP Servers** | Add, enable/disable, refresh, and delete MCP tool servers |
| **Settings** | Configure the utility agent model, toggle auto-features, set global defaults |
| **Agent List** | Browse all agents; create a new agent |
| **Agent Detail** | View and edit agent profile, memories, dream logs, behavioral settings, and dream schedules |
| **All Agents** | At-a-glance overview of every agent and their current status |
| **Project List** | Browse all projects |
| **Project Detail** | Review and manage memories extracted for a project |
| **Share Target** | Receive shared content from other apps and route it to an agent or conversation |

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM — `ViewModel` → `Repository` → data sources
- **Navigation**: Navigation Compose
- **Networking**: OkHttp 4 (standard requests + SSE via `okhttp-sse`), Jsoup (webpage parsing)
- **Serialization**: Gson
- **Local storage**: Room (conversations, messages, agents, agent memories, projects, project memories, conversation embeddings, dream logs, dream schedules, skills), DataStore Preferences (server list & settings)
- **Background work**: WorkManager (dream cycles), `AlarmManager` (time-of-day dream schedules)
- **Markdown rendering**: Compose Markdown (Material 3 theme)
- **Async**: Kotlin Coroutines + Flow
- **Build**: Gradle with version catalog (`gradle/libs.versions.toml`), KSP for Room

## Requirements

- Android device or emulator running **Android 8.0 (API 26)** or higher
- The device must be on the **same Wi-Fi network** as the Ollama server(s)
- Ollama running with its API accessible on port `11434` (default)
- *(Optional)* One or more MCP tool servers accessible over HTTP/SSE from the device
- *(Optional)* A second Ollama model designated as the **utility agent** for background tasks (title generation, memory extraction, dream cycles, etc.)

## Building

```bash
# Debug APK
./gradlew assembleDebug

# Install directly to a connected device
./gradlew installDebug
```

The project uses `local.properties` for SDK path configuration (generated automatically by Android Studio).

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | Connect to Ollama and MCP servers |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Determine the local subnet for scanning |
| `CHANGE_WIFI_MULTICAST_STATE` | Required for subnet broadcast operations |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keep the streaming HTTP connection alive in the background |
| `POST_NOTIFICATIONS` | Show a notification while an AI response is being fetched or a dream cycle is running (Android 13+) |

## MCP Transport Support

OnGrid supports both transports defined by the MCP specification:

- **HTTP (Streamable HTTP)** — POST requests to the server URL
- **SSE** — connects to a `…/sse` endpoint; the server sends an `endpoint` event pointing to a session-specific POST URL; subsequent JSON-RPC calls are POSTed there and responses arrive over the event stream

