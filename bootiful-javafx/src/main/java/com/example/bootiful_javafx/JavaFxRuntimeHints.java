package com.example.bootiful_javafx;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class JavaFxRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		var reflective = types(this.nativeCallbacks, this.prismShaders, this.effectPeers, this.publicApi, this.toolkit);
		this.findClassesInPackages(classLoader, reflective)
			.forEach(type -> hints.reflection().registerType(type, this.everything));
		this.findClassesInPackages(classLoader, this.nativeCallbacks)
			.forEach(type -> hints.jni().registerType(type, this.everything));
		this.nativeCallbackTypes.forEach(type -> {
			hints.reflection().registerTypeIfPresent(classLoader, type, this.everything);
			hints.jni().registerTypeIfPresent(classLoader, type, this.everything);
		});
		this.arrays.forEach(type -> hints.reflection().registerTypeIfPresent(classLoader, type, this.everything));
		this.resources.forEach(hints.resources()::registerPattern);
	}

	private final MemberCategory[] everything = Stream.of(MemberCategory.values()) //
		.filter(category -> {//
			try {
				return !MemberCategory.class.getField(category.name()) //
					.isAnnotationPresent(Deprecated.class);
			} //
			catch (NoSuchFieldException noSuchField) {
				throw new IllegalStateException(noSuchField);
			}
		}) //
		.toArray(MemberCategory[]::new);

	/*
	 * Glass is the sliver of JavaFX that sits on the platform's own windowing toolkit,
	 * and the traffic runs both ways. Coocoa delivers an event, and the native side
	 * reaches back into Java through JNI, looking up the class, the field or the method
	 * by name.
	 */
	private final List<String> nativeCallbacks = List.of("com.sun.glass.events", "com.sun.glass.ui",
			"com.sun.glass.ui.delegate", "com.sun.glass.ui.headless", "com.sun.glass.ui.mac", "com.sun.glass.utils",
			"com.sun.javafx.font.coretext");

	private final List<String> prismShaders = List.of("com.sun.prism.shader");

	private final List<String> effectPeers = List.of("com.sun.scenario.effect.impl.es2",
			"com.sun.scenario.effect.impl.hw.mtl", "com.sun.scenario.effect.impl.prism",
			"com.sun.scenario.effect.impl.prism.ps", "com.sun.scenario.effect.impl.prism.sw",
			"com.sun.scenario.effect.impl.sw.java", "com.sun.scenario.effect.impl.sw.sse");

	private final List<String> publicApi = List.of("javafx.animation", "javafx.application", "javafx.collections",
			"javafx.css", "javafx.event", "javafx.geometry", "javafx.scene", "javafx.scene.control",
			"javafx.scene.effect", "javafx.scene.image", "javafx.scene.layout", "javafx.scene.paint",
			"javafx.scene.shape", "javafx.scene.text", "javafx.scene.transform", "javafx.stage");

	/*
	 * The rest of the toolkit's own by-name plumbing: the pipeline and font factory it
	 * selects from a system property, the logger it picks depending on whether JFR is
	 * around.
	 */
	private final List<String> toolkit = List.of("com.sun.javafx", "com.sun.javafx.logging",
			"com.sun.javafx.logging.jfr", "com.sun.javafx.scene.control.skin", "com.sun.javafx.tk.quantum",
			"com.sun.prism", "com.sun.prism.es2");

	/*
	 * these are types used by JNI. Some of them are the same as in the reflection hints.
	 */
	private final List<String> nativeCallbackTypes = types(
			classSet(Runnable.class, Boolean.class, Class.class, Integer.class, Double.class, Float.class, Byte.class,
					Character.class, Long.class, Object.class, String.class),
			classSet(Collections.class, HashMap.class, List.class, Map.class), classSet(javafx.scene.paint.Color.class),
			classSet(javafx.scene.shape.LineTo.class, javafx.scene.shape.MoveTo.class),
			List.of("sun.management.VMManagementImpl"));

	/* `getCanonicalName`, not `getName`: for an array */
	private final List<String> arrays = List.of(com.sun.glass.ui.Screen[].class.getCanonicalName(),
			javafx.scene.paint.Color[].class.getCanonicalName());

	private final List<String> resources = List.of("*.dylib", "com/sun/glass/utils/NativeLibLoader.class",
			"com/sun/javafx/scene/control/skin/modena/**", "com/sun/javafx/scene/control/skin/caspian/**",
			"com/sun/javafx/scene/control/skin/resources/*.properties", "com/sun/javafx/tk/quantum/*.properties",
			"com/sun/prism/es2/glsl/**", "com/sun/prism/mtl/msl/**", "com/sun/scenario/effect/impl/es2/glsl/**",
			// the app's own two: the stylesheet the scene loads, and the Mustache
			// template the sign-in page is rendered from. Neither is a class, so
			// nothing in the AOT pass would otherwise notice them.
			"styles.css", "templates/*");

	private Set<String> classSet(Class<?>... classes) {
		return Stream.of(classes).map(Class::getName).collect(Collectors.toUnmodifiableSet());
	}

	private Set<TypeReference> findClassesInPackages(ClassLoader classLoader, Collection<String> packageNames) {
		var resolver = new PathMatchingResourcePatternResolver(classLoader);
		var metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
		var classNames = new TreeSet<String>();
		for (var packageName : packageNames) {
			var pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
					+ ClassUtils.convertClassNameToResourcePath(packageName) + "/*.class";
			try {
				for (var resource : resolver.getResources(pattern)) {
					if (!resource.isReadable() || isSynthetic(resource.getFilename())) {
						continue;
					}
					var metadata = metadataReaderFactory.getMetadataReader(resource).getClassMetadata();
					classNames.add(metadata.getClassName());
				}
			} //
			catch (IOException ioException) {
				throw new UncheckedIOException("could not scan [" + packageName + "]", ioException);
			}
		}
		return classNames//
			.stream() //
			.map(TypeReference::of) //
			.collect(Collectors.toUnmodifiableSet());
	}

	@SafeVarargs
	private List<String> types(Collection<String>... groups) {
		return Stream.of(groups).flatMap(Collection::stream).toList();
	}

	private boolean isSynthetic(String filename) {
		return filename == null || filename.startsWith("package-info") || filename.startsWith("module-info");
	}

}
