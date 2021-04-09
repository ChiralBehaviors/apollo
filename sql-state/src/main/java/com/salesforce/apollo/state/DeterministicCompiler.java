/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.state;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.h2.api.ErrorCode;
import org.h2.api.Trigger;
import org.h2.engine.Constants;
import org.h2.engine.Session;
import org.h2.message.DbException;
import org.h2.value.Value;
import org.slf4j.Logger;

import com.salesforce.apollo.protocols.Utils;

import net.corda.djvm.SandboxConfiguration;
import net.corda.djvm.SandboxRuntimeContext;
import net.corda.djvm.TypedTaskFactory;
import net.corda.djvm.analysis.AnalysisConfiguration;
import net.corda.djvm.analysis.AnalysisConfiguration.Builder;
import net.corda.djvm.execution.ExecutionProfile;
import net.corda.djvm.messages.Severity;
import net.corda.djvm.rewiring.SandboxClassLoader;
import net.corda.djvm.source.ApiSource;
import net.corda.djvm.source.BootstrapClassLoader;
import net.corda.djvm.source.UserPathSource;
import net.corda.djvm.source.UserSource;
import sandbox.com.salesforce.apollo.dsql.JavaMethod;
import sandbox.com.salesforce.apollo.dsql.TriggerWrapper;

/**
 * Represents a class loading catolog of deterministic Java implemented
 * functions for SQL stored procedures, functions, triggers n' such.
 * 
 * @author hal.hildebrand
 *
 */
public class DeterministicCompiler implements UserSource {

    private static class ClassFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        JavaClassObject classObject;

        public ClassFileManager(StandardJavaFileManager standardManager) {
            super(standardManager);
        }

        @Override
        public ClassLoader getClassLoader(Location location) {
            return new SecureClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classObject.getBytes();
                    return super.defineClass(name, bytes, 0, bytes.length);
                }

                @Override
                protected URL findResource(String name) {
                    try {
                        return classObject.toUri().toURL();
                    } catch (MalformedURLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind,
                                                   FileObject sibling) throws IOException {
            classObject = new JavaClassObject(className, kind);
            return classObject;
        }
    }

    private static class JavaClassObject extends SimpleJavaFileObject {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public JavaClassObject(String name, Kind kind) {
            super(URI.create("string:///" + name.replace('.', '/') + kind.extension), kind);
        }

        public byte[] getBytes() {
            return out.toByteArray();
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return out;
        }
    }

    private static class StringJavaFileObject extends SimpleJavaFileObject {

        private final String sourceCode;

        public StringJavaFileObject(String className, String sourceCode) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }

    }

    public static final ApiSource             BOOTSTRAP;
    public final static AnalysisConfiguration DEFAULT_CONFIG;
    public static final Set<String>           OVERRIDE_CLASSES = overrideClasses();

    private static final String       BOOTSTRAP_JAR    = "/deterministic-rt.jar";
    private static final int          DOT_CLASS_LENGTH = ".class".length();
    private static final JavaCompiler JAVA_COMPILER;
    private final static Logger       log              = org.slf4j.LoggerFactory.getLogger(DeterministicCompiler.class);
    private static final String       SANDBOX_PREFIX   = "sandbox.";

    static {
        try {
            File tempFile = File.createTempFile("bootstrap", ".jar");
            tempFile.delete();
            tempFile.deleteOnExit();
            try (InputStream is = DeterministicCompiler.class.getResourceAsStream(BOOTSTRAP_JAR);
                    FileOutputStream os = new FileOutputStream(tempFile);) {
                Utils.copy(is, os);
                os.flush();
            }
            BOOTSTRAP = new BootstrapClassLoader(tempFile.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to cache boxotstrap jar", e);
        }
        JavaCompiler c;
        try {
            c = ToolProvider.getSystemJavaCompiler();
        } catch (Exception e) {
            throw new IllegalStateException("Java compiler required", e);
        }
        JAVA_COMPILER = c;
        DEFAULT_CONFIG = AnalysisConfiguration.createRoot(new UserPathSource(new URL[0]), Collections.emptySet(),
                                                          Severity.TRACE, BOOTSTRAP, OVERRIDE_CLASSES);
    }

    public static String getCompleteSourceCode(String packageName, String className, String source) {
        if (source.startsWith("package ")) {
            return source;
        }
        StringBuilder buff = new StringBuilder();
        if (packageName != null) {
            buff.append("package ").append(packageName).append(";\n");
        }
        int endImport = source.indexOf("@CODE");
        if (endImport >= 0) {
            source = source.substring("@CODE".length() + endImport);
            String importCode = """
                    import java.util.*;
                    import java.math.*;
                    import sandbox.java.sql.*;

                    """;
            importCode = source.substring(0, endImport);
            buff.append(importCode);
            buff.append("public class ")
                .append(className)
                .append(" {\n    public static ")
                .append(source)
                .append("\n}\n");
        } else {
            buff.append(source);
        }
        return buff.toString();
    }

    private static String getMethodSignature(Method m) {
        StringBuilder buff = new StringBuilder(m.getName());
        buff.append('(');
        Class<?>[] parameterTypes = m.getParameterTypes();
        for (int i = 0, length = parameterTypes.length; i < length; i++) {
            if (i > 0) {
                // do not use a space here, because spaces are removed
                // in CreateFunctionAlias.setJavaClassMethod()
                buff.append(',');
            }
            Class<?> p = parameterTypes[i];
            if (p.isArray()) {
                buff.append(unsandbox(p.getComponentType().getName())).append("[]");
            } else {
                buff.append(unsandbox(p.getName()));
            }
        }
        return buff.append(')').toString();
    }

    private static void handleSyntaxError(String output, int exitStatus) {
        if (0 == exitStatus) {
            return;
        }
        boolean syntaxError = false;
        final BufferedReader reader = new BufferedReader(new StringReader(output));
        try {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.endsWith("warning") || line.endsWith("warnings")) {
                    // ignore summary line
                } else if (line.startsWith("Note:") || line.startsWith("warning:")) {
                    // just a warning (e.g. unchecked or unsafe operations)
                } else {
                    syntaxError = true;
                    break;
                }
            }
        } catch (IOException ignored) {
            // exception ignored
        }

        if (syntaxError) {
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1, output);
        }
    }

    private static Set<String> overrideClasses() {
        return new HashSet<>(
                Arrays.asList("sandbox/com/salesforce/apollo/dsql/ConnectionWrapper", "sandbox/org/h2/api/Trigger",
                              "sandbox/java/sql/Array", "sandbox/java/sql/BatchUpdateException",
                              "sandbox/java/sql/Blob", "sandbox/java/sql/CallableStatement",
                              "sandbox/java/sql/ClientInfoStatus", "sandbox/java/sql/Clob",
                              "sandbox/java/sql/Connection", "sandbox/java/sql/ConnectionBuilder",
                              "sandbox/java/sql/DatabaseMetaData", "sandbox/java/sql/DataTruncation",
                              "sandbox/java/sql/Date", "sandbox/java/sql/Driver", "sandbox/java/sql/DriverAction",
                              "sandbox/java/sql/DriverInfo", "sandbox/java/sql/DriverManager",
                              "sandbox/java/sql/DriverPropertyInfo", "sandbox/java/sql/JDBCType",
                              "sandbox/java/sql/NClob", "sandbox/java/sql/ParameterMetaData",
                              "sandbox/java/sql/PreparedStatement", "sandbox/java/sql/PseudoColumnUsage",
                              "sandbox/java/sql/Ref", "sandbox/java/sql/ResultSet",
                              "sandbox/java/sql/ResultSetMetaData", "sandbox/java/sql/RowId",
                              "sandbox/java/sql/RowIdLifetime", "sandbox/java/sql/Savepoint",
                              "sandbox/java/sql/ShardingKey", "sandbox/java/sql/ShardingKeyBuilder",
                              "sandbox/java/sql/SQLClientInfoException", "sandbox/java/sql/SQLData",
                              "sandbox/java/sql/SQLDataException", "sandbox/java/sql/SQLException",
                              "sandbox/java/sql/SQLFeatureNotSupportedException", "sandbox/java/sql/SQLInput",
                              "sandbox/java/sql/SQLIntegrityConstraintViolationException",
                              "sandbox/java/sql/SQLInvalidAuthorizationSpecException",
                              "sandbox/java/sql/SQLNonTransientConnectionException",
                              "sandbox/java/sql/SQLNonTransientException", "sandbox/java/sql/SQLOutput",
                              "sandbox/java/sql/SQLPermission", "sandbox/java/sql/SQLRecoverableException",
                              "sandbox/java/sql/SQLSyntaxErrorException", "sandbox/java/sql/SQLTimeoutException",
                              "sandbox/java/sql/SQLTransactionRollbackException",
                              "sandbox/java/sql/SQLTransientConnectionException",
                              "sandbox/java/sql/SQLTransientException", "sandbox/java/sql/SQLType",
                              "sandbox/java/sql/SQLWarning", "sandbox/java/sql/SQLXML", "sandbox/java/sql/Statement",
                              "sandbox/java/sql/Struct", "sandbox/java/sql/Time", "sandbox/java/sql/Timestamp",
                              "sandbox/java/sql/Types", "sandbox/java/sql/Wrapper",
                              "sandbox/com/salesforce/apollo/dsql/ArrayWrapper",
                              "sandbox/com/salesforce/apollo/dsql/BlobWrapper",
                              "sandbox/com/salesforce/apollo/dsql/CallableStatementWrapper",
                              "sandbox/com/salesforce/apollo/dsql/ClobWrapper",
                              "sandbox/com/salesforce/apollo/dsql/ConnectionWrapper",
                              "sandbox/com/salesforce/apollo/dsql/DatabaseMetadataWrapper",
                              "sandbox/com/salesforce/apollo/dsql/NClobWrapper",
                              "sandbox/com/salesforce/apollo/dsql/ParameterMetaDataWrapper",
                              "sandbox/com/salesforce/apollo/dsql/PreparedStatementWrapper",
                              "sandbox/com/salesforce/apollo/dsql/RefWrapper",
                              "sandbox/com/salesforce/apollo/dsql/ResultSetMetaDataWrapper",
                              "sandbox/com/salesforce/apollo/dsql/ResultSetWrapper",
                              "sandbox/com/salesforce/apollo/dsql/RowIdWrapper",
                              "sandbox/com/salesforce/apollo/dsql/SavepointWrapper",
                              "sandbox/com/salesforce/apollo/dsql/ShardingKeyWrapper",
                              "sandbox/com/salesforce/apollo/dsql/SQLXMLWrapper",
                              "sandbox/com/salesforce/apollo/dsql/StatementWrapper",
                              "sandbox/com/salesforce/apollo/dsql/StructWrapper",
                              "sandbox/com/salesforce/apollo/dsql/TriggerWrapper",
                              "sandbox/com/salesforce/apollo/dsql/JavaMethod"));
    }

    private static File tempDir() throws IllegalStateException {
        File tempDir;
        try {
            tempDir = File.createTempFile("functions-" + UUID.randomUUID(), "dir");
        } catch (IOException e) {
            throw new IllegalStateException("unable to create temp directory for cached classes");
        }
        tempDir.delete();
        tempDir.mkdirs();
        tempDir.deleteOnExit();
        return tempDir;
    }

    private static String unsandbox(String className) {
        return className.startsWith(SANDBOX_PREFIX) ? className.substring(SANDBOX_PREFIX.length() + 1) : className;
    }

    private final File              cacheDir;
    private final Map<String, File> compiledClasses = new ConcurrentHashMap<>();

    private final SandboxRuntimeContext context;

    public DeterministicCompiler() throws IOException, ClassNotFoundException, IllegalStateException {
        this(DEFAULT_CONFIG, ExecutionProfile.DEFAULT, tempDir());
    }

    public DeterministicCompiler(AnalysisConfiguration config, ExecutionProfile execution, File cacheDir)
            throws ClassNotFoundException, IOException {
        Utils.clean(cacheDir);
        cacheDir.mkdirs();
        if (!cacheDir.isDirectory()) {
            throw new IllegalArgumentException(cacheDir.getAbsolutePath() + " must be directory");
        }
        this.cacheDir = cacheDir;
        Builder childConfig = config.createChild(this);

        SandboxConfiguration cfg = SandboxConfiguration.createFor(childConfig.build(), execution);
        cfg.preload();
        context = new SandboxRuntimeContext(cfg);
    }

    @Override
    public void close() throws Exception {
        compiledClasses.clear();
    }

    public <T> Class<T> compile(String packageAndClassName, String source) throws ClassNotFoundException {

        ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {

            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                String packageName = null;
                int idx = name.lastIndexOf('.');
                String className;
                if (idx >= 0) {
                    packageName = name.substring(0, idx);
                    className = name.substring(idx + 1);
                } else {
                    className = name;
                }
                String s = getCompleteSourceCode(packageName, className, source);
                s = source;
                byte[] classBytes = javaxToolsJavac(packageName, className, s);
                String binaryName = toBinaryName(packageAndClassName);
                File clazzFile;
                try {
                    clazzFile = File.createTempFile(binaryName, "class", cacheDir);
                } catch (IOException e) {
                    throw new ClassNotFoundException("unable to store: " + packageAndClassName, e);
                }

                try (OutputStream os = new FileOutputStream(clazzFile)) {
                    os.write(classBytes);
                } catch (IOException e) {
                    throw new ClassNotFoundException("unable to store: " + packageAndClassName, e);
                }

                compiledClasses.put(binaryName, clazzFile);
                return defineClass(binaryName, classBytes, 0, classBytes.length);
            }
        };
        @SuppressWarnings("unchecked")
        Class<T> clazz = (Class<T>) classLoader.loadClass(packageAndClassName);
        return clazz;
    }

    public void compileClass(String packageAndClassName, String source) throws ClassNotFoundException {
        String packageName = null;
        int idx = packageAndClassName.lastIndexOf('.');
        String className;
        if (idx >= 0) {
            packageName = packageAndClassName.substring(0, idx);
            className = packageAndClassName.substring(idx + 1);
        } else {
            className = packageAndClassName;
        }
        byte[] classBytes = javaxToolsJavac(packageName, className,
                                            getCompleteSourceCode(packageName, className, source));
        String binaryName = toBinaryName(packageAndClassName);
        File clazzFile;
        try {
            clazzFile = File.createTempFile(binaryName, "class", cacheDir);
        } catch (IOException e) {
            throw new ClassNotFoundException("unable to store: " + packageAndClassName, e);
        }

        try (OutputStream os = new FileOutputStream(clazzFile)) {
            os.write(classBytes);
        } catch (IOException e) {
            throw new ClassNotFoundException("unable to store: " + packageAndClassName, e);
        }

        compiledClasses.put(binaryName, clazzFile);
    }

    public void execute(Class<? extends Function<long[], Long>> clazz) {
        context.use(ctx -> {
            SandboxClassLoader cl = ctx.getClassLoader();

            // Create a reusable task factory.
            TypedTaskFactory taskFactory;
            try {
                taskFactory = cl.createTypedTaskFactory();
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new IllegalStateException();
            }

            // Wrap SimpleTask inside an instance of sandbox.Task.
            Function<long[], Long> simpleTask = taskFactory.create(clazz);

            // Execute SimpleTask inside the sandbox.
            @SuppressWarnings("unused")
            Long result = simpleTask.apply(new long[] { 1000, 200, 30, 4 });
        });
    }

    @Override
    public URL findResource(String name) {
        String binaryName = toBinaryName(name);
        File file = compiledClasses.get(binaryName);
        if (file == null) {
            return null;
        }
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("unable to construct url for: " + file.getAbsolutePath(), e);
        }
    }

    @Override
    public Enumeration<URL> findResources(String name) {
        return new Enumeration<URL>() {
            private URL next = findResource(name);

            @Override
            public boolean hasMoreElements() {
                return (next != null);
            }

            @Override
            public URL nextElement() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                URL u = next;
                next = null;
                return u;
            }
        };
    }

    /**
     * Get the first public static method of the given class.
     *
     * @param className the class name
     * @return the method name
     */
    public Method getMethod(String className) throws ClassNotFoundException {
        Class<?> clazz = getClass(SANDBOX_PREFIX + className);
        Method[] methods = clazz.getDeclaredMethods();
        for (Method m : methods) {
            int modifiers = m.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) {
                String name = m.getName();
                if (!name.startsWith("_") && !m.getName().equals("main")) {
                    return m;
                }
            }
        }
        return null;
    }

    public BiFunction<Session, Value[], Object> getMethod(String className, String method, String source) {
        AtomicReference<BiFunction<Session, Value[], Object>> m = new AtomicReference<>();
        context.use(ctx -> {
            try {
                compileClass(className, source);
                Class<?> clazz = ctx.getClassLoader().loadClass(SANDBOX_PREFIX + className);

                Method call = null;
                for (Method mth : clazz.getDeclaredMethods()) {
                    if (method.equals(mth.getName())) {
                        call = mth;
                        break;
                    }
                }

                if (call == null) {
                    throw DbException.get(ErrorCode.SYNTAX_ERROR_1, new IllegalArgumentException(
                            "Must contain invocation method named: " + method + "(...)"), source);
                }
                Object instance = clazz.getDeclaredConstructor().newInstance();
                SandboxJavaMethod sandboxJavaMethod = wrap(call, 0, ctx.getClassLoader());
                m.set((session, args) -> sandboxJavaMethod.invoke(instance, session, args));
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1, e, source);
            }

        });
        return m.get();
    }

    @Override
    public URL[] getURLs() {
        return new URL[0];
    }

    public SandboxJavaMethod[] loadFunction(String className, String methodName) {
        AtomicReference<SandboxJavaMethod[]> methodsList = new AtomicReference<>();
        context.use(ctx -> {
            SandboxClassLoader cl = ctx.getClassLoader();
            Class<?> javaClass;
            try {
                javaClass = cl.loadClass(className);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot find sandboxed class: " + className, e);
            }
            Method[] methods = javaClass.getMethods();
            ArrayList<SandboxJavaMethod> list = new ArrayList<>(1);
            for (int i = 0, len = methods.length; i < len; i++) {
                Method m = methods[i];
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (m.getName().equals(methodName) || getMethodSignature(m).equals(methodName)) {
                    SandboxJavaMethod javaMethod;
                    try {
                        javaMethod = wrap(m, i, cl);
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot construct JavaMethod wrapper on: " + m, e);
                    }
                    for (SandboxJavaMethod old : list) {
                        if (old.getParameterCount() == javaMethod.getParameterCount()) {
                            throw DbException.get(ErrorCode.METHODS_MUST_HAVE_DIFFERENT_PARAMETER_COUNTS_2,
                                                  old.toString(), javaMethod.toString());
                        }
                    }
                    list.add(javaMethod);
                }
            }
            if (list.isEmpty()) {
                throw DbException.get(ErrorCode.PUBLIC_STATIC_JAVA_METHOD_NOT_FOUND_1,
                                      methodName + " (" + className + ")");
            }
            SandboxJavaMethod[] javaMethods = list.toArray(new SandboxJavaMethod[0]);
            // Sort elements. Methods with a variable number of arguments must be at
            // the end. Reason: there could be one method without parameters and one
            // with a variable number. The one without parameters needs to be used
            // if no parameters are given.
            Arrays.sort(javaMethods);
            methodsList.set(javaMethods);
        });
        return methodsList.get();
    }

    public SandboxJavaMethod[] loadFunctionFromSource(String name, String source) {
        AtomicReference<SandboxJavaMethod[]> returned = new AtomicReference<>();
        context.use(ctx -> {
            String fullClassName = Constants.USER_PACKAGE + "." + name;
            try {
                compileClass(fullClassName, source);
                Method m = getMethod(fullClassName);
                SandboxJavaMethod method = wrap(m, 0, ctx.getClassLoader());
                returned.set(new SandboxJavaMethod[] { method });
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1, e, source);
            }
        });
        return returned.get();
    }

    public Trigger loadTrigger(String packageAndClassName) {
        AtomicReference<Trigger> holder = new AtomicReference<>();

        context.use(ctx -> {
            SandboxClassLoader cl = ctx.getClassLoader();

            try {
                holder.set(new SandboxTrigger(context,
                        cl.loadClass(TriggerWrapper.class.getCanonicalName())
                          .getDeclaredConstructor(Object.class)
                          .newInstance(cl.loadClass(SANDBOX_PREFIX + packageAndClassName)
                                         .getDeclaredConstructor()
                                         .newInstance())));
            } catch (Exception e) {
                throw DbException.get(ErrorCode.ERROR_CREATING_TRIGGER_OBJECT_3, e, packageAndClassName, "..source..",
                                      e.toString());
            }
        });
        return holder.get();
    }

    public Trigger loadTriggerFromSource(String name, String triggerSource) {
        AtomicReference<Trigger> trigger = new AtomicReference<>();
        context.use(ctx -> {
            String fullClassName = Constants.USER_PACKAGE + ".trigger." + name;
            try {
                String source = getCompleteSourceCode(Constants.USER_PACKAGE, name, triggerSource);
                compileClass(fullClassName, source);
                final Method m = getMethod(fullClassName);
                if (m.getParameterTypes().length > 0) {
                    throw new IllegalStateException("No parameters are allowed for a trigger");
                }
                trigger.set(new SandboxTrigger(context,
                        new SandboxTrigger(context,
                                ctx.getClassLoader()
                                   .loadClass(TriggerWrapper.class.getCanonicalName())
                                   .getDeclaredConstructor(Object.class)
                                   .newInstance(m.invoke(null)))));
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw DbException.get(ErrorCode.ERROR_CREATING_TRIGGER_OBJECT_3, e, name, triggerSource, e.toString());
            }
        });
        return trigger.get();
    }

    private Class<?> getClass(String className) {
        AtomicReference<Class<?>> clazz = new AtomicReference<>();
        context.use(ctx -> {
            try {
                clazz.set(ctx.getClassLoader().loadClass(className));
            } catch (ClassNotFoundException e) {
                log.warn("Failed to load class: {}", className, e);
            }
        });
        return clazz.get();
    }

    private byte[] javaxToolsJavac(String packageName, String className, String source) {
        String fullClassName = packageName == null ? className : packageName + "." + className;
        StringWriter writer = new StringWriter();
        try (ClassFileManager fileManager = new ClassFileManager(
                JAVA_COMPILER.getStandardFileManager(null, null, null))) {
            ArrayList<JavaFileObject> compilationUnits = new ArrayList<>();
            compilationUnits.add(new StringJavaFileObject(fullClassName, source));
            // cannot concurrently compile
            final boolean ok;
            synchronized (JAVA_COMPILER) {
                ok = JAVA_COMPILER.getTask(writer, fileManager, null, Arrays.asList("-target", "1.8", "-source", "1.8"),
                                           null, compilationUnits)
                                  .call();
            }
            String output = writer.toString();
            handleSyntaxError(output, (ok ? 0 : 1));
            return fileManager.classObject.getBytes();
        } catch (IOException e) {
            // ignored
            return null;
        }
    }

    /**
     * Converts a "resource name" (as used in the getResource* methods) to a binary
     * name if the name identifies a class
     * 
     * @param name the resource name
     * @return the binary name
     */
    private String toBinaryName(String name) {
        if (!name.endsWith(".class")) {
            return name;
        }
        return name.substring(0, name.length() - DOT_CLASS_LENGTH).replace('/', '.');
    }

    private SandboxJavaMethod wrap(Method method, int index, SandboxClassLoader classLoader) throws Exception {
        return new SandboxJavaMethod(context,
                classLoader.loadClass(JavaMethod.class.getCanonicalName())
                           .getDeclaredConstructor(Method.class)
                           .newInstance(method),
                index);
    }
}