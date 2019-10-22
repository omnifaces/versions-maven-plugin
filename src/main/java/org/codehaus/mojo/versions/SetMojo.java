/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.codehaus.mojo.versions;

import static java.lang.Integer.parseInt;
import static org.apache.maven.artifact.ArtifactUtils.versionlessKey;
import static org.codehaus.mojo.versions.api.PomHelper.getArtifactId;
import static org.codehaus.mojo.versions.api.PomHelper.getChildModels;
import static org.codehaus.mojo.versions.api.PomHelper.getGroupId;
import static org.codehaus.mojo.versions.api.PomHelper.getLocalRoot;
import static org.codehaus.mojo.versions.api.PomHelper.getModelEntry;
import static org.codehaus.mojo.versions.api.PomHelper.getRawModel;
import static org.codehaus.mojo.versions.api.PomHelper.getReactorModels;
import static org.codehaus.mojo.versions.api.PomHelper.isExplicitVersion;
import static org.codehaus.mojo.versions.utils.RegexUtils.convertWildcardsToRegex;
import static org.codehaus.mojo.versions.utils.RegexUtils.getWildcardScore;
import static org.codehaus.plexus.util.StringUtils.isBlank;
import static org.codehaus.plexus.util.StringUtils.isEmpty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.change.VersionChange;
import org.codehaus.mojo.versions.change.VersionChanger;
import org.codehaus.mojo.versions.change.VersionChangerFactory;
import org.codehaus.mojo.versions.ordering.ReactorDepthComparator;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.mojo.versions.utils.ContextualLog;
import org.codehaus.mojo.versions.utils.DelegatingContextualLog;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.codehaus.plexus.components.interactivity.PrompterException;
import org.codehaus.plexus.util.StringUtils;

/**
 * Sets the current project's version and based on that change propagates that change onto any child modules as
 * necessary.
 *
 * @author Stephen Connolly
 * @since 1.0-beta-1
 */
@Mojo(name = "set", requiresProject = true, requiresDirectInvocation = true, aggregator = true, threadSafe = true)
public class SetMojo extends AbstractVersionsUpdaterMojo {

    private static final String SNAPSHOT = "-SNAPSHOT";

    /**
     * The new version number to set.
     *
     * @since 1.0-beta-1
     */
    @Parameter(property = "newVersion")
    private String newVersion;

    /**
     * The groupId of the dependency/module to update. If you like to update modules of a aggregator you should set
     * <code>-DgroupId='*'</code> to ignore the group of the current project. On Windows you can omit the single quotes on
     * Linux they are necessary to prevent expansion through the shell.
     *
     * @since 1.2
     */
    @Parameter(property = "groupId", defaultValue = "${project.groupId}")
    private String groupId;

    /**
     * The artifactId of the dependency/module to update. If you like to update modules of a aggregator you should set
     * <code>-DartifactId='*'</code> to ignore the artifactId of the current project. On Windows you can omit the single
     * quotes on Linux they are necessary to prevent expansion through the shell.
     *
     * @since 1.2
     */
    @Parameter(property = "artifactId", defaultValue = "${project.artifactId}")
    private String artifactId;

    /**
     * The version of the dependency/module to update. If you are changing an aggregator you should give
     * <code>-DoldVersion='*'</code> to suppress the check against the version of the current project. On Windows you can
     * omit the single quotes on Linux they are necessary to prevent expansion through the shell.
     *
     * @since 1.2
     */
    @Parameter(property = "oldVersion", defaultValue = "${project.version}")
    private String oldVersion;

    /**
     * Whether matching versions explicitly specified (as /project/version) in child modules should be updated.
     *
     * @since 1.3
     */
    @Parameter(property = "updateMatchingVersions", defaultValue = "true")
    private boolean updateMatchingVersions;

    /**
     * Whether to process the parent of the project.
     *
     * @since 1.3
     */
    @Parameter(property = "processParent", defaultValue = "true")
    private boolean processParent;

    /**
     * Whether to process the project version.
     *
     * @since 1.3
     */
    @Parameter(property = "processProject", defaultValue = "true")
    private boolean processProject;

    /**
     * Whether to process the dependencies section of the project.
     *
     * @since 1.3
     */
    @Parameter(property = "processDependencies", defaultValue = "true")
    private boolean processDependencies;

    /**
     * Whether to process the plugins section of the project.
     *
     * @since 1.3
     */
    @Parameter(property = "processPlugins", defaultValue = "true")
    private boolean processPlugins;

    /**
     * Component used to prompt for input
     *
     * @component
     */
    @Component
    private Prompter prompter;

    /**
     * Whether to remove <code>-SNAPSHOT</code> from the existing version.
     *
     * @since 2.10
     */
    @Parameter(property = "removeSnapshot", defaultValue = "false")
    private boolean removeSnapshot;

    /**
     * Whether to add next version number and <code>-SNAPSHOT</code> to the existing version.
     *
     * @since 2.10
     */
    @Parameter(property = "nextSnapshot", defaultValue = "false")
    private boolean nextSnapshot;

    /**
     * Whether to process all modules whereas they have parent/child or not.
     *
     * @since 2.5
     */
    @Parameter(property = "processAllModules", defaultValue = "false")
    private boolean processAllModules;

    /**
     * The changes to module coordinates. Guarded by this.
     */
    private final transient List<VersionChange> sourceChanges = new ArrayList<>();


    /**
     * Called when this mojo is executed.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong.
     * @throws org.apache.maven.plugin.MojoFailureException when things go wrong.
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (getProject().getOriginalModel().getVersion() == null) {
            throw new MojoExecutionException("Project version is inherited from parent.");
        }

        if (removeSnapshot && !nextSnapshot) {
            String version = getVersion();
            if (version.endsWith(SNAPSHOT)) {
                newVersion = version.substring(0, version.indexOf(SNAPSHOT));
                getLog().info("SNAPSHOT found.  BEFORE " + version + "  --> AFTER: " + newVersion);
            }
        }

        if (!removeSnapshot && nextSnapshot) {
            String version = getVersion();
            String versionWithoutSnapshot = version;
            if (version.endsWith(SNAPSHOT)) {
                versionWithoutSnapshot = version.substring(0, version.indexOf(SNAPSHOT));
            }
            
            LinkedList<String> numbers = new LinkedList<>();
            if (versionWithoutSnapshot.contains(".")) {
                // Chop the version into numbers by splitting on the dot (.)
                Collections.addAll(numbers, versionWithoutSnapshot.split("\\."));
            } else {
                // The version contains no dots, assume that it is only 1 number
                numbers.add(versionWithoutSnapshot);
            }

            numbers.addLast(String.valueOf(parseInt(numbers.removeLast()) + 1));
            String nextVersion = StringUtils.join(numbers.toArray(new String[0]), ".");
            newVersion = nextVersion + "-SNAPSHOT";
            
            getLog().info("SNAPSHOT found.  BEFORE " + version + "  --> AFTER: " + newVersion);
        }
        
        validateNewVersionSet();

        try {
            MavenProject project = getMavenProject();
            SortedMap<String, Model> reactor = getReactor(project);

            // Set of files to update
            Set<File> files = new LinkedHashSet<>();

            getLog().info("Local aggregation root: " + project.getBasedir());
            getLog().info("Processing change of " + groupId + ":" + artifactId + ":" + oldVersion + " -> " + newVersion);
            
            Pattern groupIdRegex = toRegex(groupId);
            Pattern artifactIdRegex = toRegex(artifactId);
            Pattern oldVersionIdRegex = toRegex(oldVersion);
            
            boolean found = false;
            for (Model model : reactor.values()) {
                
                String modelGroupId = getGroupId(model);
                String modelArtifactId = getArtifactId(model);
                String modelVersion = PomHelper.getVersion(model);
                
                if (((matches(modelGroupId,groupIdRegex) && matches(modelArtifactId,artifactIdRegex)) //
                        || processAllModules) //
                        && matches(modelVersion, oldVersionIdRegex) && !newVersion.equals(modelVersion)) {
                    
                    found = true;
                    
                    // If the change is not one we have swept up already
                    collectChanges(
                        project, reactor, files, 
                        modelGroupId, model.getArtifactId(),
                        isBlank(oldVersion) || "*".equals(oldVersion) ? "" : model.getVersion());
                }
            }
            
            if (!found && getWildcardScore(groupId) == 0 && getWildcardScore(artifactId) == 0 && getWildcardScore(oldVersion) == 0) {
                collectChanges(
                    project, reactor, files, 
                    groupId, artifactId, oldVersion);
            }

            // Now process all the updates
            for (File file : files) {
                process(file);
            }

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void collectChanges(MavenProject project, SortedMap<String, Model> reactor, Set<File> files, String groupId, String artifactId, String oldVersion) {

        getLog().debug("Applying change " + groupId + ":" + artifactId + ":" + oldVersion + " -> " + newVersion);
        
        // This is a triggering change
        addChange(groupId, artifactId, oldVersion, newVersion);
        
        // Now fake out the triggering change

        Entry<String, Model> modelEntry = getModelEntry(reactor, groupId, artifactId);
        modelEntry.getValue().setVersion(newVersion);

        addFile(files, project, modelEntry.getKey());

        for (Entry<String, Model> sourceEntry : filterModules(reactor)) {
            String sourcePath = sourceEntry.getKey();
            Model sourceModel = sourceEntry.getValue();
            
            String sourceGroupId = getGroupId(sourceModel);
            String sourceArtifactId = getArtifactId(sourceModel);
            String sourceVersion = PomHelper.getVersion(sourceModel);

            getLog().debug(sourcePath.length() == 0 ? "Processing root module as parent" : "Processing " + sourcePath + " as a parent.");

            addFile(files, project, sourcePath);

            getLog().debug("Looking for modules which use " + versionlessKey(sourceGroupId, sourceArtifactId) + " as their parent");

            for (Entry<String, Model> sourceModelEntry : getModules(reactor, sourceGroupId, sourceArtifactId)) {
                
                Model targetModel = sourceModelEntry.getValue();
                Parent parent = targetModel.getParent();
                
                logModuleChange(sourceModelEntry, parent, sourceVersion, sourceGroupId, sourceArtifactId);
                
                if ((updateMatchingVersions || !isExplicitVersion(targetModel)) && versionMatches(parent, targetModel)) {
                    
                    logModuleChange2(targetModel, sourceVersion);
                    
                    addChange(getGroupId(targetModel), getArtifactId(targetModel), PomHelper.getVersion(targetModel), sourceVersion);
                    targetModel.setVersion(sourceVersion);
                } else {
                    logModuleChange3(targetModel);
                }
            }
        }
    }
    
    private void validateNewVersionSet() throws MojoExecutionException {
        if (isEmpty(newVersion)) {
            if (settings.isInteractiveMode()) {
                try {
                    newVersion = prompter.prompt("Enter the new version to set", getProject().getOriginalModel().getVersion());
                } catch (PrompterException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            } else {
                throw new MojoExecutionException("You must specify the new version, either by using the newVersion "
                        + "property (that is -DnewVersion=... on the command line) or run in interactive mode");
            }
        }
        
        if (isEmpty(newVersion)) {
            throw new MojoExecutionException("You must specify the new version, either by using the newVersion "
                    + "property (that is -DnewVersion=... on the command line) or run in interactive mode");
        }
    }
    
    private boolean matches(CharSequence input, Pattern pattern) {
        return pattern.matcher(input).matches();
    }
    
    private Pattern toRegex(String pattern) {
        return Pattern.compile(convertWildcardsToRegex(fixNullOrEmpty(pattern, "*"), true));
    }

    private static String fixNullOrEmpty(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }
    
    private Set<Entry<String, Model>> filterModules(Map<String, Model> reactor) {
         
        Set<Entry<String, Model>> filteredModules = new LinkedHashSet<>();
        
        for (Entry<String, Model> sourceEntry : reactor.entrySet()) {
            String sourcePath = sourceEntry.getKey();
            Model sourceModel = sourceEntry.getValue();

            String sourceGroupId = getGroupId(sourceModel);
            if (sourceGroupId == null) {
                getLog().warn("Module " + sourcePath + " is missing a groupId.");
                continue;
            }
            
            String sourceArtifactId = getArtifactId(sourceModel);
            if (sourceArtifactId == null) {
                getLog().warn("Module " + sourcePath + " is missing an artifactId.");
                continue;
            }
            
            String sourceVersion = PomHelper.getVersion(sourceModel);
            if (sourceVersion == null) {
                getLog().warn("Module " + sourcePath + " is missing a version.");
                continue;
            }
            
            filteredModules.add(sourceEntry);
        }
        
        return filteredModules;
        
    }
    
    private Set<Entry<String, Model>> getModules(Map<String, Model> reactor, String sourceGroupId, String sourceArtifactId) {
        return processAllModules ? reactor.entrySet() : getChildModels(reactor, sourceGroupId, sourceArtifactId).entrySet();
    }
    
    private boolean versionMatches(Parent parent, Model targetModel) {
        return parent != null && StringUtils.equals(parent.getVersion(), PomHelper.getVersion(targetModel));
    }
    
    private void logModuleChange(Entry<String, Model> sourceModelEntry, Parent parent, String sourceVersion, String sourceGroupId, String sourceArtifactId) {
        getLog().debug("Module: " + sourceModelEntry.getKey());
        
        if (parent != null && sourceVersion.equals(parent.getVersion())) {
            getLog().debug("    parent already is " + versionlessKey(sourceGroupId, sourceArtifactId) + ":" + sourceVersion);
        } else {
            getLog().debug("    parent is " + versionlessKey(sourceGroupId, sourceArtifactId) + ":" + (parent == null ? "" : parent.getVersion()));
            getLog().debug("    will become " + versionlessKey(sourceGroupId, sourceArtifactId) + ":" + sourceVersion);
        }
    }
    
    private void logModuleChange2(Model targetModel, String sourceVersion) {
        getLog().debug("    module is " + versionlessKey(getGroupId(targetModel), getArtifactId(targetModel)) + ":" + PomHelper.getVersion(targetModel));
        getLog().debug("    will become " + versionlessKey(getGroupId(targetModel), getArtifactId(targetModel)) + ":" + sourceVersion);
    }
    
    private void logModuleChange3(Model targetModel) {
        getLog().debug("    module is " + versionlessKey(getGroupId(targetModel), getArtifactId(targetModel)) + ":" + PomHelper.getVersion(targetModel));
    }
    
    private synchronized void addChange(String groupId, String artifactId, String oldVersion, String newVersion) {
        if (!newVersion.equals(oldVersion)) {
            sourceChanges.add(new VersionChange(groupId, artifactId, oldVersion, newVersion));
        }
    }

    private void addFile(Set<File> files, MavenProject project, String relativePath) {
        File moduleDir = new File(project.getBasedir(), relativePath);
        File projectBaseDir = project.getBasedir();

        File moduleProjectFile;

        if (projectBaseDir.equals(moduleDir)) {
            moduleProjectFile = project.getFile();
        } else if (moduleDir.isDirectory()) {
            moduleProjectFile = new File(moduleDir, "pom.xml");
        } else {
            // i don't think this should ever happen... but just in case
            // the module references the file-name
            moduleProjectFile = moduleDir;
        }

        files.add(moduleProjectFile);
    }
    
    private SortedMap<String, Model> getReactor(MavenProject project) throws IOException {
        Map<String, Model> reactorModels = getReactorModels(project, getLog());
        SortedMap<String, Model> reactor = new TreeMap<>(new ReactorDepthComparator(reactorModels));
        reactor.putAll(reactorModels);
        
        return reactor;
    }
    
    private MavenProject getMavenProject() {
        return getLocalRoot(projectBuilder, getProject(), localRepository, null, getLog());
    }

    /**
     * Updates the pom file.
     *
     * @param pom The pom file to update.
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong.
     * @throws org.apache.maven.plugin.MojoFailureException when things go wrong.
     * @throws javax.xml.stream.XMLStreamException when things go wrong.
     */
    @Override
    protected synchronized void update(ModifiedPomXMLEventReader pom) throws MojoExecutionException, MojoFailureException, XMLStreamException {
        ContextualLog log = new DelegatingContextualLog(getLog());
        try {
            Model model = getRawModel(pom);
            log.setContext("Processing " + getGroupId(model) + ":" + getArtifactId(model));

            VersionChangerFactory versionChangerFactory = new VersionChangerFactory();
            versionChangerFactory.setPom(pom);
            versionChangerFactory.setLog(log);
            versionChangerFactory.setModel(model);

            VersionChanger changer = versionChangerFactory.newVersionChanger(processParent, processProject, processDependencies, processPlugins);

            for (VersionChange versionChange : sourceChanges) {
                changer.apply(versionChange);
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        
        log.clearContext();
    }

}
