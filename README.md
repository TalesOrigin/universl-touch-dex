# universl-touch-dex

Standalone Java JNI bridge compiled directly to **DEX only**. No APK, manifest,
resources, Android application plugin, or packaging step. Java sources have no
Android framework dependency. Minimum runtime: Android API 21.

## Android Java / Gradle project

Open this repository as a Gradle project in Android Studio or IntelliJ, using JDK 17.
The checked-in Gradle 8.10.2 wrapper provides Linux/macOS and Windows launchers.
This is an Android-targeted **DEX module**, not an installable Android application;
it deliberately uses the Java plugin plus D8 instead of an APK/AAR-producing Android
plugin. There is no manifest, Activity, resource packaging, or install task.

```text
build.gradle                 Java, JNI header, test and DEX tasks
settings.gradle              Project settings
gradle.properties           Android build-tools version and minimum API
gradle/wrapper/              Pinned Gradle wrapper
src/main/java/               Java/JNI bridge
src/test/java/               JVM contract tests
local.properties.example     Local SDK configuration template
```

## Build

Install JDK 17 and Android SDK build-tools 35.0.0. Configure the SDK with
`ANDROID_SDK_ROOT`, `ANDROID_HOME`, `sdk.dir` in an untracked `local.properties`,
or `-PandroidSdkDir=/path/to/sdk` (in that precedence: Gradle property, local file,
SDK_ROOT, HOME). No Android SDK platform package is required because the bridge
uses only Java APIs, not Android framework classes.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./gradlew clean build     # compile, test, generate JNI headers and DEX
./gradlew dex             # DEX only, with compilation as needed
./gradlew check           # JVM tests, no SDK needed
```

On Windows use `gradlew.bat` instead of `./gradlew`. First use downloads Gradle
from its distribution service. Override Android settings with
`-PandroidBuildToolsVersion=35.0.0 -PandroidMinSdk=21`.

Outputs:
- `build/dex/classes.dex`: loadable DEX, not an APK or DEX-in-JAR.
- `build/jni/io_universl_touch_TouchDex.h`: generated JNI declarations for the backend.

Both `assemble` and `build` produce the DEX; the JAR task is disabled. CI runs
`./gradlew clean build` and uploads only the DEX and JNI header.

### Optional Python build entry point

The original SDK/JDK-only build script remains available if Gradle is not desired
(Python 3 required). Do not run both build systems concurrently in the same checkout:

```sh
python3 tools/build.py --test
python3 tools/build.py --build-tools 35.0.0
```

`--sdk /path/to/sdk` overrides the environment; omitted build-tools version selects
the newest installed version. Its tests need no SDK. Both build paths target Java 8
bytecode and minimum API 21 by default.

## Usage from a host that loads this DEX

```java
import io.universl.touch.TouchDex;

TouchDex dex = new TouchDex("universl_touch");
dex.init("{}", ready -> {
    if (!ready.isSuccess()) return;
    dex.touch(TouchDex.DOWN, 0, 200, 400, down -> {
        if (down.isSuccess()) {
            dex.touch(TouchDex.UP, 0, 200, 400, up -> {
                // Inspect up.status and up.payload.
            });
        }
    });
});
// Other operations (submit after successful initialization):
// dex.input("Hello", callback);
// dex.key(TouchDex.DOWN, 66, 0, callback);
// dex.key(TouchDex.UP, 66, 0, callback);
// dex.swipe(100, 500, 100, 100, 300, callback);
// dex.scroll(200, 400, 0, -1, callback);
// dex.swap(0, 1, callback);
// dex.release(callback); // release all held inputs
// dex.cancel(requestId); // cancel delivery, not native execution
// dex.close();           // stop backend and complete outstanding callbacks
```

`swap` exchanges backend-defined slots; `swipe` is a timed gesture. The two are
intentionally separate. Touch supports multiple concurrent pointer IDs. Coordinates
are pixels in the backend's configured display space; keys use Android key codes.
Text is passed as a Java string without shell interpolation. Configuration and
response payload formats are owned by the native implementation.

## Native integration contract

**This project supplies the Java/JNI API, not an input-injection implementation.**
Provide `libuniversl_touch.so` for the device ABI through the host's native-library
search path (or use another library name in the constructor). Implement the generated
JNI header's `nativeInit`, `nativeTouch`, `nativeInput`, `nativeKey`, `nativeSwipe`,
`nativeSwap`, `nativeScroll`, `nativeRelease`, and `nativeClose` symbols, or bind the
same methods with `RegisterNatives`.

Every operation except close receives a positive request ID. Complete it through
this instance method, whose JNI signature is `(JILjava/lang/String;)V`:

```java
void onNativeResponse(long requestId, int status, String payload)
```

Use `0` for success and `1` for backend errors. Java reserves `2` for closed and
`3` for cancelled. Responses may arrive synchronously or asynchronously and out of
order. Each registered callback is delivered at most once; duplicates/late replies
are ignored. A backend that never responds leaves a request pending until cancel or
close; callers should schedule their own timeout/cancellation if needed. Submission
failures from JNI linkage/runtime exceptions are reported as error responses.
Callbacks run on the responding thread, so they must be short and nonblocking;
callback RuntimeExceptions are isolated. Marshal to an executor for application work.

Native submission methods must return promptly and must not wait for worker callbacks
(Java serializes submissions against closing). Store a JNI global reference for
asynchronous callbacks, attach native threads to the VM, and delete local/global
references appropriately. `nativeClose` must stop input, release held pointers,
drain workers, and dispose per-instance state/global references; it receives no
request ID. Close is idempotent on the Java side. Do not share mutable native state
between bridge instances without synchronization.

A DEX grants **no additional input privileges**. The host/backend must have the
necessary authorized Android permissions or execution context; ordinary applications
cannot inject arbitrary system-wide events simply by loading this library. Native
code is responsible for device capabilities, Unicode text handling, gesture timing,
input ordering, display bounds, and reporting unsupported operations as errors.
