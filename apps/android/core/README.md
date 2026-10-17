# Android core library

`:core` packages generated Kotlin bindings, the Rust native library for
`arm64-v8a` and `x86_64`, and the JNA dependency. It has no application activity,
provider login, storage, UI or package identity requirement. The primary Android
application consumes it as a normal Gradle library dependency.

Run the documented Android build environment, including pinned Rust and the NDK,
then use `./gradlew :core:assembleRelease`. The `prepareCore` task invokes
`scripts/build-android-core.sh`; bindings and binaries are generated from the
same locked Cargo workspace and are not checked in. This task deliberately
invokes Cargo on every build so missing or changed inputs are checked.

For another Gradle application, include this library project and depend on it,
or use `:core:publishReleasePublicationToMavenLocal` to publish the AAR together
with its dependency metadata and source archive. A copied AAR alone does not
carry transitive dependencies; consumers must include the matching JNA AAR.

The public Kotlin API is in `net.fstab.tachiai.core`. Player adapters supply a
typed `SeekContext` snapshot and execute the returned `SeekPlan`; the library
does not access a player or perform a seek itself. Times are milliseconds and
unknown values are nullable. API and native bindings are an evolving `0.1.0`
unit; generated sources and native libraries must come from the same build.

Host JVM tests need the JNA JAR and
`jna.library.path=<repository>/build/core/native/host`. Android uses the JNA AAR;
the packaged library is loaded from the application's native library directory.
