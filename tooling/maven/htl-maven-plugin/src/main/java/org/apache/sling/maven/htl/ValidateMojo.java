/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.sling.maven.htl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.sling.maven.htl.compiler.HTLClassInfo;
import org.apache.sling.maven.htl.compiler.HTLJavaImportsAnalyzer;
import org.apache.sling.maven.htl.compiler.ScriptCompilationUnit;
import org.apache.sling.scripting.sightly.compiler.CompilationResult;
import org.apache.sling.scripting.sightly.compiler.CompilerMessage;
import org.apache.sling.scripting.sightly.compiler.SightlyCompiler;
import org.apache.sling.scripting.sightly.java.compiler.ClassInfo;
import org.apache.sling.scripting.sightly.java.compiler.JavaClassBackendCompiler;
import org.apache.sling.scripting.sightly.java.compiler.JavaImportsAnalyzer;
import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Validates HTL scripts.
 */
@Mojo(
        name = "validate",
        defaultPhase = LifecyclePhase.COMPILE,
        threadSafe = true
)
public class ValidateMojo extends AbstractMojo {

    private static final String DEFAULT_INCLUDES = "**/*.html";
    private static final int _16K = 16384;

    @Component
    private BuildContext buildContext;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * Defines the root folder where this Mojo expects to find Sightly scripts to validate.
     */
    @Parameter(property = "htl.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    /**
     * List of files to include. Specified as fileset patterns which are relative to the input directory whose contents will be scanned
     * (see the sourceDirectory configuration option).
     */
    @Parameter(defaultValue = DEFAULT_INCLUDES)
    private String[] includes;

    /**
     * List of files to exclude. Specified as fileset patterns which are relative to the input directory whose contents will be scanned
     * (see the sourceDirectory configuration option).
     */
    @Parameter
    private String[] excludes;

    /**
     * If set to "true" it will fail the build on compiler warnings.
     */
    @Parameter(property = "htl.failOnWarnings", defaultValue = "false")
    private boolean failOnWarnings;

    /**
     * If set to "true" it will generate the Java classes resulted from transpiling the HTL scripts to Java. The generated classes will
     * be stored in the folder identified by the {@code generatedJavaClassesDirectory} parameter.
     */
    @Parameter(property = "htl.generateJavaClasses", defaultValue = "false")
    private boolean generateJavaClasses;

    /**
     * Defines the folder where the generated Java classes resulted from transpiling the project's HTL scripts will be stored. This
     * folder will be added to the list of source folders for this project.
     */
    @Parameter(property = "htl.generatedJavaClassesDirectory", defaultValue = "${project.build.directory}/generated-sources/htl")
    private File generatedJavaClassesDirectory;

    /**
     * Defines a list of Java packages that should be ignored when generating the import statements for the Java classes resulted from
     * transpiling the project's HTL scripts. Subpackages of these packages will also be part automatically of the ignore list.
     */
    @Parameter(property = "htl.ignoreImports")
    private Set<String> ignoreImports;

    /**
     * If set to "true" the validation will be skipped.
     */
    @Parameter(property = "htl.skip", defaultValue = "false")
    private boolean skip;

    private boolean hasWarnings = false;
    private boolean hasErrors = false;
    private List<File> processedFiles = Collections.emptyList();

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping validation.");
            return;
        }

        long start = System.currentTimeMillis();

        if (!sourceDirectory.isAbsolute()) {
            sourceDirectory = new File(project.getBasedir(), sourceDirectory.getPath());
        }
        if (!sourceDirectory.exists()) {
            getLog().info("Source directory does not exist, skipping.");
            return;
        }
        if (!sourceDirectory.isDirectory()) {
            throw new MojoExecutionException(
                    String.format("Configured sourceDirectory={%s} is not a directory.", sourceDirectory.getAbsolutePath()));
        }
        if (generateJavaClasses) {
            // validate generated Java classes folder
            if (!generatedJavaClassesDirectory.isAbsolute()) {
                generatedJavaClassesDirectory = new File(project.getBasedir(), generatedJavaClassesDirectory.getPath());
            }
            if (generatedJavaClassesDirectory.exists() && !generatedJavaClassesDirectory.isDirectory()) {
                throw new MojoExecutionException(String.format("Configured generatedJavaClassesDirectory={%s} is not a directory.",
                        generatedJavaClassesDirectory.getAbsolutePath()));
            }
            if (!generatedJavaClassesDirectory.exists()) {
                if (!generatedJavaClassesDirectory.mkdirs()) {
                    throw new MojoExecutionException(String.format("Unable to generate generatedJavaClassesDirectory={%s}.",
                            generatedJavaClassesDirectory.getAbsolutePath()));
                }
            }
            project.addCompileSourceRoot(generatedJavaClassesDirectory.getPath());
        }

        if (!buildContext.hasDelta(sourceDirectory)) {
            getLog().info("No files found to validate, skipping.");
            return;
        }

        // don't fail execution in Eclipse as it generates an error marker in the POM file, which is not desired
        boolean mayFailExecution = !buildContext.getClass().getName().startsWith("org.eclipse.m2e");

        try {
            Scanner scanner = buildContext.newScanner(sourceDirectory);
            scanner.setExcludes(excludes);
            scanner.setIncludes(includes);
            scanner.scan();

            String[] includedFiles = scanner.getIncludedFiles();

            processedFiles = new ArrayList<>(includedFiles.length);
            for (String includedFile : includedFiles) {
                processedFiles.add(new File(sourceDirectory, includedFile));
            }
            Map<File, CompilationResult> compilationResults;
            if (generateJavaClasses) {
                compilationResults = transpileHTLScriptsToJavaClasses(processedFiles, new SightlyCompiler(), new HTLJavaImportsAnalyzer
                        (ignoreImports));
            } else {
                compilationResults = compileHTLScripts(processedFiles, new SightlyCompiler());
            }
            for (Map.Entry<File, CompilationResult> entry : compilationResults.entrySet()) {
                File script = entry.getKey();
                CompilationResult result = entry.getValue();
                buildContext.removeMessages(script);

                if (result.getWarnings().size() > 0) {
                    for (CompilerMessage message : result.getWarnings()) {
                        buildContext.addMessage(script, message.getLine(), message.getColumn(), message.getMessage(),
                                BuildContext.SEVERITY_WARNING, null);
                    }
                    hasWarnings = true;
                }
                if (result.getErrors().size() > 0) {
                    for (CompilerMessage message : result.getErrors()) {
                        String messageString = message.getMessage().replaceAll(System.lineSeparator(), "");
                        buildContext
                                .addMessage(script, message.getLine(), message.getColumn(), messageString, BuildContext.SEVERITY_ERROR,
                                        null);
                    }
                    hasErrors = true;
                }
            }

            getLog().info("Processed " + processedFiles.size() + " files in " + (System.currentTimeMillis() - start) + "ms");

            if (mayFailExecution && hasWarnings && failOnWarnings) {
                throw new MojoFailureException("Compilation warnings were configured to fail the build.");
            }
            if (mayFailExecution && hasErrors) {
                throw new MojoFailureException("Please check the reported syntax errors.");
            }
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Cannot filter files from {%s} with includes {%s} and excludes {%s}.",
                    sourceDirectory.getAbsolutePath(), includes, excludes), e);
        }

    }

    private Map<File, CompilationResult> transpileHTLScriptsToJavaClasses(List<File> scripts, SightlyCompiler compiler, JavaImportsAnalyzer
            javaImportsAnalyzer) throws IOException {
        Map<File, CompilationResult> compilationResult = new LinkedHashMap<>(scripts.size());
        for (File script : scripts) {
            JavaClassBackendCompiler backendCompiler = new JavaClassBackendCompiler(javaImportsAnalyzer);
            ScriptCompilationUnit compilationUnit = new ScriptCompilationUnit(sourceDirectory, script);
            compilationResult.put(script, compiler.compile(compilationUnit, backendCompiler));
            ClassInfo classInfo = new HTLClassInfo(script.getPath().replaceFirst(sourceDirectory.getAbsolutePath(), ""));
            String javaSourceCode = backendCompiler.build(classInfo);
            File generatedClassFile = new File(generatedJavaClassesDirectory, classInfo.getFullyQualifiedClassName()
                    .replaceAll("\\.", File.separator) + ".java");
            generatedClassFile.getParentFile().mkdirs();
            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(generatedClassFile), _16K));
            out.write(javaSourceCode);
            compilationUnit.dispose();
            out.close();
        }
        return compilationResult;
    }

    private Map<File, CompilationResult> compileHTLScripts(List<File> scripts, SightlyCompiler compiler) throws IOException {
        Map<File, CompilationResult> compilationResult = new LinkedHashMap<>(scripts.size());
        for (File script : scripts) {
            ScriptCompilationUnit scriptCompilationUnit = new ScriptCompilationUnit(sourceDirectory, script);
            compilationResult.put(script, compiler.compile(scriptCompilationUnit));
            scriptCompilationUnit.dispose();
        }
        return compilationResult;
    }
    // visible for testing only
    void setBuildContext(BuildContext buildContext) {
        this.buildContext = buildContext;
    }

    boolean hasWarnings() {
        return hasWarnings;
    }

    boolean hasErrors() {
        return hasErrors;
    }

    List<File> getProcessedFiles() {
        return processedFiles;
    }
}
