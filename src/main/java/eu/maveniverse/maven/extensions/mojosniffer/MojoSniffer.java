/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package eu.maveniverse.maven.extensions.mojosniffer;

import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.maven.classrealm.ClassRealmManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mojo sniffer component. It cannot use ctor injection, as it is instantiated in {@link MojoSnifferModule}
 * and only then is injected.
 */
public class MojoSniffer {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final String NAME = MojoSniffer.class.getSimpleName().toLowerCase(Locale.ENGLISH);
    private static final String DUMPER_ENABLED = NAME + ".enabled";
    private static final String DUMPER_STACKTRACE = NAME + ".stacktrace";
    private static final String DUMPER_OUTPUT_PATH = NAME + ".file";

    // number of lines from the stack trace to discard
    // those come from the interception mechanism
    private static final int LINES_TO_IGNORE = 7;

    private final boolean enabled;

    private final boolean stackTrace;

    private final Path outputPath;

    // class -> method -> stackTraces
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<List<String>>>> invocations =
            new ConcurrentHashMap<>();

    private final AtomicBoolean sessionRuns = new AtomicBoolean(false);

    private RuntimeInformation runtimeInformation;

    private ClassRealmManager classRealmManager;

    public MojoSniffer() {
        this.enabled = Boolean.getBoolean(DUMPER_ENABLED);
        this.stackTrace = Boolean.getBoolean(DUMPER_STACKTRACE);
        this.outputPath = Paths.get(System.getProperty(DUMPER_OUTPUT_PATH, "sniffer.log"));
    }

    @Inject
    public void inject(RuntimeInformation runtimeInformation, ClassRealmManager classRealmManager) {
        this.runtimeInformation = runtimeInformation;
        this.classRealmManager = classRealmManager;
    }

    private void log(MethodInvocation methodInvocation) {
        if (!enabled) {
            return;
        }
        Object enhancedThis = methodInvocation.getThis();
        if (enhancedThis == null) {
            return;
        }
        Class<?> realThis = enhancedThis.getClass().getSuperclass();
        String target = realThis.getName() + " (CL " + realThis.getClassLoader() + ")";
        String method = methodInvocation.getMethod().toGenericString();
        ConcurrentHashMap<String, Set<List<String>>> methods =
                invocations.computeIfAbsent(target, k -> new ConcurrentHashMap<>());
        Set<List<String>> stackTraces = methods.computeIfAbsent(method, k -> ConcurrentHashMap.newKeySet());
        StringWriter sw = new StringWriter();
        new Throwable().printStackTrace(new PrintWriter(sw));
        stackTraces.add(
                Stream.of(sw.toString().split("\n")).skip(LINES_TO_IGNORE).collect(Collectors.toList()));
    }

    /**
     * Just "surface calls", internals (where this class already present) are omitted.
     */
    boolean isInternalCall(List<String> stackTrace) {
        return stackTrace.stream().anyMatch(s -> s.contains(MojoSniffer.class.getName()));
    }

    Object intercept(MethodInvocation methodInvocation) throws Throwable {
        log(methodInvocation);
        return methodInvocation.proceed();
    }

    boolean matchClass(Class<?> aClass) {
        if (isClassOfInterest(aClass)) {
            if (Modifier.isFinal(aClass.getModifiers())) {
                return false;
            }
            if (isDeprecatedClass(aClass)) {
                return true;
            }
            if (!aClass.isInterface() && !aClass.isEnum()) {
                return isMavenCompatClass(aClass);
            }
        }
        return false;
    }

    boolean isClassOfInterest(Class<?> aClass) {
        Package pkg = aClass.getPackage();
        if (pkg == null) {
            return false;
        }
        String pgkName = pkg.getName();
        return pgkName.startsWith("org.apache.maven") || pgkName.startsWith("org.codehaus");
    }

    boolean isDeprecatedClass(Class<?> aClass) {
        if (aClass.getAnnotation(Deprecated.class) != null) {
            return true;
        }
        Class<?>[] ifaces = aClass.getInterfaces();
        for (Class<?> iface : ifaces) {
            if (isDeprecatedClassAncestor(iface)) {
                return true;
            }
        }
        return isDeprecatedClassAncestor(aClass.getSuperclass());
    }

    private boolean isDeprecatedClassAncestor(Class<?> aClass) {
        if (aClass == null) {
            return false;
        }
        if (!isClassOfInterest(aClass)) {
            return false;
        }
        if (aClass.getAnnotation(Deprecated.class) != null) {
            return true;
        }
        Class<?>[] ifaces = aClass.getInterfaces();
        for (Class<?> iface : ifaces) {
            if (isDeprecatedClassAncestor(iface)) {
                return true;
            }
        }
        return isDeprecatedClassAncestor(aClass.getSuperclass());
    }

    boolean isMavenCompatClass(Class<?> aClass) {
        ClassLoader cl = aClass != null ? aClass.getClassLoader() : null;
        URL url = cl != null ? cl.getResource(aClass.getName().replace('.', '/') + ".class") : null;
        return url != null && url.getPath().contains("/maven-compat-");
    }

    boolean matchMethod(Method method) {
        return true;
    }

    public Matcher<Class<?>> getClassMatcher() {
        return new AbstractMatcher<Class<?>>() {
            @Override
            public boolean matches(Class<?> aClass) {
                return matchClass(aClass);
            }
        };
    }

    public Matcher<Method> getMethodMatcher() {
        return new AbstractMatcher<Method>() {
            @Override
            public boolean matches(Method method) {
                return matchMethod(method);
            }
        };
    }

    public MethodInterceptor getMethodInterceptor() {
        return new MethodInterceptor() {
            @Override
            public Object invoke(MethodInvocation methodInvocation) throws Throwable {
                return intercept(methodInvocation);
            }
        };
    }

    public void sessionStarted(MavenSession session) {
        if (sessionRuns.compareAndSet(false, true)) {
            if (enabled) {
                logger.info("Mojo Sniffer enabled");
            }

            return;
        }
        throw new IllegalStateException("Not supporting overlapping session!");
    }

    public void sessionStopped(MavenSession session) {
        if (sessionRuns.compareAndSet(true, false)) {
            if (enabled) {
                logger.info("MojoSniffer dumping into {}", outputPath);
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputPath))) {
                    pw.println("MojoSniffer output");
                    pw.println("Maven " + runtimeInformation.getMavenVersion());
                    pw.println();
                    pw.println();
                    for (Map.Entry<String, ConcurrentHashMap<String, Set<List<String>>>> target :
                            invocations.entrySet()) {
                        pw.println("===> " + target.getKey());
                        for (Map.Entry<String, Set<List<String>>> method :
                                target.getValue().entrySet()) {
                            pw.println();
                            pw.println("     " + method.getKey());
                            boolean first = true; // to have at least one (even internal) stack trace
                            for (List<String> stackTrace : method.getValue()) {
                                if (first || !isInternalCall(stackTrace)) {
                                    stackTrace.forEach(pw::println);
                                    pw.println();
                                    first = false;
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            return;
        }
        throw new IllegalStateException("Not supporting overlapping session!");
    }
}
