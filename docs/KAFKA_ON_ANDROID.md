# Kafka on Android

My initial googling around this idea did not bode well for running Kafka on a mobile device.
The consensus online seems to be that it is not meant for running on mobile and one should leverage mqtt, webrtc, websockets or any number of other proxies.

For this project, simplicity was a core goal. Since this is also a toy project, I decided to ignore that advice and see how far a direct Kafka connection could be pushed (possibly driven by a bit of contrarianism..).

### using the java kafka-client library
My first attempt was to use the plain kafka-client java dependency, that approach failed quickly. As documented [here](https://issues.apache.org/jira/browse/KAFKA-7025), the Java client is not Android-compatible due to its reliance on APIs and behaviors that are not available on the Android runtime.
Another option would be to implement my own client, but that would be a massive effort, way beyond the scope of this project.

### librdkafka via JNI
Then I looked at wrapping the c implementation (librdkafka) using JNI. golang and python do something similar for their kafka client so it's not too far off the beaten path.

This ended up being a viable approach, but it came with some work:
- librdkafka itself needed to be cross-compiled for Android
- OpenSSL was also required and had to be cross-compiled
- JNI bindings had to be written for the small subset of APIs the app actually uses
  There are no readily available prebuilt binaries that fit this setup, so the native toolchain work was unavoidable.

While the initial setup was more involved than expected, the result has been surprisingly stable.

Once the JNI layer was in place, it required very little ongoing maintenance. The app interacts with Kafka through a small, well-defined native surface, and most of the application logic lives entirely on the Kotlin side.
