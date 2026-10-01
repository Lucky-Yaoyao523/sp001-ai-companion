# SP001 AI Companion

**A discontinued Spider-Man toy, its original hardware, and new conversations in Chinese.**

[中文](README.md) · [Get started](#get-started) · [Integration guide](docs/integration.md)

The microphone, speaker, and expressive eyes are already there. This project explores what that hardware can do with current speech and language services: hear a new question, tell a new story, continue a conversation, and retrieve preferences that were actually saved.

The prototype runs on the original Sphero Spider-Man SP001 board. This repository shares our authored code, offline regression cases, and integration experience.

**This is a developer source edition. The local parent console can run on a computer; the toy components require your own device integration. No ready-to-flash APK or firmware is included.**

## Why keep working on an old toy?

An interactive toy depends on its hardware and the software ecosystem around it. After SP001 was discontinued, the retirement of its original app and services made setup and continued use difficult. Community projects such as [Second Life Toys](https://github.com/second-life-toys/second-life-toys) describe this problem and share recovery work.

Our contribution explores Chinese AI interaction on the original hardware. The board, microphone, speaker, eyes, and enclosure stay in use. New speech and model services connect over Wi-Fi, bringing new conversations through the existing physical toy.

Talking to something beside you creates concrete requirements. You can ask a follow-up, change a story, or interrupt halfway through an answer. The entire speech pipeline has to handle that behavior.

Those requirements shaped the project, one problem at a time.

## First: get a complete conversation through the hardware

```text
Original microphone → lightweight client → Wi-Fi → ASR and cloud dialogue
                                                       ↓
Original speaker ← streaming playback ← speech synthesis and the reply
```

The old device handles capture, playback, session state, and bounded hardware actions. Cloud services handle transcription, language understanding, answer generation, and requested lookups. The configured prototype can chat over Wi-Fi without a computer or phone staying connected. The computer-based parent console is optional; cloud conversation still requires a network connection and the user's own service accounts.

The prototype supports Chinese multi-turn conversation, stories, explanations, and translation. Current adapters include Qwen recognition and MiniMax dialogue and speech synthesis. Their implementations are included in the source.

Getting sound through the system was the beginning of the work.

## Then: make the conversation continue

People pause, add detail, correct themselves, and change their minds. The toy's own speaker also feeds sound back into its microphone. A simple sequence of API requests can turn these conditions into missed speech, overlapping turns, long waits, or an old answer still playing when a new question arrives.

The project includes capture windows, echo handling, interruption confirmation, playback cancellation, and session lifecycle management. The model interprets the full request and selects tools. The device executes a limited action and returns the actual result.

Two practical requirements guide this boundary:

- A volume request needs a device action and a receipt. A spoken promise is not evidence that the setting changed.
- Ending a conversation needs to end the listening session. A farewell spoken by a story character or a word being translated must preserve the user's actual request.

These requirements became formal tool calls, receipts, and cancellation handling. Prototype use has produced reports of improved interruption behavior; reliability across different acoustic conditions still needs more physical testing.

## The answer was generated. Why did the toy stop speaking?

One important investigation found that reply text was being lost in transit. Two defects were reproducible:

1. After parsing a valid reply envelope, the client ignored additional ordinary text that followed it.
2. After a tool round trip, the client returned at the first completed assistant message and missed later assistant messages.

Both could sound like a suddenly shortened answer. Diagnosing them required comparing what the provider produced, what the client retained, and what playback received.

The premature returns were fixed, and those failure shapes are preserved in [NativeReplyCompletenessTest](core-patch/test/NativeReplyCompletenessTest.java). Two real-provider text-level integration cases also checked complete delivery. Their tool and playback ports were simulated, so that evidence establishes text delivery rather than physical speaker acceptance.

The useful debugging method is to trace the reply from provider output through the client and playback state, locating the first point where text disappears. Cancellation, network interruption, and playback failures remain separate causes to investigate.

## Remember preferences, and make use visible

As a conversation continues, another question appears: which information survives the current turn, the next session, or a restart?

The project separates recent conversational context from persistent local memory. Stable preferences can be stored in application-private storage. An explicit save request requires a save receipt before the assistant has grounds to say it was persisted. The memory components also provide viewing, deletion, and profile separation. The storage and recall mechanisms are available for reuse; accurate long-term recall still needs continued validation.

The parent console adds visibility into sessions, duration, complete or partial answers, tool results, and errors. Transcript retention is off by default and must be selected explicitly.

When the computer backend is temporarily unavailable, the toy can retain pending events and catch up when the backend returns. Backend downtime and catch-up were physically tested on the prototype. Authentication, certificate validation, durable cursors, and deduplication support this path. Moving a backend requires transferring its records and cursor together; one durable backend owns synchronization for a toy.

**Backend downtime catch-up has test evidence. A toy without network access cannot use cloud services to generate new replies.**

## What the current components support

These are existing mechanisms and prototype capabilities. Using the source requires your own hardware adaptation and service credentials.

| Capability | What it provides | Evidence and remaining work |
|---|---|---|
| Chinese conversation | Follow-ups, stories, explanations, translation | Runs on the prototype; factual accuracy, names, and conversational quality need improvement |
| Streaming replies | Incremental reception and playback, continued text, multiple post-tool messages | Two reply-loss defects fixed with regression coverage; cancellation and transport failures remain separate |
| Interruption and session control | Confirmed interruption, old-playback cancellation, new capture, session ending | Improvement reported in prototype use; broad acoustic acceptance is incomplete |
| Hardware tools and expression | Volume query/change, eye expression, capture settings, initiative controls | Bounded actions and actual receipt paths; depends on the original runtime and physical checks |
| Weather and search | Model-selected lookups and result-based answers | Interfaces included; weather requires an explicit city, and network requests can fail |
| Local preference memory | Save, retrieve, inspect, delete, and separate profiles | Private application storage and offline regression; reliable long-term recall needs more evidence |
| Parent console | Usage, partial replies, errors, transcript retention controls | Runs locally, starts empty, offers explicit synthetic demo loading |
| Journal synchronization | Authenticated retrieval, durable storage, deduplication, catch-up | Prototype backend downtime tested; public edition requires explicit own-device pairing configuration |
| Usage limits | Daily time/session limits, quiet hours, pause/resume, command confirmation state | Software logic and expired-command rejection tested; complete physical acceptance of effective rules is still pending |

Example integration requests could include a story set in a lunar greenhouse, a volume query after changing one step, saving a preference for paper folding, an explicit city/date weather query, and ending the current chat. These are newly authored illustrations, not family transcripts or verbatim recordings of tests.

## What you can take from this repository

The source provides a reference across speech transport, model tools, device receipts, memory, and usage records. Regression cases preserve previously encountered failure shapes, so later changes can be checked for lost text, wrong tool execution, and confused cancellation state.

| Area | Start reading |
|---|---|
| Requests and tool definitions | [NativeDialogueProtocol](core-patch/src/org/sp001/core/NativeDialogueProtocol.java) |
| Reply reception and completeness | [NativeDialogueEngine](core-patch/src/org/sp001/core/NativeDialogueEngine.java), [ReplyEnvelopeStream](core-patch/src/org/sp001/core/ReplyEnvelopeStream.java) |
| Capture, playback, interruption | [OwnerNativeConversation](core-patch/src/org/sp001/core/OwnerNativeConversation.java), [OwnerDuplexCapture](core-patch/src/org/sp001/core/OwnerDuplexCapture.java) |
| Persistent preferences | [CompanionMemory](core-patch/src/org/sp001/core/CompanionMemory.java), [OwnerCompanionMemory](core-patch/src/org/sp001/core/OwnerCompanionMemory.java) |
| Durable events and catch-up | [ParentJournalState](core-patch/src/org/sp001/core/ParentJournalState.java), [toy-pull.mjs](parent-console/toy-pull.mjs) |
| Local parent UI | [parent-console/](parent-console/) |

The initial source release compiled **95 production Java source files** and passed **57 Node checks and nine Java offline regression groups**. These results cover compilation and software behavior. Installation, microphone capture, audible playback, and multi-day physical use have separate acceptance requirements.

The parent console and offline mock can be explored on a computer first. Android components use adapters for the original SP001 runtime. Individual components can also be studied when building an adapter for other hardware.

## Get started

Install [Node.js 22 or newer](https://nodejs.org/), then:

```sh
git clone https://github.com/Lucky-Yaoyao523/sp001-ai-companion.git
cd sp001-ai-companion
npm start
```

Open the local URL printed in the terminal, normally `http://127.0.0.1:8787/`, and enter the fresh access code. The database starts empty. Clearly labelled synthetic demo records can be loaded explicitly.

Windows can use `START-PARENT.cmd`; Mac/Linux can run `sh START-PARENT.command`. Node.js must be installed separately.

```sh
npm run voice:demo
npm test
npm run check:privacy
```

Default entry points do not connect to a toy, call paid models, or use a microphone. See [development](docs/development.md) for Java compilation and [integration](docs/integration.md) for device adaptation, configuration, and synchronization ownership.

## Work still ahead

Factual accuracy, name understanding, natural conversation, partial-answer cancellation diagnosis, and long-session behavior need improvement. Hardware, room, distance, and network variations require more real-world evidence.

A useful contribution can be a sanitized reproducible failure, a regression case, a hardware adapter, or clearer setup documentation. Please follow [CONTRIBUTING.md](CONTRIBUTING.md) before sharing diagnostics.

The aim is to keep discontinued hardware useful and give people building speech companions concrete experience to build on.

## Privacy, distribution, and community

Family identities, ages, locations, conversations, memories, Wi-Fi details, device identities, credentials, private certificates, and recordings are excluded. Examples are disabled with empty credentials. There is no household weather default. BLE remote provisioning and capture-start entry points are disabled in the public copy; private prototype data and original project history were not exported.

Enabled cloud speech sends audio and text to the user's selected providers. `store:false` is not a guarantee of provider non-retention. Parent transcript storage is off by default; no automatic external summary service is wired in. See [privacy](PRIVACY.md) and [security](SECURITY.md).

No vendor APK, extracted assets, character audio, signing private keys, firmware recovery tools, or debug-access instructions are distributed. Use your own lawful device development environment.

Community references include [Second Life Toys](https://github.com/second-life-toys/second-life-toys), [SpheroRevived](https://github.com/Ric-614/SpheroRevived), and [Sphero-Spiderman](https://github.com/helenclarko/Sphero-Spiderman). Their application packages, proprietary code, and assets are not copied into this repository.

Our authored code and documentation use the [MIT License](LICENSE). Dependencies are installed separately; see [third-party notices](THIRD_PARTY_NOTICES.md). This independent community project is not affiliated with or endorsed by Sphero, Marvel, or Disney. Trademarks identify the compatible device.
