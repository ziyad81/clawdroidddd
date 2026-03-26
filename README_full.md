# ClawDroid 🦞

**Fully open-source Android AI agent. Like OpenClaw, but runs entirely on your phone.**

Control your Android device with any LLM — Claude, Gemini, GPT, Groq, Ollama, LM Studio, or any OpenAI-compatible API. No cloud required.

---

## 🏗️ Full Project Structure

```
app/src/main/java/com/clawdroid/
│
├── accessibility/
│   ├── ClawAccessibilityService.kt   ← Core: reads screen + gestures
│   └── DeviceController.kt           ← High-level API wrapper
│
├── llm/
│   └── LLMClient.kt                  ← Unified client: 8 providers + vision
│
├── agent/
│   ├── PowerAgentCore.kt             ← Full agent loop (memory + vision + skills)
│   └── AgentCore.kt                  ← Simple loop (backwards compat)
│
├── tools/
│   ├── FullToolRegistry.kt           ← 40+ tools: screen, tap, SMS, contacts...
│   ├── DeviceToolsExtended.kt        ← Contacts, SMS, calendar, files, apps
│   └── ToolRegistry.kt               ← Original basic tools
│
├── memory/
│   └── MemoryStore.kt                ← Persistent facts + task history + workspace
│
├── skills/
│   └── SkillsEngine.kt               ← OpenClaw-style reusable task scripts
│
├── vision/
│   └── ScreenshotEngine.kt           ← Screen capture → bitmap → LLM vision
│
├── voice/
│   └── VoiceEngine.kt                ← TTS (ElevenLabs/system) + STT
│
├── gateway/
│   └── GatewayClient.kt              ← WebSocket node for OpenClaw gateway
│
├── service/
│   └── AgentForegroundService.kt     ← Keeps agent alive in background
│
├── scheduler/
│   └── HeartbeatScheduler.kt         ← OpenClaw-style proactive tasks (WorkManager)
│
├── settings/
│   └── SettingsRepository.kt         ← DataStore: API keys, config
│
├── ui/
│   └── MainActivity.kt               ← Compose chat UI (3 tabs)
│
├── BootReceiver.kt                   ← Auto-restart on reboot
└── ClawDroidApp.kt                   ← Application class
```

---

## ⚡ Features

### 🤖 AI Agent
- Full reasoning loop: task → LLM → tool → result → repeat
- Vision support: take screenshot → send to LLM → AI describes/reads screen
- Memory: long-term facts persist across sessions
- Skills: reusable task scripts (like OpenClaw's AgentSkills)
- Up to 30 reasoning steps per task
- Token usage tracking

### 🔧 40+ Tools
| Category | Tools |
|---|---|
| Screen | read_screen, get_interactive_elements, capture_screenshot |
| Gestures | tap, tap_text, tap_id, swipe, scroll, long_press |
| Typing | type_text, tap_and_type, clear_text |
| System | press_back, press_home, press_recents, open_notifications |
| Apps | open_app, list_apps, is_app_installed, open_url, open_settings |
| Contacts | search_contacts, get_all_contacts |
| SMS | read_sms, send_sms |
| Calls | get_call_log, dial_number, make_call |
| Calendar | get_calendar_events, add_calendar_event |
| Files | list_files, read_file, write_file, delete_file |
| Memory | save_memory, get_memory, list_memories, delete_memory |
| Voice | speak (ElevenLabs/system TTS), listen (STT) |
| Device | get_device_info, share_text |
| Skills | list_skills, create_skill |

### 🧠 Memory System (like OpenClaw)
- **Long-term facts**: "User's name is Ziyad", "preferred alarm is 7am"
- **Task history**: every completed task is logged
- **Workspace files**: agent can create/read/write files
- **HEARTBEAT.md**: proactive task checklist (like OpenClaw)
- All stored as plain JSON/Markdown files — inspectable, backuppable

### 🛠️ Skills Engine (like OpenClaw AgentSkills)
Built-in skills:
- `morning-briefing` — daily summary of calendar + battery + notifications
- `send-whatsapp` — guided WhatsApp message flow
- `take-screenshot-describe` — screenshot + visual description
- `remember-fact` — save user preferences
- `battery-check` — battery status + advice
- `open-and-search` — open app and search
- `daily-report` — summary of calls, SMS, calendar

Agent can create new skills automatically with `create_skill` tool.

### 📡 LLM Providers

| Provider | Free? | Vision? | Local? |
|---|---|---|---|
| Claude (Anthropic) | ❌ | ✅ | ❌ |
| Gemini (Google) | ✅ (free tier) | ✅ | ❌ |
| GPT (OpenAI) | ❌ | ✅ | ❌ |
| Groq | ✅ (free tier) | ❌ | ❌ |
| OpenRouter | ✅ (some models) | ✅ (some) | ❌ |
| Ollama | ✅ 100% free | ✅ (with llava) | ✅ |
| LM Studio | ✅ 100% free | ✅ (with llava) | ✅ |
| Custom API | depends | depends | depends |

### 🌐 OpenClaw Gateway Mode
Connect to an OpenClaw Gateway as a node:
- Receive tasks from Telegram, WhatsApp, Discord, Signal
- Device pairs over WebSocket (ws://gateway:18789)
- Protocol matches OpenClaw's node spec
- Auto-reconnects after reboot

### 🔔 Heartbeat Scheduler (like OpenClaw)
- Runs in background every N minutes (WorkManager)
- Reads HEARTBEAT.md from workspace
- Finds unchecked items → notifies user
- Default: every 30 minutes

---

## ⚙️ Setup

### 1. Clone & open in Android Studio
```bash
git clone https://github.com/yourname/ClawDroid
```

### 2. Build & install (API 26+, Android 8+)
```
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Enable Accessibility Service ← CRITICAL
```
Settings → Accessibility → Installed Services → ClawDroid Agent → Enable
```

### 4. Add API key
Open app → Settings tab → choose provider → paste API key

**Free options:**
- **Gemini**: [aistudio.google.com](https://aistudio.google.com) → Get API key (free)
- **Groq**: [console.groq.com](https://console.groq.com) → Free, very fast
- **Ollama**: Run locally on any machine on same WiFi

---

## 🗺️ Roadmap

- [x] Phase 1: Accessibility Service + Device Control (40+ tools)
- [x] Phase 2: Multi-LLM support (8 providers)
- [x] Phase 3: Vision (screenshot → LLM)
- [x] Phase 4: Memory + Skills (like OpenClaw)
- [x] Phase 5: Voice (ElevenLabs + system TTS/STT)
- [x] Phase 6: Gateway mode (OpenClaw node protocol)
- [x] Phase 7: Heartbeat scheduler
- [ ] Phase 8: Voice wake word (Vosk offline)
- [ ] Phase 9: Floating overlay UI
- [ ] Phase 10: Telegram/WhatsApp channel without gateway

---

## 📄 License

MIT — use freely, contribute back!
