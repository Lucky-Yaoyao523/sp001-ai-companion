# SP001 AI Companion

**New conversations in Chinese. The same Spider-Man hardware.**

[中文](README.md) · [Capabilities](#what-it-supports) · [Get started](#get-started) · [Integration guide](docs/integration.md)

The Sphero Spider-Man SP001 already has a microphone, a speaker, and expressive eyes. After its original app and services retired, setting up and continuing to use that hardware became difficult. This project keeps the original board and enclosure, connecting Chinese speech recognition, a language model, and speech synthesis so new replies come through the original speaker.

Once it could speak, conversation exposed the next set of problems. A new question could arrive while a story was playing. A complete model reply could lose its ending inside the client. A promise to remember something needed a successful save behind it. Capture, dialogue, playback, and memory had to work together.

The prototype runs on the original device. This repository shares our authored source, regression cases, and integration experience as a **developer source edition**. You can explore the parent console on a computer now; using the toy components requires your own hardware integration and service accounts.

## Start with the hardware already there

The retirement of SP001's original app and services is a problem other owners have encountered. Community efforts such as [Second Life Toys](https://github.com/second-life-toys/second-life-toys) document it and share recovery work.

This project explores Chinese AI interaction through the original board, microphone, speaker, eyes, and enclosure. The microphone captures a question; speech recognition turns it into text; a model answers or requests a tool; speech synthesis brings the response back through the original speaker. Eye expressions and limited device controls provide another way to respond.

```text
Original microphone → device client → Wi-Fi → ASR and cloud dialogue
                                                   ↓
Original speaker ← streaming playback ← speech synthesis and reply
```

The configured prototype can have a conversation over Wi-Fi without a computer or phone remaining connected. The optional parent console runs separately. Cloud recognition and answer generation still require network access.

Current adapters include Qwen recognition and MiniMax dialogue and speech synthesis. The prototype supports Chinese follow-ups, stories, explanations, and translation. Weather and search tools let the model request information and continue from the returned results; weather queries require an explicit city.

Getting a reply to come out of the speaker was the first milestone.

## Sound is the beginning of a conversation

People pause halfway through a thought, add a detail, correct themselves, or ask another question before an answer finishes. Meanwhile, the toy's speaker feeds sound back toward its own microphone.

The difficult part is deciding when to listen, when to speak, and when to stop. If a new question arrives during a story, the client needs to confirm the interruption, stop the old playback, and capture the new request. Late results from the cancelled turn also need to respect that cancellation.

The project includes mechanisms for those transitions. The model interprets the full request and selects tools; the device performs bounded actions and returns execution receipts. Tools cover volume queries and changes, eye expression, capture settings, and initiative controls.

A request to lower the volume needs a result from the device before the assistant can report success. Ending a conversation needs to end the listening session. A farewell spoken by a story character, or a word being translated, must preserve the meaning of the user's request.

Feedback from prototype use includes improvements in interruption handling. Reliability across rooms, distances, and voices still needs further testing.

## Following the missing part of an answer

One investigation began with shortened spoken answers. Following the text through the system revealed two reproducible client defects:

1. The parser accepted a valid reply envelope, then discarded ordinary reply text that followed it.
2. After a tool round trip, the dialogue engine returned at the first completed assistant message, leaving later assistant messages out of the reply.

Both defects could make a complete response sound unfinished. Finding them required comparing three stages: the provider's output, the text retained by the client, and the text handed to playback.

The public regression uses a constructed aquarium introduction: an opening about two areas, followed by separate pools, a visitor path, and an entrance sequence. The old completion logic could deliver the opening and lose the later explanation. This is a test fixture, not a replay of a family conversation.

We fixed the premature completion paths and preserved the failure shapes in [NativeReplyCompletenessTest](core-patch/test/NativeReplyCompletenessTest.java). Two integration cases using a real model provider also checked complete text delivery. Their tool and playback ports were simulated, so they establish delivery of the text, with physical speaker acceptance still separate.

That tracing method is useful beyond this toy: locate the first stage where content disappears. Cancellation, network interruptions, and playback errors each need their own evidence when an answer stops early.

## Remember what was actually saved

Recent conversational context and persistent preferences have different jobs. Keeping up with the previous question does not establish that a preference will survive the next session or a restart.

The memory components store stable preferences in application-private storage, with profile separation, inspection, and deletion. An explicit save request produces a receipt; the assistant needs a successful result before saying the information was saved.

This makes persistence visible and testable. The storage and recall mechanisms are available; reliable long-term recall remains an open validation task.

These newly written requests illustrate possible integration tests; **they are synthetic examples, not family transcripts or recordings of tests**:

- Tell a story set in a lunar greenhouse, then change its direction with a follow-up.
- Lower the volume by one step and report the resulting setting.
- Save a preference for paper folding, then check the save receipt.
- Look up tomorrow's weather for an explicitly named city.
- End the current conversation and stop listening.

## Keep a journal that can catch up

The optional parent console shows sessions, duration, complete or partial replies, tool results, and errors. Transcript retention is off by default and can be enabled explicitly.

The toy can keep pending events while the computer backend is unavailable, then synchronize them when it returns. We tested that path on the prototype: the computer service stopped, the toy continued a cloud conversation over Wi-Fi, and the restored service caught up without duplicate records.

Authenticated retrieval, certificate validation, durable cursors, and deduplication support synchronization. A migration needs to transfer the records and cursor together, with one durable backend owning synchronization for each toy.

This concerns the availability of the parent backend. A toy that loses network access cannot generate new replies through cloud services.

The usage controls include daily time and session limits, quiet hours, pause/resume, and command confirmation state. Software logic and expired-command rejection have been tested; enforcing active rules still needs complete testing on the device.

## What it supports

These are components and capabilities of the configured prototype. Running them on a toy requires your own hardware integration.

| What you want to do | What the project provides |
|---|---|
| Continue a conversation in Chinese | Multi-turn context, follow-up questions, stories, explanations, and translation |
| Speak while a reply arrives | Incremental reception and segmented playback, including continued text and multiple messages after tools |
| Interrupt or end a conversation | Interruption confirmation, playback cancellation, new capture, and session lifecycle management |
| Act on the device | Volume queries and changes; adapters for eye expressions, capture settings, and initiative controls; actual execution results |
| Look up weather or information | Model-selected weather and search tools; weather requires an explicit city |
| Save a preference | Local storage, retrieval, inspection, deletion, profile separation, and save receipts |
| See how the toy was used | A local parent console with sessions, duration, partial replies, tool results, errors, and transcript retention controls |
| Catch up after backend downtime | Authenticated synchronization, durable events and cursors, and deduplication |
| Set usage rules | Time and session limits, quiet hours, pause/resume, and confirmation status; complete device testing of active rules is pending |

## What developers can build on

The source connects speech transport, model tools, device execution, memory, and usage records. Regression cases give later changes concrete failures to check.

| Area | Start here |
|---|---|
| Requests and tools | [NativeDialogueProtocol](core-patch/src/org/sp001/core/NativeDialogueProtocol.java) |
| Complete reply reception | [NativeDialogueEngine](core-patch/src/org/sp001/core/NativeDialogueEngine.java), [ReplyEnvelopeStream](core-patch/src/org/sp001/core/ReplyEnvelopeStream.java) |
| Capture, playback, interruption | [OwnerNativeConversation](core-patch/src/org/sp001/core/OwnerNativeConversation.java), [OwnerDuplexCapture](core-patch/src/org/sp001/core/OwnerDuplexCapture.java) |
| Persistent preferences | [CompanionMemory](core-patch/src/org/sp001/core/CompanionMemory.java), [OwnerCompanionMemory](core-patch/src/org/sp001/core/OwnerCompanionMemory.java) |
| Events and catch-up | [ParentJournalState](core-patch/src/org/sp001/core/ParentJournalState.java), [toy-pull.mjs](parent-console/toy-pull.mjs) |
| Local parent interface | [parent-console/](parent-console/) |

Android components use adapters for the original runtime. Developers working with other hardware can study the individual components and implement their own adapters.

## Get started

Install [Node.js 22 or newer](https://nodejs.org/), then:

```sh
git clone https://github.com/Lucky-Yaoyao523/sp001-ai-companion.git
cd sp001-ai-companion
npm start
```

Open the printed local URL, normally `http://127.0.0.1:8787/`, and enter the fresh access code. The database starts empty; clearly labelled synthetic demo records can be loaded explicitly.

Windows users can run `START-PARENT.cmd`; Mac/Linux users can run `sh START-PARENT.command`. Node.js is installed separately.

```sh
npm run voice:demo
npm test
npm run check:privacy
```

Default entry points use no toy, microphone, or paid model. Read [development](docs/development.md) for Java compilation and [integration](docs/integration.md) for hardware adaptation, configuration, and synchronization ownership.

## Where the project stands

The prototype runs on real hardware, and journal catch-up after parent backend downtime has been tested. The two reply-loss defects have reproducible failures, fixes, regression cases, and real-provider checks of complete text delivery.

The initial source release compiled **95 production Java files** and passed **57 Node checks and nine Java offline regression groups**. These cover compilation and software behavior.

Factual accuracy, name understanding, conversational naturalness, cancellation diagnosis, and long-session behavior need improvement. The prototype's selected successes do not establish reliability across every device or acoustic environment.

Audible completion after the reply-loss fixes, interruption reliability, accurate long-term recall, and enforcement of active parent rules still need their own physical or extended-use testing. Integrating the public source with your device is also a separate task.

A useful contribution can start with a sanitized reproducible failure, a regression case, an adapter, or clearer instructions. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Privacy and distribution

The public copy excludes private identities, conversations, memories, recordings, Wi-Fi details, credentials, and private certificates. Configuration examples are disabled with empty credentials, and there is no household weather default. BLE remote provisioning and capture-start entry points are disabled. Private prototype data and original project history were not exported.

Enabled cloud speech sends audio and text to the providers you select; `store:false` does not guarantee provider non-retention. Parent transcripts are off by default, and no automatic external summary service is wired in. See [privacy](PRIVACY.md) and [security](SECURITY.md).

There is no bundled APK, firmware, vendor asset, character audio, signing private key, recovery tool, or debug-access guide. Use your own lawful device development environment.

## Community and license

Community references include [Second Life Toys](https://github.com/second-life-toys/second-life-toys), [SpheroRevived](https://github.com/Ric-614/SpheroRevived), and [Sphero-Spiderman](https://github.com/helenclarko/Sphero-Spiderman); their proprietary packages and assets are not included. Authored code and documentation use the [MIT License](LICENSE), with dependencies installed separately under [their licenses](THIRD_PARTY_NOTICES.md). This independent project has no affiliation with or endorsement from Sphero, Marvel, or Disney. Trademarks identify compatible hardware.

The aim is to keep useful hardware in use, and share the engineering work that lets its next conversation happen.
