# SP001 AI Companion

Open-source components for a Chinese-speaking AI companion on the original Sphero Spider-Man SP001 hardware. The project preserves the original board, microphone, speaker, eyes and enclosure, using cloud models over Wi-Fi rather than an on-device LLM.

See the [Chinese README](README.md) for the full feature matrix and instructions. This is a developer **source release**, not a ready-to-flash toy firmware. The Node.js parent console runs locally; Android integration requires a lawful device development environment and hardware adaptation.

```sh
git clone https://github.com/Lucky-Yaoyao523/sp001-ai-companion.git
cd sp001-ai-companion
npm start
npm test
npm run check:privacy
```

Node.js 22+ is required. Open the local URL printed by the console and enter its freshly generated access code. It starts with an empty database, transcript retention disabled, no toy connection and no automatic cloud summary service. Clearly labelled synthetic demo records can be loaded explicitly.

The source includes streaming dialogue, bounded audio/ASR/TTS, cancellation, interruption handling, local preference memory, authenticated journal synchronization, and regression tests for silent reply loss. Android sources compile for API 22 / Java 8; see [development](docs/development.md) and [integration](docs/integration.md).

Private profiles, family conversations, original recordings, home addresses, credentials, device identities and signing materials were excluded. BLE remote provisioning and capture-start functionality is disabled in this public edition. No vendor APK, extracted assets, firmware recovery tools or debug-access instructions are distributed.

This public export differs from the private hardware prototype. Offline checks do not establish physical voice quality on arbitrary toys. Remaining work includes model factual accuracy, natural conversation, name understanding, cancellation diagnosis, long-session behavior and wider hardware/network coverage.

This independent community project is not affiliated with Sphero, Marvel or Disney. It makes no global-first or world-leading claim. MIT applies to our authored code and docs; see [third-party notices](THIRD_PARTY_NOTICES.md), [privacy](PRIVACY.md) and [security](SECURITY.md).
