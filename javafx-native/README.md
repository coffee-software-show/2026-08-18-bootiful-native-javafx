# javafx-native

A small JavaFX desktop app wired up with **Spring Boot** (dependency injection, configuration
properties, AOT) and compiled to a **GraalVM native image**.

The window is built by an ordinary `@Component`; JavaFX hands the primary `Stage` to Spring as an
application event and Spring beans take it from there.

```
                     Spring Boot AOT              GraalVM native-image
  main() ─ boots ──▶  ApplicationContext  ──▶  javafx-native (single binary)
        └ Platform.startup() ─ publishes StageReadyEvent ─▶ StageInitializer builds the Scene
```

## Requirements

- GraalVM for JDK 25 (`sdk install java 25.2.4-graalce`) — `native-image` must be on the `PATH`
- Maven 3.9+
- Xcode command line tools (the native linker)

Built and verified on macOS / aarch64. See [Other platforms](#other-platforms).

## Running it

```bash
# on the JVM
mvn spring-boot:run

# tests (Spring context only, no JavaFX toolkit, so this is headless-safe)
mvn test

# native image  ->  ./target/javafx-native
mvn -Pnative -DskipTests native:compile
./target/javafx-native
```

Startup, JVM vs. native image, same machine (M-series Mac, GraalVM CE 25):

| | Spring context ready | window on screen |
|---|---|---|
| JVM | ~220 ms | ~570 ms |
| native image | ~10 ms | ~160 ms |

The binary is ~56 MB and self-contained — JavaFX's native libraries are embedded in it.

Add `-Dsmoke.test=true` to either one to have the app close itself after three seconds — that is how
the binary gets verified without a human clicking anything.

## Layout

| File | Role |
|---|---|
| `JavaFxNativeApplication` | `@SpringBootApplication`; `main()` boots Spring, then starts the JavaFX toolkit |
| `StageReadyEvent` | carries the primary `Stage` into the Spring event system |
| `StageInitializer` | `@Component` that builds the `Scene` when the stage shows up |
| `GreetingService` / `GreetingProperties` | an injected service bound to `greeting.*` in `application.properties` |
| `META-INF/native-image/org.openjfx/javafx/reachability-metadata.json` | the JavaFX half of the native-image configuration |

## How the native build works

Three separate things have to line up. Two are handled by plugins, one is hand-maintained.

**1. Spring Boot AOT.** `spring-boot-maven-plugin`'s `process-aot` goal (contributed by the
`native` profile in the Spring Boot parent) runs the application at *build* time, then emits bean
definitions, an `ApplicationContextInitializer` and reflection metadata as generated source. At
runtime the native image uses the generated context instead of scanning and reflecting — that is
where the 14 ms startup comes from. `Started AOT-processed JavaFxNativeApplication` in the log
confirms the generated context is the one being used.

**2. JavaFX reachability metadata.** OpenJFX ships no native-image metadata of its own, so this
project keeps its own, recorded with the tracing agent and then widened by hand (see below).

**3. `native-maven-plugin`** builds the binary. JavaFX's platform jar carries `libglass.dylib`,
`libprism_*.dylib` and friends *inside* the jar, and `NativeLibLoader` extracts them at runtime.
They are registered as resources in the metadata file, so they get embedded in the image and the
binary needs nothing beside it.

### Four things that will bite you

These are all load-bearing; they cost a while to find.

**`Platform.startup()`, not `Application.launch()`.** On macOS the AppKit event loop has to run on
the process' first thread. The `java` launcher arranges that by moving `main()` onto a secondary
thread and parking thread 0 in a CoreFoundation run loop. A native image has no such launcher, so
`main()` *is* thread 0. `Application.launch()` hands toolkit startup to a `JavaFX-Launcher` thread,
which then waits forever for a main thread that is itself blocked inside `launch()`. The result is
a silent deadlock: process alive, no window, no stack trace, exit code 0. Starting the toolkit with
`Platform.startup()` directly from `main()` lets Glass see it is already on the main thread and run
the event loop in place. This is also correct on the JVM.

**Boot the Spring context from `main()`, on the main thread.** `SpringApplication` deduces the
"main application class" by walking the stack for a `main()` method, and the AOT-generated
initializer is looked up by that class name. A JavaFX launcher/FX thread has no `main()` on its
stack, so booting the context from `Application#init()` silently falls back to the *non*-AOT path —
it still works, just slowly and with metadata that no longer matches. Booting in `main()` also
means Spring Boot's AOT processor, which invokes `main()` at build time and abandons the run once
the context is prepared, never reaches the UI code — no window pops up during the build.

**Do not put your metadata in `META-INF/native-image/<your groupId>/<your artifactId>/`.** Spring
Boot's AOT engine generates its own `reachability-metadata.json` at exactly that path, and one file
silently wins over the other. The JavaFX metadata lives under `org.openjfx/javafx/` instead, which
is also where it belongs — it describes OpenJFX, not this app.

**The tracing agent alone is not enough for Glass.** Glass' native code resolves Java callbacks by
name at points the agent may never reach — window activation, menus, drag & drop, quit. A method ID
it fails to resolve is cached as `NULL` and called later, which is a segfault, not an exception. So
`com.sun.glass.ui`, `com.sun.glass.events` and `com.sun.glass.utils` are registered wholesale
(`allDeclaredMethods`/`Fields`/`Constructors`, `jniAccessible`) rather than method by method. Note
that `allDeclaredMethods` does *not* cover inherited methods, and JNI looks callbacks up on the
subclass — so the agent-recorded entries (e.g. `notifyApplicationDidTerminate` on `MacApplication`,
declared on `Application`) are kept alongside the wholesale ones.

### Re-recording the metadata

If you add UI that touches new parts of JavaFX:

```bash
mvn -Pagent spring-boot:run        # writes target/native/agent-output/reachability-metadata.json
```

Exercise the new UI before it closes, then merge the JavaFX-related entries (anything under
`com.sun.*` / `javafx.*`, plus every entry marked `jniAccessible`) into
`src/main/resources/META-INF/native-image/org.openjfx/javafx/reachability-metadata.json`. Leave the
Spring entries out — Spring Boot's AOT engine regenerates those on every build.

## Other platforms

The committed metadata was recorded on macOS/aarch64 and names macOS-only classes
(`com.sun.glass.ui.mac.*`) and libraries (`*.dylib`). On Linux or Windows, re-run the agent there
and merge the result. Everything else — the `Platform.startup()` bootstrap, the AOT wiring, the
metadata layout — is platform independent.

`Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` at startup is
expected: JavaFX is on the class path rather than the module path, which is what native-image
wants. It is a warning, not an error.
