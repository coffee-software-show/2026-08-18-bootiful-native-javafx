package com.example.bootiful_javafx;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.List;
import java.util.stream.Stream;

///
/// This began as the `META-INF/native-image/org.openjfx/javafx/reachability-metadata.json` the
/// native-image agent recorded (`mvn -Pagent spring-boot:run`), rewritten as a
/// [RuntimeHintsRegistrar] so it compiles, so it can carry an explanation of *why* each family is
/// in the list, and so it arrives by the same route as every other hint Spring's AOT processing
/// contributes.
///
/// What it lists now is packages rather than classes. The agent records reflection down to the
/// individual method, and one recording's worth of methods on one recording's worth of classes is
/// exactly the thing that goes stale: a class JavaFX only ever finds by name is a class whose
/// members we cannot predict, and a *package* JavaFX finds classes by name in is a package whose
/// classes we cannot predict either. So the packages are named, [HintsUtils] finds what is in them
/// - nested, anonymous and synthetic classes included, since those are class files sitting in the
/// same directory - and every type it finds is registered for every [MemberCategory]. Asking for
/// `values()` rather than naming the categories keeps that true the day Spring adds one; scanning
/// rather than enumerating keeps it true the day OpenJFX adds a shader.
///
/// The cost is honest: this registers on the order of a couple of thousand types where the
/// recording named five hundred, which the image pays for in size. The benefit is that a JavaFX
/// upgrade cannot silently take a class out from under it.
///
class JavaFxRuntimeHints implements RuntimeHintsRegistrar {

    /// Everything, asked for as `values()` rather than spelled out, so it stays everything the day
    /// Spring adds a category. Minus the deprecated ones: each is either a synonym for a surviving
    /// category, or - `PUBLIC_CLASSES`, `DECLARED_CLASSES` - an attribute GraalVM's metadata schema
    /// has since dropped and now greets with a build warning.
    private static final MemberCategory[] EVERYTHING = Stream.of(MemberCategory.values())
            .filter(category -> !isDeprecated(category))
            .toArray(MemberCategory[]::new);

    /// Glass is the sliver of JavaFX that sits on the platform's own windowing toolkit, and the
    /// traffic runs both ways: Cocoa delivers an event, and the native side reaches back into
    /// Java through JNI, looking up the class, the field or the method by name. Those lookups are
    /// invisible to native-image's static analysis, so every type in these packages is registered
    /// for JNI as well as for reflection.
    ///
    /// Much of this is platform-specific - `com.sun.glass.ui.mac` only exists in the macOS
    /// classifier of javafx-graphics - and on another platform the scan simply comes back empty.
    private static final List<String> NATIVE_CALLBACKS = List.of(
            "com.sun.glass.events",
            "com.sun.glass.ui",
            "com.sun.glass.ui.delegate",
            "com.sun.glass.ui.headless",
            "com.sun.glass.ui.mac",
            "com.sun.glass.utils",
            "com.sun.javafx.font.coretext");

    /// Prism builds a shader's class name out of the paint, the blend mode and the pipeline, then
    /// asks for it by that name. No amount of static analysis follows a string concatenation into
    /// a class, and no single run of the app touches more than a few, so the package goes in
    /// wholesale rather than one recording's worth of it.
    private static final List<String> PRISM_SHADERS = List.of(
            "com.sun.prism.shader");

    /// The effects pipeline resolves a peer per renderer - hand-written Java, SSE intrinsics,
    /// Prism shaders, Metal - by name, for the same reason and with the same blind spot. Reduced
    /// opacity on a disabled control is enough to pull one of these in, which is what the
    /// `-Dsmoke.test` toggling in [StageInitializer] is there to exercise.
    private static final List<String> EFFECT_PEERS = List.of(
            "com.sun.scenario.effect.impl.es2",
            "com.sun.scenario.effect.impl.hw.mtl",
            "com.sun.scenario.effect.impl.prism",
            "com.sun.scenario.effect.impl.prism.ps",
            "com.sun.scenario.effect.impl.prism.sw",
            "com.sun.scenario.effect.impl.sw.java",
            "com.sun.scenario.effect.impl.sw.sse");

    /// The CSS engine turns a selector into a class: `styles.css` naming `.greeting` sends it
    /// looking for the Java type behind the styleable, and property lookups on the way to a
    /// computed value go through reflection too. Which types a stylesheet will name is a question
    /// about the stylesheet, not about the app, so the public API packages go in whole.
    private static final List<String> PUBLIC_API = List.of(
            "javafx.animation",
            "javafx.application",
            "javafx.collections",
            "javafx.css",
            "javafx.event",
            "javafx.geometry",
            "javafx.scene",
            "javafx.scene.control",
            "javafx.scene.effect",
            "javafx.scene.image",
            "javafx.scene.layout",
            "javafx.scene.paint",
            "javafx.scene.shape",
            "javafx.scene.text",
            "javafx.scene.transform",
            "javafx.stage");

    /// The rest of the toolkit's own by-name plumbing: the pipeline and font factory it selects
    /// from a system property, the logger it picks depending on whether JFR is around.
    private static final List<String> TOOLKIT = List.of(
            "com.sun.javafx",
            "com.sun.javafx.logging",
            "com.sun.javafx.logging.jfr",
            "com.sun.javafx.scene.control.skin",
            "com.sun.javafx.tk.quantum",
            "com.sun.prism",
            "com.sun.prism.es2");

    /// The last names spelled out one by one, for the two reasons a package will not do.
    ///
    /// The JDK's own types - the `java.lang.Boolean` Glass boxes a result into on its way back up
    /// through JNI, the `sun.management.VMManagementImpl` the toolkit asks the VM about - live in
    /// the runtime image rather than on the classpath, so there is no class file for a scan to
    /// find. And `Color`, `LineTo` and `MoveTo` are the only members of packages already scanned
    /// for reflection that the native side also constructs, so naming them here keeps the JNI
    /// surface to what actually crosses that boundary rather than two more whole packages.
    /// Registration is conditional: none of these is guaranteed to be present.
    private static final List<String> NATIVE_CALLBACK_TYPES = types(
            in("java.lang", Boolean.class.getName(), Class.class.getName(), Integer.class.getName(),
                    Long.class.getName(), Object.class.getName(), Runnable.class.getName(), String.class.getName()),
            in("java.util", "Collections", "HashMap", "List", "Map"),
            in("javafx.scene.paint", "Color"),
            in("javafx.scene.shape", "LineTo", "MoveTo"),
            in("sun.management", "VMManagementImpl"));

    /// An array type has no class file of its own, so no scan will ever turn one up.
    private static final List<String> ARRAYS = types(
            in("com.sun.glass.ui", "Screen[]"),
            in("javafx.scene.paint", "Color[]"));

    /// Resources JavaFX loads off the classpath: the native libraries it unpacks and dlopen()s,
    /// the Modena and Caspian stylesheets, the shader programs, and the control skins'
    /// localized strings. Plus this application's own `styles.css`.
    private static final List<String> RESOURCES = List.of(
            "*.dylib",
            "com/sun/glass/utils/NativeLibLoader.class",
            "com/sun/javafx/scene/control/skin/modena/**",
            "com/sun/javafx/scene/control/skin/caspian/**",
            "com/sun/javafx/scene/control/skin/resources/*.properties",
            "com/sun/javafx/tk/quantum/*.properties",
            "com/sun/prism/es2/glsl/**",
            "com/sun/prism/mtl/msl/**",
            "com/sun/scenario/effect/impl/es2/glsl/**",
            "styles.css"
    );

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        
        // everything wants reflection, the JNI-reachable packages included - JNI registration is
        // on top of that, not instead of it
        var reflective = types(NATIVE_CALLBACKS, PRISM_SHADERS, EFFECT_PEERS, PUBLIC_API, TOOLKIT);
        HintsUtils.findClassesInPackages(classLoader, reflective)
                .forEach(type -> hints.reflection().registerType(type, EVERYTHING));

        HintsUtils.findClassesInPackages(classLoader, NATIVE_CALLBACKS)
                .forEach(type -> hints.jni().registerType(type, EVERYTHING));

        NATIVE_CALLBACK_TYPES.forEach(type -> {
            hints.reflection().registerTypeIfPresent(classLoader, type, EVERYTHING);
            hints.jni().registerTypeIfPresent(classLoader, type, EVERYTHING);
        });

        ARRAYS.forEach(type -> hints.reflection().registerTypeIfPresent(classLoader, type, EVERYTHING));

        RESOURCES.forEach(hints.resources()::registerPattern);
    }

    private static boolean isDeprecated(MemberCategory category) {
        try {
            return MemberCategory.class.getField(category.name()).isAnnotationPresent(Deprecated.class);
        } catch (NoSuchFieldException noSuchField) {
            throw new IllegalStateException(noSuchField);
        }
    }

    /// The one list left that names types rather than packages is long enough without repeating
    /// the package on every line.
    private static List<String> in(String packageName, String... simpleNames) {
        return Stream.of(simpleNames).map(simpleName -> packageName + "." + simpleName).toList();
    }

    @SafeVarargs
    private static List<String> types(List<String>... groups) {
        return Stream.of(groups).flatMap(List::stream).toList();
    }

}
