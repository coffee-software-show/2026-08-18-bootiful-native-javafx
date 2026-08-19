package com.example.bootiful_javafx;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

///
/// Finding the classes in a package, rather than listing them by hand.
///
/// `ClassPathScanningCandidateComponentProvider` is the obvious tool here and the wrong one: it
/// hunts for candidate *beans*, so before a filter ever sees a class it has already dropped
/// interfaces, abstract classes and non-static inner classes - which are precisely the things
/// JavaFX reaches for by name. This walks the class files themselves and reads each one's name
/// out of the bytecode instead, so nothing is filtered out and, just as importantly, nothing is
/// loaded: a class file is enough, and a type whose static initializer wants a windowing system
/// is never asked to run one.
///
abstract class HintsUtils {

    private static final Log log = LogFactory.getLog(HintsUtils.class);

    /// Every type whose class file lives directly in `packageName`: its classes, interfaces, enums
    /// and records, and - because `Outer$Inner.class` sits in the same directory as `Outer.class` -
    /// every nested, anonymous and synthetic class compiled alongside them. Subpackages are not
    /// included; name the ones you want.
    static Set<TypeReference> findClassesInPackage(String packageName) {
        return findClassesInPackages(HintsUtils.class.getClassLoader(), List.of(packageName));
    }

    /// [#findClassesInPackage(String)] over several packages at once, sharing one resolver and one
    /// metadata cache across the lot. A package that nothing on the classpath contributes to - a
    /// platform-specific one such as `com.sun.glass.ui.mac` on Linux, say - contributes nothing
    /// rather than failing.
    static Set<TypeReference> findClassesInPackages(ClassLoader classLoader, Collection<String> packageNames) {
        var resolver = new PathMatchingResourcePatternResolver(classLoader);
        var metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        var classNames = new TreeSet<String>();
        for (var packageName : packageNames) {
            var pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                    + ClassUtils.convertClassNameToResourcePath(packageName) + "/*.class";
            var before = classNames.size();
            try {
                for (var resource : resolver.getResources(pattern)) {
                    if (!resource.isReadable() || isSynthetic(resource.getFilename())) {
                        continue;
                    }
                    var metadata = metadataReaderFactory.getMetadataReader(resource).getClassMetadata();
                    classNames.add(metadata.getClassName());
                }
            } catch (IOException ioException) {
                throw new UncheckedIOException("could not scan [" + packageName + "]", ioException);
            }
            if (log.isDebugEnabled()) {
                log.debug("found [" + (classNames.size() - before) + "] classes in [" + packageName + "]");
            }
        }
        return classNames.stream().map(TypeReference::of).collect(Collectors.toUnmodifiableSet());
    }

    /// `package-info` and `module-info` are class files that do not describe a class, and their
    /// hyphenated names are not valid Java identifiers - [TypeReference#of(String)] rejects them.
    private static boolean isSynthetic(String filename) {
        return filename == null || filename.startsWith("package-info") || filename.startsWith("module-info");
    }

}
