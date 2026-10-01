# Third-party notices

The MIT license in this repository covers our authored source and documentation. It does not grant rights to Sphero/Marvel/Disney software, firmware, audio, artwork, trademarks, or any third-party application.

No vendor APK, extracted firmware/resource bundle, signing key, or third-party JAR/runtime is distributed here.

Dependencies obtained separately:

- Android SDK / `android.jar`: provided by Google under its own SDK terms.
- OkHttp 3.12.13 and Okio 1.15.0: Square, Apache License 2.0. See [OkHttp](https://github.com/square/okhttp) and [Okio](https://github.com/square/okio).
- JSON-java 20240303: [JSON-java](https://github.com/stleary/JSON-java), upstream license applies; used only by host tests.
- Node.js and a Java JDK: installed separately, under their respective licenses.
- The embedded ISRG Root X1 **public trust certificate** in `WeatherTls` is from [Let's Encrypt](https://letsencrypt.org/certificates/). It is not a family certificate or private key.

The code may call MiniMax, Alibaba Cloud Model Studio, and Open-Meteo using the user's own configuration. Their service terms, pricing, attribution requirements, and data handling apply independently. Their SDKs are not bundled.

Public preservation projects are credited as references in the README; no application or code from those repositories is redistributed in this source export.
