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
package org.cstamas.maven.components.mojosniffer;

import javax.inject.Inject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.classrealm.ClassRealmManager;
import org.apache.maven.execution.MavenSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mojo sniffer component. It cannot use ctor injection, as it is instantiated in {@link MojoSnifferModule}
 * and only then is injected.
 */
public class MojoSniffer {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DUMPER_ENABLED = MojoSniffer.class.getSimpleName() + ".enabled";
    public static final String DUMPER_STACKTRACE = MojoSniffer.class.getSimpleName() + ".stacktrace";
    public static final String DUMPER_FILE = MojoSniffer.class.getSimpleName() + ".file";
    // number of lines from the stack trace to discard
    // those come from the interception mechanism
    private static final int LINES_TO_IGNORE = 6;

    private final boolean enabled;

    private final boolean printStackTrace;

    private final PrintWriter writer;
    private final Map<String, Set<List<String>>> invocations = new ConcurrentHashMap<>();

    private ClassRealmManager classRealmManager;

    @Inject
    public MojoSniffer() {
        this.enabled = Boolean.getBoolean(DUMPER_ENABLED);
        this.printStackTrace = Boolean.getBoolean(DUMPER_STACKTRACE);
        PrintWriter pw = null;
        if (this.enabled) {
            String file = System.getProperty(DUMPER_FILE, "target/sniffer.log");
            Path path = Paths.get(file);
            try {
                Files.createDirectories(path.getParent());
                pw = new PrintWriter(Files.newBufferedWriter(Paths.get(file)));
            } catch (IOException e) {
                System.err.println("Unable to write to " + file + ", using stdout (" + e + ")");
                pw = new PrintWriter(new OutputStreamWriter(System.out));
            }
        }
        this.writer = pw;
    }

    @Inject
    public void inject(ClassRealmManager classRealmManager) {
        this.classRealmManager = classRealmManager;

        if (enabled) {
            logger.info("Mojo Sniffer enabled {}", classRealmManager);
        }
    }

    private void log(MethodInvocation methodInvocation) {
        if (!enabled) {
            return;
        }
        String method = methodInvocation.getMethod().toGenericString();
        Set<List<String>> stackTraces = this.invocations.computeIfAbsent(method, k -> ConcurrentHashMap.newKeySet());
        StringWriter sw = new StringWriter();
        new Throwable().printStackTrace(new PrintWriter(sw));
        List<String> stackTrace =
                Stream.of(sw.toString().split("\n")).skip(LINES_TO_IGNORE).collect(Collectors.toList());
        if (stackTraces.add(stackTrace)) {
            if (!isInternalCall(stackTrace)) {
                synchronized (writer) {
                    if (printStackTrace) {
                        writer.println(method);
                        stackTrace.forEach(writer::println);
                    } else {
                        writer.println("Using maven-compat deprecated method: " + method);
                    }
                    if (writer.checkError()) {
                        System.err.println("MavenCompatDumper in error");
                    }
                }
            }
        }
    }

    /**
     * Disable calls from within maven-compat
     */
    private boolean isInternalCall(List<String> stackTrace) {
        return stackTrace.stream().anyMatch(s -> s.contains(MojoSniffer.class.getName()));
    }

    private Object intercept(MethodInvocation methodInvocation) throws Throwable {
        log(methodInvocation);
        return methodInvocation.proceed();
    }

    private boolean matchClass(Class<?> aClass) {
        if (!aClass.isInterface() && !aClass.isEnum()) {
            return isMavenCompat(aClass) || ArtifactRepository.class.isAssignableFrom(aClass);
        }
        return false;
    }

    private boolean matchMethod(Method method) {
        return isMavenCompat(method.getDeclaringClass());
    }

    private boolean isMavenCompat(Class<?> aClass) {
        ClassLoader cl = aClass != null ? aClass.getClassLoader() : null;
        URL url = cl != null ? cl.getResource(aClass.getName().replace('.', '/') + ".class") : null;
        return url != null && url.getPath().contains("/maven-compat-");
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
        logger.info("Session started");
    }

    public void sessionStopped(MavenSession session) {
        logger.info("Session stopped");
    }
}
