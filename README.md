# OnGrid

<p align="center">
  <img src="assets/onGridLogo.png" width="120" alt="OnGrid logo" />
</p>

An Android app that discovers [Ollama](https://ollama.com) servers on your local Wi-Fi network, lets you chat with any model they expose, and wires those models up to [Model Context Protocol (MCP)](https://modelcontextprotocol.io) tool servers so the AI can take real actions.

## Features

- **Automatic server discovery** — scans your local `/24` subnet for Ollama instances on port `11434` and shows them as soon as they are found. Manual entry is available as a fallback.
- **Model selection** — lists every model available on a discovered server and lets you switch models mid-conversation.
- **Streaming chat** — responses are streamed token-by-token using Ollama's `/api/chat` endpoint. A foreground service keeps the connection alive when the app is backgrounded.
- **MCP tool integration** — add any number of MCP servers (HTTP or SSE transport). OnGrid discovers their tool list via the JSON-RPC `tools/list` method and exposes those tools to the active Ollama model as native function-calling tools. When the model requests a tool call, OnGrid executes it against the MCP server and feeds the result back automatically.
- **Conversation history** — all chats are persisted locally in a Room database and listed on the Conversations screen for easy recall.
- **Persistent settings** — saved Ollama servers and their cached model lists are stored with DataStore so they survive restarts.

## Screens

| Screen | Purpose |
|---|---|
| **Discovery** | Scan for / manually add Ollama servers; view available models; navigate to MCP settings |
| **Conversations** | Browse and resume past conversations |
| **Chat** | Streaming chat with the selected model; inline tool-call indicators; MCP tool sheet |
| **MCP Servers** | Add, enable/disable, refresh, and delete MCP tool servers |

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM — `ViewModel` → `Repository` → data sources
- **Navigation**: Navigation Compose
- **Networking**: OkHttp 4 (standard requests + SSE via `okhttp-sse`)
- **Serialization**: Gson
- **Local storage**: Room (conversations & messages), DataStore Preferences (server list)
- **Async**: Kotlin Coroutines + Flow
- **Build**: Gradle with version catalog (`gradle/libs.versions.toml`), KSP for Room

## Requirements

- Android device or emulator running **Android 8.0 (API 26)** or higher
- The device must be on the **same Wi-Fi network** as the Ollama server(s)
- Ollama running with its API accessible on port `11434` (default)
- *(Optional)* One or more MCP tool servers accessible over HTTP/SSE from the device

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
| `POST_NOTIFICATIONS` | Show a notification while an AI response is being fetched (Android 13+) |

## MCP Transport Support

OnGrid supports both transports defined by the MCP specification:

- **HTTP (Streamable HTTP)** — POST requests to the server URL
- **SSE** — connects to a `…/sse` endpoint; the server sends a `endpoint` event pointing to a session-specific POST URL; subsequent JSON-RPC calls are POSTed there and responses arrive over the event stream

