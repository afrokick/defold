// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob;

import static org.apache.commons.io.FilenameUtils.normalizeNoEndSeparator;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.codec.binary.Base64;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.ExtenderResource;

import com.dynamo.bob.archive.EngineVersion;
import com.dynamo.bob.archive.publisher.AWSPublisher;
import com.dynamo.bob.archive.publisher.DefoldPublisher;
import com.dynamo.bob.archive.publisher.NullPublisher;
import com.dynamo.bob.archive.publisher.Publisher;
import com.dynamo.bob.archive.publisher.PublisherSettings;
import com.dynamo.bob.archive.publisher.ZipPublisher;

import com.dynamo.bob.bundle.BundleHelper;
import com.dynamo.bob.bundle.IBundler;
import com.dynamo.bob.bundle.BundlerParams;
import com.dynamo.bob.fs.ClassLoaderMountPoint;
import com.dynamo.bob.fs.FileSystemWalker;
import com.dynamo.bob.fs.IFileSystem;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.fs.ZipMountPoint;
import com.dynamo.bob.pipeline.ExtenderUtil;
import com.dynamo.bob.pipeline.IShaderCompiler;
import com.dynamo.bob.pipeline.ShaderCompilers;
import com.dynamo.bob.pipeline.TextureGenerator;
import com.dynamo.bob.logging.Logger;
import com.dynamo.bob.util.BobProjectProperties;
import com.dynamo.bob.util.LibraryUtil;
import com.dynamo.bob.util.ReportGenerator;
import com.dynamo.bob.util.HttpUtil;
import com.dynamo.bob.util.TimeProfiler;
import com.dynamo.bob.util.StringUtil;
import com.dynamo.graphics.proto.Graphics.TextureProfiles;

import com.dynamo.bob.cache.ResourceCache;
import com.dynamo.bob.cache.ResourceCacheKey;

/**
 * Project abstraction. Contains input files, builder, tasks, etc
 * @author Christian Murray
 *
 */
public class Project {

    private static Logger logger = Logger.getLogger(Project.class.getName());

    public final static String LIB_DIR = ".internal/lib";
    public final static String CACHE_DIR = ".internal/cache";
    public final static String PLUGINS_DIR = "./build/plugins";
    private static ClassLoaderScanner scanner = null;

    public enum OutputFlags {
        NONE,
        UNCOMPRESSED,
        ENCRYPTED
    }

    private ExecutorService executor = Executors.newCachedThreadPool();
    private ResourceCache resourceCache = new ResourceCache();
    private IFileSystem fileSystem;
    private Map<String, Class<? extends Builder<?>>> extToBuilder = new HashMap<String, Class<? extends Builder<?>>>();
    private Map<String, String> inextToOutext = new HashMap<>();
    private List<Class<? extends Builder<?>>> ignoreTaskAutoCreation = new ArrayList<Class<? extends Builder<?>>>();
    private List<String> inputs = new ArrayList<String>();
    private HashMap<String, EnumSet<OutputFlags>> outputs = new HashMap<String, EnumSet<OutputFlags>>();
    private HashMap<String, Task<?>> tasks;
    private State state;
    private String rootDirectory = ".";
    private String buildDirectory = "build";
    private Map<String, String> options = new HashMap<String, String>();
    private List<URL> libUrls = new ArrayList<URL>();
    private List<String> propertyFiles = new ArrayList<>();
    private List<String> buildServerHeaders = new ArrayList<>();
    private List<String> excluedFilesAndFoldersEntries = new ArrayList<>();
    private List<String> engineBuildDirs = new ArrayList<>();

    private BobProjectProperties projectProperties;
    private Publisher publisher;
    private Map<String, Map<Long, IResource>> hashToResource = new HashMap<>();

    private TextureProfiles textureProfiles;
    private List<Class<? extends IBundler>> bundlerClasses = new ArrayList<>();
    private ClassLoader classLoader = null;

    private List<Class<? extends IShaderCompiler>> shaderCompilerClasses = new ArrayList();

    public Project(IFileSystem fileSystem) {
        this.fileSystem = fileSystem;
        this.fileSystem.setRootDirectory(rootDirectory);
        this.fileSystem.setBuildDirectory(buildDirectory);
        clearProjectProperties();
    }

    public Project(IFileSystem fileSystem, String sourceRootDirectory, String buildDirectory) {
        this.rootDirectory = normalizeNoEndSeparator(new File(sourceRootDirectory).getAbsolutePath(), true);
        this.buildDirectory = normalizeNoEndSeparator(buildDirectory, true);
        this.fileSystem = fileSystem;
        this.fileSystem.setRootDirectory(this.rootDirectory);
        this.fileSystem.setBuildDirectory(this.buildDirectory);
        clearProjectProperties();
    }

    // For the editor
    public Project(ClassLoader loader, IFileSystem fileSystem, String sourceRootDirectory, String buildDirectory) {
        this.classLoader = loader;
        this.rootDirectory = normalizeNoEndSeparator(new File(sourceRootDirectory).getAbsolutePath(), true);
        this.buildDirectory = normalizeNoEndSeparator(buildDirectory, true);
        this.fileSystem = fileSystem;
        this.fileSystem.setRootDirectory(this.rootDirectory);
        this.fileSystem.setBuildDirectory(this.buildDirectory);
        clearProjectProperties();
    }

    public void dispose() {
        this.fileSystem.close();
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    public String getBuildDirectory() {
        return buildDirectory;
    }

    public String getPluginsDirectory() {
        return FilenameUtils.concat(rootDirectory, PLUGINS_DIR);
    }

    public String getBinaryOutputDirectory() {
        return options.getOrDefault("binary-output", FilenameUtils.concat(rootDirectory, "build"));
    }

    public String getLibPath() {
        return FilenameUtils.concat(rootDirectory, LIB_DIR);
    }

    public String getBuildCachePath() {
        return FilenameUtils.concat(rootDirectory, CACHE_DIR);
    }

    public String getSystemEnv(String name) {
        return System.getenv(name);
    }

    public String getSystemProperty(String name) {
        return System.getProperty(name);
    }

    public String getLocalResourceCacheDirectory() {
        return option("resource-cache-local", null);
    }

    public String getRemoteResourceCacheDirectory() {
        return option("resource-cache-remote", null);
    }

    public String getRemoteResourceCacheUser() {
        return option("resource-cache-remote-user", getSystemEnv("DM_BOB_RESOURCE_CACHE_REMOTE_USER"));
    }

    public String getRemoteResourceCachePass() {
        return option("resource-cache-remote-pass", getSystemEnv("DM_BOB_RESOURCE_CACHE_REMOTE_PASS"));
    }

    public int getMaxCpuThreads() {
        String maxThreadsOpt = option("max-cpu-threads", null);
        if (maxThreadsOpt == null) {
            return getDefaultMaxCpuThreads();
        }
        return Integer.parseInt(maxThreadsOpt);
    }

    public BobProjectProperties getProjectProperties() {
        return projectProperties;
    }

    /**
     * Convert an absolute path to a path relative to the project root
     * @param path The path to relativize
     * @return Relative path
     */
    public String getPathRelativeToRootDirectory(String path) {
        return Path.of(rootDirectory).relativize(Path.of(path)).toString();
    }

    public void setPublisher(Publisher publisher) {
        this.publisher = publisher;
    }

    public Publisher getPublisher() {
        return this.publisher;
    }

    private ClassLoaderScanner createClassLoaderScanner() throws IOException {
        scanner = new ClassLoaderScanner(getClassLoader());
        return scanner;
    }

    public ClassLoader getClassLoader() {
        if (classLoader == null)
            classLoader = this.getClass().getClassLoader();
        return classLoader;
    }

    public static IClassScanner getClassLoaderScanner() {
        return scanner;
    }

    public static Class<?> getClass(String className) {
        try {
            return Class.forName(className, true, scanner.getClassLoader());
        } catch(ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Scan package for builder classes
     * @param scanner class scanner
     * @param pkg package name to be scanned
     */
    public void scan(IClassScanner scanner, String pkg) {
        Set<String> classNames = scanner.scan(pkg);
        doScan(scanner, classNames);
    }

    private static String getManifestInfo(String attribute) {
        Enumeration resEnum;
        try {
            resEnum = Thread.currentThread().getContextClassLoader().getResources(JarFile.MANIFEST_NAME);
            while (resEnum.hasMoreElements()) {
                try {
                    URL url = (URL)resEnum.nextElement();
                    InputStream is = url.openStream();
                    if (is != null) {
                        Manifest manifest = new Manifest(is);
                        Attributes mainAttribs = manifest.getMainAttributes();
                        String value = mainAttribs.getValue(attribute);
                        if(value != null) {
                            return value;
                        }
                    }
                }
                catch (Exception e) {
                    // Silently ignore wrong manifests on classpath?
                }
            }
        } catch (IOException e1) {
            // Silently ignore wrong manifests on classpath?
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void doScan(IClassScanner scanner, Set<String> classNames) {
        boolean is_bob_light = getManifestInfo("is-bob-light") != null;

        for (String className : classNames) {
            // Ignore TexcLibrary to avoid it being loaded and initialized
            // We're also skipping some of the bundler classes, since we're only building content,
            // not doing bundling when using bob-light
            boolean skip = className.startsWith("com.dynamo.bob.TexcLibrary") ||
                    (is_bob_light && className.startsWith("com.dynamo.bob.archive.publisher.AWSPublisher")) ||
                    (is_bob_light && className.startsWith("com.dynamo.bob.pipeline.ExtenderUtil")) ||
                    (is_bob_light && className.startsWith("com.dynamo.bob.bundle.BundleHelper"));
            if (!skip) {
                try {
                    Class<?> klass = Class.forName(className, true, scanner.getClassLoader());
                    BuilderParams builderParams = klass.getAnnotation(BuilderParams.class);
                    if (builderParams != null) {
                        for (String inExt : builderParams.inExts()) {
                            extToBuilder.put(inExt, (Class<? extends Builder<?>>) klass);
                            inextToOutext.put(inExt, builderParams.outExt());
                            if (builderParams.ignoreTaskAutoCreation()) {
                                ignoreTaskAutoCreation.add((Class<? extends Builder<?>>) klass);
                            }
                        }

                        ProtoParams protoParams = klass.getAnnotation(ProtoParams.class);
                        if (protoParams != null) {
                            ProtoBuilder.addMessageClass(builderParams.outExt(), protoParams.messageClass());

                            for (String ext : builderParams.inExts()) {
                                Class<?> inputClass = protoParams.srcClass();
                                if (inputClass != null) {
                                    ProtoBuilder.addMessageClass(ext, protoParams.srcClass());
                                }
                            }
                        }
                    }

                    if (IBundler.class.isAssignableFrom(klass))
                    {
                        if (!klass.equals(IBundler.class)) {
                            bundlerClasses.add( (Class<? extends IBundler>) klass);
                        }
                    }

                    if (IShaderCompiler.class.isAssignableFrom(klass))
                    {
                        if (!klass.equals(IShaderCompiler.class)) {
                            shaderCompilerClasses.add((Class<? extends IShaderCompiler>) klass);
                        }
                    }

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    static String[][] extensionMapping = new String[][] {
        {".camera", ".camerac"},
        {".buffer", ".bufferc"},
        {".mesh", ".meshc"},
        {".collectionproxy", ".collectionproxyc"},
        {".collisionobject", ".collisionobjectc"},
        {".particlefx", ".particlefxc"},
        {".gui", ".guic"},
        {".model", ".modelc"},
        {".script", ".scriptc"},
        {".sound", ".soundc"},
        {".wav", ".soundc"},
        {".ogg", ".soundc"},
        {".collectionfactory", ".collectionfactoryc"},
        {".factory", ".factoryc"},
        {".light", ".lightc"},
        {".label", ".labelc"},
        {".sprite", ".spritec"},
        {".tilegrid", ".tilemapc"},
        {".tilemap", ".tilemapc"},
    };

    public String replaceExt(String inExt) {
        for (int i = 0; i < extensionMapping.length; i++) {
            if (extensionMapping[i][0].equals(inExt))
            {
                return extensionMapping[i][1];
            }
        }
        String outExt = inextToOutext.get(inExt); // Get the output ext, or use the inExt as default
        if (outExt != null)
            return outExt;
        return inExt;
    }

    private Class<? extends Builder<?>> getBuilderFromExtension(String input) {
        String ext = "." + FilenameUtils.getExtension(input);
        Class<? extends Builder<?>> builderClass = extToBuilder.get(ext);
        return builderClass;
    }

    /**
     * Returns builder class for resource
     * @param input input resource
     * @return class
     */
    public Class<? extends Builder<?>> getBuilderFromExtension(IResource input) {
        return getBuilderFromExtension(input.getPath());
    }

    /**
     * Create task from resource path with explicit builder.
     * @param inputPath input resource path
     * @param builderClass class to build resource with
     * @return task
     * @throws CompileExceptionError
     */
    public Task<?> createTask(String inputPath, Class<? extends Builder<?>> builderClass) throws CompileExceptionError {
        IResource inputResource = fileSystem.get(inputPath);
        return createTask(inputResource, builderClass);
    }

    /**
     * Create task from resource. Typically called from builder
     * that create intermediate output/input-files
     * @param input input resource
     * @return task
     * @throws CompileExceptionError
     */
    public Task<?> createTask(IResource inputResource) throws CompileExceptionError {
        Class<? extends Builder<?>> builderClass = getBuilderFromExtension(inputResource);
        if (builderClass == null) {
            logWarning("No builder for '%s' found", inputResource);
            return null;
        }

        return createTask(inputResource, builderClass);
    }

    /**
     * Create task from resource with explicit builder.
     * Make sure that task is unique.
     * @param input input resource
     * @param builderClass class to build resource with
     * @return task
     * @throws CompileExceptionError
     */
    public Task<?> createTask(IResource inputResource, Class<? extends Builder<?>> builderClass) throws CompileExceptionError {
        // It's possible to build the same resource using different builders
        String key = inputResource.getPath()+" "+builderClass;
        Task<?> task = tasks.get(key);
        if (task != null) {
            return task;
        }
        TimeProfiler.start();
        TimeProfiler.addData("type", "createTask");
        Builder<?> builder;
        try {
            builder = builderClass.newInstance();
            builder.setProject(this);
            task = builder.create(inputResource);
            if (task != null) {
                TimeProfiler.addData("output", StringUtil.truncate(task.getOutputsString(), 1000));
                TimeProfiler.addData("name", task.getName());
                tasks.put(key, task);
            }
            return task;
        } catch (CompileExceptionError e) {
            // Just pass CompileExceptionError on unmodified
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            TimeProfiler.stop();
        }
    }

    private List<String> sortInputs() {
        ArrayList<String> sortedInputs = new ArrayList<String>(inputs);
        Collections.sort(sortedInputs, new Comparator<String>() {

            @Override
            public int compare(String i1, String i2) {
                Class<? extends Builder<?>> b1 = getBuilderFromExtension(i1);
                Class<? extends Builder<?>> b2 = getBuilderFromExtension(i2);

                BuilderParams p1 = b1.getAnnotation(BuilderParams.class);
                BuilderParams p2 = b2.getAnnotation(BuilderParams.class);

                return p1.createOrder() - p2.createOrder();
            }
        });
        return sortedInputs;
    }

    /*
        The same logic implemented in the Editor.
        If you change something here, make sure you change it in resource.clj
        (defignore-pred).
    */
    private void loadIgnoredFilesAndFolders() throws CompileExceptionError {
        String excludeFoldersStr = this.option("exclude-build-folder", "");
        List<String> excludeFolders = BundleHelper.createArrayFromString(excludeFoldersStr);
        excluedFilesAndFoldersEntries.addAll(excludeFolders);
        List<String> defIgnoreEntries = new ArrayList<String>();
        final File defIgnoreFile = new File(getRootDirectory(), ".defignore");
        if (defIgnoreFile.isFile()) {
            try {
                defIgnoreEntries = FileUtils.readLines(defIgnoreFile, "UTF-8")
                        .stream()
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }
            catch(IOException e) {
                throw new CompileExceptionError("Unable to read .defignore", e);
            }
        }
        excluedFilesAndFoldersEntries.addAll(defIgnoreEntries);
        // remove initial "/" from excluded folder names
        for(int i = 0; i < excluedFilesAndFoldersEntries.size(); i++) {
            String entry = excluedFilesAndFoldersEntries.get(i);
            if (entry.startsWith("/")) {
                excluedFilesAndFoldersEntries.set(i, entry.substring(1));
            }
        }
    }

    private void createTasks() throws CompileExceptionError {
        tasks = new HashMap<String, Task<?>>();
        List<String> sortedInputs = sortInputs(); // from findSources

        // To currently know the output resources, we need to parse the main.collectionc
        // We would need to alter that to get a correct behavior (e.g. using GameProjectBuilder.findResources(this, rootNode))

        // create tasks for inputs that are not excluded
        for (String input : sortedInputs) {
            boolean skipped = false;
            // Ignore for resources.
            // Check comment for loadIgnoredFilesAndFolders()
            for (String excludeEntry : excluedFilesAndFoldersEntries) {
                if (input.startsWith(excludeEntry)) {
                    skipped = true;
                    break;
                }
            }
            if (!skipped) {
                Class<? extends Builder<?>> builderClass = getBuilderFromExtension(input);
                if (!ignoreTaskAutoCreation.contains(builderClass)) {
                    Task<?> task = createTask(input, builderClass);
                }
            }
        }
    }

    private void logWarning(String fmt, Object... args) {
        System.err.println(String.format(fmt, args));
    }
    private void logInfo(String fmt, Object... args) {
        System.out.println(String.format(fmt, args));
    }

    public void createPublisher(boolean shouldPublish) throws CompileExceptionError {
        try {
            String settingsPath = this.getProjectProperties().getStringValue("liveupdate", "settings", "/liveupdate.settings"); // if no value set use old hardcoded path (backward compatability)
            IResource publisherSettings = this.fileSystem.get(settingsPath);
            if (!publisherSettings.exists()) {
                if (shouldPublish) {
                    IResource gameProject = this.fileSystem.get("/game.project");
                    throw new CompileExceptionError(gameProject, 0, "There is no liveupdate.settings file specified in game.project or the file is missing from disk.");
                } else {
                    this.publisher = new NullPublisher(new PublisherSettings());
                }
            } else {
                ByteArrayInputStream is = new ByteArrayInputStream(publisherSettings.getContent());
                PublisherSettings settings = PublisherSettings.load(is);
                if (shouldPublish) {
                    if (PublisherSettings.PublishMode.Amazon.equals(settings.getMode())) {
                        this.publisher = new AWSPublisher(settings);
                    } else if (PublisherSettings.PublishMode.Defold.equals(settings.getMode())) {
                        this.publisher = new DefoldPublisher(settings);
                    } else if (PublisherSettings.PublishMode.Zip.equals(settings.getMode())) {
                        this.publisher = new ZipPublisher(getRootDirectory(), settings);
                    } else {
                        throw new CompileExceptionError("The publisher specified is not supported", null);
                    }
                } else {
                    this.publisher = new NullPublisher(settings);
                }
            }
        } catch (CompileExceptionError e) {
            throw e;
        } catch (Throwable e) {
            throw new CompileExceptionError(null, 0, e.getMessage(), e);
        }
    }

    public void clearProjectProperties() {
        projectProperties = new BobProjectProperties();
    }

    private static void loadPropertiesData(BobProjectProperties properties, byte[] data, Boolean isMeta, String filepath) throws IOException {
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        try {
            properties.load(is, isMeta);
        } catch(ParseException e) {
            throw new IOException("Could not parse: " + filepath);
        }
    }

    private static void loadPropertiesFile(BobProjectProperties properties, String filepath, Boolean isMeta) throws IOException {
        Path pathHandle = Paths.get(filepath);
        if (!Files.exists(pathHandle) || !pathHandle.toFile().isFile())
            throw new IOException(filepath + " is not a file");
        loadPropertiesData(properties, Files.readAllBytes(pathHandle), isMeta, filepath);
    }

    // Loads the properties from a game project settings file
    // Also adds any properties specified with the "--settings" flag
    public static BobProjectProperties loadProperties(Project project, IResource projectFile, List<String> settingsFiles) throws IOException {
        if (!projectFile.exists()) {
            throw new IOException(String.format("Project file not found: %s", projectFile.getAbsPath()));
        }

        BobProjectProperties properties = new BobProjectProperties();
        try {
            // load meta.properties embeded in bob.jar
            properties.loadDefaultMetaFile();
            // load property files from extensions
            List<String> extensionFolders = ExtenderUtil.getExtensionFolders(project);
            if (!extensionFolders.isEmpty()) {
                for (String extension : extensionFolders) {
                    IResource resource = project.getResource(extension + "/" + BobProjectProperties.PROPERTIES_EXTENSION_FILE);
                    if (resource.exists()) {
                        // resources from extensions in ZIP files can't be read as files, but getContent() works fine
                        loadPropertiesData(properties, resource.getContent(), true, resource.getPath());
                    }
                }
            }
            // load property file from the project
            IResource gameProjectProperties = projectFile.getResource(BobProjectProperties.PROPERTIES_PROJECT_FILE);
            if (gameProjectProperties.exists()) {
               loadPropertiesFile(properties, gameProjectProperties.getAbsPath(), true);
            }
            // load game.project file
            Project.loadPropertiesFile(properties, projectFile.getAbsPath(), false);
        } catch(ParseException e) {
            throw new IOException("Could not parse: " + projectFile.getAbsPath());
        }
        // load settings file specified in `--settings` for bob.jar
        for (String filepath : settingsFiles) {
            Project.loadPropertiesFile(properties, filepath, false);
        }

        return properties;
    }

    public void loadProjectFile() throws IOException {
        IResource gameProject = getGameProjectResource();
        if (gameProject.exists()) {
            projectProperties = Project.loadProperties(this, gameProject, this.getPropertyFiles());
        }
    }

    public void addBuildServerHeader(String header) {
        buildServerHeaders.add(header);
    }

    public void addPropertyFile(String filepath) {
        propertyFiles.add(filepath);
    }

    public void addEngineBuildDir(String dirpath) {
        engineBuildDirs.add(dirpath);
    }

    // Returns the command line specified property files
    public List<String> getPropertyFiles() {
        return propertyFiles;
    }

    public List<IResource> getPropertyFilesAsResources() {
        List<IResource> resources = new ArrayList<>();
        for (String propertyFile : propertyFiles) {
            resources.add(fileSystem.get(propertyFile));
        }
        return resources;
    }

    /**
     * Build the project
     * @param monitor
     * @return list of {@link TaskResult}. Only executed nodes are part of the list.
     * @throws IOException
     * @throws CompileExceptionError
     */
    public List<TaskResult> build(IProgress monitor, String... commands) throws IOException, CompileExceptionError, MultipleCompileException {
        try {
            if (this.hasOption("build-report-html")) {
                List<File> reportFiles = new ArrayList<>();
                reportFiles.add(new File(this.option("build-report-html", "report.html")));
                TimeProfiler.init(reportFiles, true);
            }
            loadProjectFile();
            String title = projectProperties.getStringValue("project", "title");
            if (title != null && title.isEmpty()) {
                throw new Exception("`project.title` in `game.project` must be non-empty.");
            }
            return doBuild(monitor, commands);
        } catch (CompileExceptionError e) {

            String s = Bob.logExceptionToString(MultipleCompileException.Info.SEVERITY_ERROR, e.getResource(), e.getLineNumber(), e.toString());
            if (s.contains("NullPointerException")) {
                e.printStackTrace(System.err); // E.g. when we happen to do something bad when handling exceptions
            }

            System.err.println(s);
            // Pass on unmodified
            throw e;
        } catch (MultipleCompileException e) {
            // Pass on unmodified
            throw e;
        } catch (Throwable e) {
            throw new CompileExceptionError(null, 0, e.getMessage(), e);
        } finally {
            TimeProfiler.createReport(true);
        }
    }

    /**
     * Mounts all the mount point associated with the project.
     * @param resourceScanner scanner to use for finding resources in the java class path
     * @throws IOException
     * @throws CompileExceptionError
     */
    public void mount(IResourceScanner resourceScanner) throws IOException, CompileExceptionError {
        this.fileSystem.clearMountPoints();
        this.fileSystem.addMountPoint(new ClassLoaderMountPoint(this.fileSystem, "builtins/**", resourceScanner));
        Map<String, File> libFiles = LibraryUtil.collectLibraryFiles(getLibPath(), this.libUrls);
        if (libFiles == null) {
            throw new CompileExceptionError("Missing libraries folder. You need to run the 'resolve' command first!");
        }
        boolean missingFiles = false;

        for (String url : libFiles.keySet() ) {
            File file = libFiles.get(url);

            if (file != null && file.exists()) {
                this.fileSystem.addMountPoint(new ZipMountPoint(this.fileSystem, file.getAbsolutePath()));
            } else {
                missingFiles = true;
            }
        }
        if (missingFiles) {
            logWarning("Some libraries could not be found locally, use the resolve command to fetch them.");
        }
    }

    /**
     * Match resource name by resource list. Comparison is case-insensitive and stripped by resource path and extension.
     * @param resource resource
     * @param resourceList list of resources to match to resource
     * @return matching resource in resource list, or null.
     */
    private IResource getMatchingResourceByName(IResource resource, List<IResource> resourceList) {
        String resourceName = FilenameUtils.removeExtension(FilenameUtils.getBaseName(resource.toString())).toLowerCase();
        for (IResource input : resourceList) {
            if (FilenameUtils.removeExtension(FilenameUtils.getBaseName(input.toString())).toLowerCase().equals(resourceName)) {
                return input;
            }
        }
        return null;
    }

    /**
     * Get the conflicting output resource given two list of input resources.
     * If no direct resource can be determined, try finding a matching resource by name in either input resource list.
     * Finally, failing that, resort to the first resource in primary input resource list.
     * @param output output resource
     * @param inputs1 primary resource list of input resource filenames
     * @param inputs2 secondary resource list of input resource filenames
     * @return conflicting resource
     */
    private IResource getConflictingResource(IResource output, List<IResource> inputs1, List<IResource> inputs2) {
        if (inputs1.size() == 1) {
            return inputs1.get(0);
        } else if (inputs2.size() == 1) {
            return inputs2.get(0);
        }
        IResource resource = getMatchingResourceByName(output, inputs1);
        if(resource != null){
            return resource;
        }
        resource = getMatchingResourceByName(output, inputs2);
        if(resource != null){
            return resource;
        }
        return inputs1.get(0);
    }

    /**
     * Validate there are no conflicting input resources for any given output
     * resource. If any output resource exists more than once in the list of
     * build output tasks, there is a conflict.
     * @throws CompileExceptionError
     */
    private void validateBuildResourceMapping() throws CompileExceptionError {
        Map<String, List<IResource>> build_map = new HashMap<String, List<IResource>>();
        for (Task<?> t : this.getTasks()) {
            List<IResource> inputs = t.getInputs();
            List<IResource> outputs = t.getOutputs();
            for (IResource output : outputs) {
                String outStr = output.toString();
                boolean isGenerated = outStr.contains("_generated_");
                if (build_map.containsKey(outStr) && !isGenerated) {
                    List<IResource> inputsStored = build_map.get(outStr);
                    String errMsg = "Conflicting output resource '" + outStr + "‘ generated by the following input files: " + inputs.toString() + " <-> " + inputsStored.toString();
                    IResource errRes = getConflictingResource(output, inputs, inputsStored);
                    throw new CompileExceptionError(errRes, 0, errMsg);
                }
                build_map.put(outStr, inputs);
            }
        }
    }

    private Class<? extends IBundler> getBundlerClass(Platform platform) {
        for (Class<? extends IBundler> klass : bundlerClasses) {
            BundlerParams bundlerParams = klass.getAnnotation(BundlerParams.class);
            if (bundlerParams == null) {
                logWarning("Bundler class '%s' has no BundlerParams", klass.getName());
                continue;
            }
            for (Platform supportedPlatform : bundlerParams.platforms()) {
                if (supportedPlatform == platform)
                    return klass;
            }
        }
        return null;
    }

    public IBundler createBundler(Platform platform) throws CompileExceptionError {
        Class<? extends IBundler> bundlerClass = getBundlerClass(platform);
        if (bundlerClass == null) {
            throw new CompileExceptionError(null, -1, String.format("No bundler registered for platform %s", platform.getPair()));
        }

        try {
            return bundlerClass.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void bundle(IProgress monitor) throws IOException, CompileExceptionError {
        IProgress m = monitor.subProgress(1);
        m.beginTask("Bundling...", 1);

        Platform platform = getPlatform();
        IBundler bundler = createBundler(platform);

        String bundleOutput = option("bundle-output", null);
        File bundleDir = null;
        if (bundleOutput != null) {
            bundleDir = new File(bundleOutput);
        } else {
            bundleDir = new File(getRootDirectory(), getBuildDirectory());
        }
        BundleHelper.throwIfCanceled(monitor);
        bundleDir.mkdirs();
        bundler.bundleApplication(this, platform, bundleDir, monitor);
        m.worked(1);
        m.done();
    }

    private Class<? extends IShaderCompiler> getShaderCompilerClass(Platform platform) {
        for (Class<? extends IShaderCompiler> klass : shaderCompilerClasses) {
            BundlerParams bundlerParams = klass.getAnnotation(BundlerParams.class);
            if (bundlerParams == null) {
                continue;
            }
            for (Platform supportedPlatform : bundlerParams.platforms()) {
                if (supportedPlatform == platform)
                    return klass;
            }
        }
        return null;
    }

    public IShaderCompiler getShaderCompiler(Platform platform) throws CompileExceptionError {
        // Look for a shader compiler plugin for this platform
        Class<? extends IShaderCompiler> shaderCompilerClass = getShaderCompilerClass(platform);
        if (shaderCompilerClass != null) {
            try {
                return shaderCompilerClass.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // If not found, try to get a built-in shader compiler for this platform
        IShaderCompiler commonShaderCompiler = ShaderCompilers.getCommonShaderCompiler(platform);
        if (commonShaderCompiler != null) {
            return commonShaderCompiler;
        }

        throw new CompileExceptionError(null, -1, String.format("No shader compiler registered for platform %s", platform.getPair()));
    }

    private boolean anyFailing(Collection<TaskResult> results) {
        for (TaskResult taskResult : results) {
            if (!taskResult.isOk()) {
                return true;
            }
        }

        return false;
    }

    public List<Platform> getArchitectures() throws CompileExceptionError {
        Platform p = getPlatform();
        return Platform.getArchitecturesFromString(option("architectures", ""), p);
    }

    public Platform getPlatform() throws CompileExceptionError {
        String pair = option("platform", null);
        Platform p = Platform.getHostPlatform();
        if (pair != null) {
            // backwards compatibility.
            // TODO: remove in some future update
            if (pair.equals("x86_64-darwin"))
            {
                String deprecatedPair = pair;
                pair = Platform.X86_64MacOS.getPair();
                System.out.printf("Platform name %s is deprecated. Please use '%s' instead\n", deprecatedPair, pair);
            }
            else if (pair.equals("arm64-darwin"))
            {
                String deprecatedPair = pair;
                pair = Platform.Arm64Ios.getPair();
                System.out.printf("Platform name %s is deprecated. Please use '%s' instead\n", deprecatedPair, pair);
            }
            p = Platform.get(pair);
        }

        if (p == null) {
            throw new CompileExceptionError(null, -1, String.format("Platform %s not supported", pair));
        }

        return p;
    }

    public String[] getPlatformStrings() throws CompileExceptionError {
        Platform p = getPlatform();
        PlatformArchitectures platformArchs = p.getArchitectures();
        String[] platformStrings;
        if (p == Platform.Arm64Ios || p == Platform.JsWeb || p == Platform.WasmWeb || p == Platform.Armv7Android || p == Platform.Arm64Android)
        {
            // Here we'll get a list of all associated architectures (armv7, arm64) and build them at the same time
            platformStrings = platformArchs.getArchitectures();
        }
        else
        {
            platformStrings = new String[1];
            platformStrings[0] = p.getPair();
        }
        return platformStrings;
    }

    public void buildEnginePlatform(IProgress monitor, File buildDir, File cacheDir, Map<String,String> appmanifestOptions, Platform platform) throws IOException, CompileExceptionError, MultipleCompileException {

        // Get SHA1 and create log file
        final String sdkVersion = this.option("defoldsdk", EngineVersion.sha1);

        final String variant = appmanifestOptions.get("baseVariant");

        List<String> defaultNames = platform.formatBinaryName("dmengine");
        List<File> exes = new ArrayList<File>();
        for (String name : defaultNames) {
            File exe = new File(FilenameUtils.concat(buildDir.getAbsolutePath(), name));
            exes.add(exe);
        }

        BundleHelper helper = new BundleHelper(this, platform, buildDir, variant);

        List<ExtenderResource> allSource = ExtenderUtil.getExtensionSources(this, platform, appmanifestOptions);

        allSource.addAll(helper.writeExtensionResources(platform));

        // Replace the unresolved manifests with the resolved ones
        List<ExtenderResource> resolvedManifests = helper.writeManifestFiles(platform, helper.getTargetManifestDir(platform));
        for (ExtenderResource manifest : resolvedManifests) {
            ExtenderResource src = null;
            for (ExtenderResource s : allSource) {
                if (s.getPath().equals(manifest.getPath())) {
                    src = s;
                    break;
                }
            }
            if (src != null) {
                allSource.remove(src);
            }
            allSource.add(manifest);
        }

        boolean debugUploadZip = this.hasOption("debug-ne-upload");

        if (debugUploadZip) {
            File debugZip = new File(buildDir.getParent(), "upload.zip");
            ZipOutputStream zipOut = null;
            try {
                zipOut = new ZipOutputStream(new FileOutputStream(debugZip));
                ExtenderUtil.writeResourcesToZip(allSource, zipOut);
                System.out.printf("Wrote debug upload zip file to: %s", debugZip);
            } catch (Exception e) {
                throw new CompileExceptionError(String.format("Failed to write debug zip file to %s", debugZip), e);
            } finally {
                zipOut.close();
            }
        }

        // Located in the same place as the log file in the unpacked successful build
        File logFile = new File(buildDir, "log.txt");
        String serverURL = this.option("build-server", "https://build.defold.com");

        try {
            ExtenderClient extender = new ExtenderClient(serverURL, cacheDir);
            extender.setHeaders(buildServerHeaders);

            String buildPlatform = platform.getExtenderPair();
            File zip = BundleHelper.buildEngineRemote(this, extender, buildPlatform, sdkVersion, allSource, logFile);

            cleanEngine(platform, buildDir);

            BundleHelper.unzip(new FileInputStream(zip), buildDir.toPath());
        } catch (ConnectException e) {
            throw new CompileExceptionError(String.format("Failed to connect to %s: %s", serverURL, e.getMessage()), e);
        } catch (ExtenderClientException e) {
            throw new CompileExceptionError(String.format("Failed to build engine: %s", e.getMessage()), e);
        }
    }

    public void buildLibraryPlatform(IProgress monitor, File buildDir, File cacheDir, Map<String,String> appmanifestOptions, Platform platform) throws IOException, CompileExceptionError, MultipleCompileException {

        // Get SHA1 and create log file
        final String sdkVersion = this.option("defoldsdk", EngineVersion.sha1);

        final String libraryName = this.option("ne-output-name", "default");

        final String variant = appmanifestOptions.get("baseVariant");

        BundleHelper helper = new BundleHelper(this, platform, buildDir, variant);

        // Located in the same place as the log file in the unpacked successful build
        File logFile = new File(buildDir, "log.txt");
        String serverURL = this.option("build-server", "https://build.defold.com");

        //platforms /armv7-ios /context /flags
        Map<String, Object> compilerOptions = new HashMap<>();
        String DEFINES = getSystemEnv("DEFINES");
        if (DEFINES != null) {
            List<String> values = Arrays.asList(DEFINES.split(" "));
            compilerOptions.put("defines", values);
        }
        String CXXFLAGS = getSystemEnv("CXXFLAGS");
        if (CXXFLAGS != null) {
            List<String> values = Arrays.asList(CXXFLAGS.split(" "));
            compilerOptions.put("flags", values);
        }
        String INCLUDES = getSystemEnv("INCLUDES");
        if (INCLUDES != null) {
            List<String> values = Arrays.asList(INCLUDES.split(" "));
            compilerOptions.put("includes", values);
        }

        for (String path : engineBuildDirs) {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                throw new IOException(String.format("'%s' is not a directory!", path));
            }
        }

        List<ExtenderResource> allSource = ExtenderUtil.getLibrarySources(this, platform, appmanifestOptions, compilerOptions, libraryName, engineBuildDirs);

        boolean debugUploadZip = this.hasOption("debug-ne-upload");
        if (debugUploadZip) {
            File debugZip = new File(buildDir.getParent(), "upload.zip");
            ZipOutputStream zipOut = null;
            try {
                zipOut = new ZipOutputStream(new FileOutputStream(debugZip));
                ExtenderUtil.writeResourcesToZip(allSource, zipOut);
                System.out.printf("Wrote debug upload zip file to: %s", debugZip);
            } catch (Exception e) {
                throw new CompileExceptionError(String.format("Failed to write debug zip file to %s", debugZip), e);
            } finally {
                zipOut.close();
            }
        }

        try {
            ExtenderClient extender = new ExtenderClient(serverURL, cacheDir);
            extender.setHeaders(buildServerHeaders);

            String buildPlatform = platform.getExtenderPair();
            File zip = BundleHelper.buildEngineRemote(this, extender, buildPlatform, sdkVersion, allSource, logFile);

            BundleHelper.unzip(new FileInputStream(zip), buildDir.toPath());
        } catch (ConnectException e) {
            throw new CompileExceptionError(String.format("Failed to connect to %s: %s", serverURL, e.getMessage()), e);
        } catch (ExtenderClientException e) {
            throw new CompileExceptionError(String.format("Failed to build engine: %s", e.getMessage()), e);
        }
    }

    public void buildEngine(IProgress monitor, String[] architectures, Map<String,String> appmanifestOptions) throws IOException, CompileExceptionError, MultipleCompileException {
        // Store the build one level above the content build since that folder gets removed during a distclean
        String internalDir = FilenameUtils.concat(rootDirectory, ".internal");
        File cacheDir = new File(FilenameUtils.concat(internalDir, "cache"));
        cacheDir.mkdirs();

        IProgress m = monitor.subProgress(architectures.length);
        m.beginTask("Building engine...", 0);

        // Build all skews of platform
        String outputDir = getBinaryOutputDirectory();
        for (int i = 0; i < architectures.length; ++i) {
            Platform platform = Platform.get(architectures[i]);

            String buildPlatform = platform.getExtenderPair();
            File buildDir = new File(FilenameUtils.concat(outputDir, buildPlatform));
            buildDir.mkdirs();


            boolean buildLibrary = shouldBuildArtifact("library");
            if (buildLibrary) {
                buildLibraryPlatform(monitor, buildDir, cacheDir, appmanifestOptions, platform);
            }
            else {
                buildEnginePlatform(monitor, buildDir, cacheDir, appmanifestOptions, platform);
            }

            m.worked(1);
        }

        m.done();
    }

    private static boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    private void cleanEngine(Platform platform, File dir) throws IOException, CompileExceptionError {
        if (!dir.exists()) {
            return;
        }

        // Check for at least a previous built engine before triggering a recursive delete
        List<String> defaultNames = platform.formatBinaryName("dmengine");
        for (String defaultName : defaultNames) {
            File exe = new File(FilenameUtils.concat(dir.getAbsolutePath(), defaultName));
            if (exe.exists()) {
                Project.deleteDirectory(dir);
                break;
            }
        }
    }

    private void cleanEngines(IProgress monitor, String[] platformStrings) throws IOException, CompileExceptionError {
        IProgress m = monitor.subProgress(platformStrings.length);
        m.beginTask("Cleaning engine...", 0);

        String outputDir = getBinaryOutputDirectory();
        for (int i = 0; i < platformStrings.length; ++i) {
            Platform platform = Platform.get(platformStrings[i]);
            cleanEngine(platform, new File(outputDir, platform.getExtenderPair()));
            m.worked(1);
        }

        m.done();
    }

    private void downloadSymbols(IProgress progress) throws IOException, CompileExceptionError {
        String archs = this.option("architectures", null);
        String[] platforms;
        if (archs != null) {
            platforms = archs.split(",");
        }
        else {
            platforms = getPlatformStrings();
        }

        progress.beginTask(String.format("Downloading %s symbols...", platforms.length), platforms.length);

        final String variant = this.option("variant", Bob.VARIANT_RELEASE);
        String variantSuffix = "";
        switch(variant) {
            case Bob.VARIANT_RELEASE:
                variantSuffix = "_release";
                break;
            case Bob.VARIANT_HEADLESS:
                variantSuffix = "_headless";
                break;
        }

        for(String platform : platforms) {
            String symbolsFilename = null;
            Platform p = Platform.get(platform);
            switch(platform) {
                case "arm64-ios":
                case "x86_64-ios":
                case "x86_64-macos":
                case "arm64-macos":
                    symbolsFilename = String.format("dmengine%s.dSYM.zip", variantSuffix);
                    break;
                case "js-web":
                    symbolsFilename = String.format("dmengine%s.js.symbols", variantSuffix);
                    break;
                case "win32":
                case "x86_64-win32":
                    symbolsFilename = String.format("dmengine%s.pdb", variantSuffix);
                    break;
            }

            if (symbolsFilename != null) {
                try {
                    URL url = new URL(String.format(Bob.ARTIFACTS_URL + "%s/engine/%s/%s", EngineVersion.sha1, platform, symbolsFilename));
                    File targetFolder = new File(getBinaryOutputDirectory(), p.getExtenderPair());
                    File file = new File(targetFolder, symbolsFilename);
                    HttpUtil http = new HttpUtil();
                    http.downloadToFile(url, file);
                    if (symbolsFilename.endsWith(".zip")){
                        BundleHelper.unzip(new FileInputStream(file), targetFolder.toPath());
                    }
                }
                catch (Exception e) {
                    throw new CompileExceptionError(e);
                }
            }
            progress.worked(1);
        }
    }

    static void addToPath(String variable, String path) {
        String newPath = null;

        // Check if variable is set externally.
        if (System.getProperty(variable) != null) {
            newPath = System.getProperty(variable);
        }

        if (newPath == null) {
            // Set path where the shared library is found.
            newPath = path;
        } else {
            // Append path where the shared library is found.
            newPath += File.pathSeparator + path;
        }

        // Set the concatenated jna.library path
        System.setProperty(variable, newPath);
        logger.info("Set %s to '%s'", variable, newPath);
    }

    private void registerPipelinePlugins() throws CompileExceptionError {
        // Find the plugins and register them now, before we're building the content
        BundleHelper.extractPipelinePlugins(this, getPluginsDirectory());
        List<File> plugins = BundleHelper.getPipelinePlugins(this, getPluginsDirectory());
        if (!plugins.isEmpty()) {
            logger.info("\nFound plugins:");
        }

        String hostPlatform = Platform.getHostPlatform().getExtenderPair();

        for (File plugin : plugins) {
            scanner.addUrl(plugin);

            File pluginsDir = plugin.getParentFile().getParentFile(); // The <extension>/plugins dir
            File libDir = new File(pluginsDir, "lib");
            File platformDir = new File(libDir, hostPlatform);

            if (platformDir.exists()) {
                addToPath("jna.library.path", platformDir.getAbsolutePath());
                addToPath("java.library.path", platformDir.getAbsolutePath());
            }

            String relativePath = new File(rootDirectory).toURI().relativize(plugin.toURI()).getPath();
            logger.info("  %s", relativePath);
        }
        logger.info("");
    }

    private boolean shouldBuildArtifact(String artifact) {
        String str = this.option("build-artifacts", "");
        List<String> artifacts = Arrays.asList(str.split(","));
        return artifacts.contains(artifact);
    }

    private boolean shouldBuildEngine() {
        String str = this.option("build-artifacts", "");
        return str.equals("") || shouldBuildArtifact("engine");
    }

    public void scanJavaClasses() throws IOException, CompileExceptionError {
        createClassLoaderScanner();
        registerPipelinePlugins();
        scan(scanner, "com.dynamo.bob");
        scan(scanner, "com.dynamo.bob.pipeline");
        scan(scanner, "com.defold.extension.pipeline");
    }

    private Future buildRemoteEngine(IProgress monitor, ExecutorService executor) {
        Callable<Void> callable = new Callable<>() {
            public Void call() throws Exception {
                logInfo("Build Remote Engine...");
                TimeProfiler.addMark("StartBuildRemoteEngine", "Build Remote Engine");
                final String variant = option("variant", Bob.VARIANT_RELEASE);
                final Boolean withSymbols = hasOption("with-symbols");

                Map<String, String> appmanifestOptions = new HashMap<>();
                appmanifestOptions.put("baseVariant", variant);
                appmanifestOptions.put("withSymbols", withSymbols.toString());

                // temporary removed because TimeProfiler works only with a single thread
                // see https://github.com/pyatyispyatil/flame-chart-js
                // TimeProfiler.addData("withSymbols", withSymbols);
                // TimeProfiler.addData("variant", variant);

                if (hasOption("build-artifacts")) {
                    String s = option("build-artifacts", "");
                    System.out.printf("build-artifacts: %s\n", s);
                    appmanifestOptions.put("buildArtifacts", s);
                }

                Platform platform = getPlatform();

                String[] architectures = platform.getArchitectures().getDefaultArchitectures();
                String customArchitectures = option("architectures", null);
                if (customArchitectures != null) {
                    architectures = customArchitectures.split(",");
                }

                long tstart = System.currentTimeMillis();

                buildEngine(monitor, architectures, appmanifestOptions);

                long tend = System.currentTimeMillis();
                logger.info("Engine build took %f s", (tend-tstart)/1000.0);
                TimeProfiler.addMark("FinishedBuildRemoteEngine", "Build Remote Engine Finished");

                return (Void)null;
            }
        };
        return executor.submit(callable);
    }

    private List<TaskResult> createAndRunTasks(IProgress monitor) throws IOException, CompileExceptionError {
        // Do early test if report files are writable before we start building
        boolean generateReport = this.hasOption("build-report") || this.hasOption("build-report-html");
        FileWriter resourceReportJSONWriter = null;
        FileWriter resourceReportHTMLWriter = null;
        FileWriter excludedResourceReportJSONWriter = null;
        FileWriter excludedResourceReportHTMLWriter = null;

        if (this.hasOption("build-report")) {
            String resourceReportJSONPath = this.option("build-report", "report.json");

            File resourceReportJSONFile = new File(resourceReportJSONPath);
            File resourceReportJSONFolder = resourceReportJSONFile.getParentFile();
            resourceReportJSONWriter = new FileWriter(resourceReportJSONFile);

            String excludedResourceReportJSONName = "excluded_" + resourceReportJSONFile.getName();
            File excludedResourceReportJSONFile = new File(resourceReportJSONFolder, excludedResourceReportJSONName);
            excludedResourceReportJSONWriter = new FileWriter(excludedResourceReportJSONFile);
        }
        if (this.hasOption("build-report-html")) {
            String resourceReportHTMLPath = this.option("build-report-html", "report.html");
            File resourceReportHTMLFile = new File(resourceReportHTMLPath);

            File resourceReportHTMLFolder = resourceReportHTMLFile.getParentFile();
            resourceReportHTMLWriter = new FileWriter(resourceReportHTMLFile);

            String excludedResourceReportHTMLName = "excluded_" + resourceReportHTMLFile.getName();
            File excludedResourceReportHTMLFile = new File(resourceReportHTMLFolder, excludedResourceReportHTMLName);
            excludedResourceReportHTMLWriter = new FileWriter(excludedResourceReportHTMLFile);
        }

        IProgress m = monitor.subProgress(99);

        IProgress mrep = m.subProgress(1);
        mrep.beginTask("Reading tasks...", 1);
        TimeProfiler.start("Create tasks");
        BundleHelper.throwIfCanceled(monitor);
        pruneSources();
        createTasks();
        validateBuildResourceMapping();
        TimeProfiler.addData("TasksCount", tasks.size());
        TimeProfiler.stop();
        mrep.done();

        BundleHelper.throwIfCanceled(monitor);
        m.beginTask("Building...", tasks.size());
        TimeProfiler.start("Build tasks");
        TimeProfiler.addData("TasksCount", tasks.size());

        BundleHelper.throwIfCanceled(monitor);
        List<TaskResult> result = runTasks(m);
        BundleHelper.throwIfCanceled(monitor);
        m.done();

        TimeProfiler.stop();

        // Generate and save build report
        TimeProfiler.start("Generating build size report");
        if (generateReport && !anyFailing(result)) {
            mrep = monitor.subProgress(1);
            mrep.beginTask("Generating report...", 1);
            ReportGenerator rg = new ReportGenerator(this);
            String resourceReportJSON = rg.generateResourceReportJSON();
            String excludedResourceReportJSON = rg.generateExcludedResourceReportJSON();

            // Save JSON report
            if (this.hasOption("build-report")) {
                resourceReportJSONWriter.write(resourceReportJSON);
                resourceReportJSONWriter.close();
                excludedResourceReportJSONWriter.write(excludedResourceReportJSON);
                excludedResourceReportJSONWriter.close();
            }

            // Save HTML report
            if (this.hasOption("build-report-html")) {
                String resourceReportHTML = rg.generateHTML(resourceReportJSON);
                String excludedResourceReportHTML = rg.generateHTML(excludedResourceReportJSON);
                resourceReportHTMLWriter.write(resourceReportHTML);
                resourceReportHTMLWriter.close();
                excludedResourceReportHTMLWriter.write(excludedResourceReportHTML);
                excludedResourceReportHTMLWriter.close();
            }
            mrep.done();
        }
        TimeProfiler.stop();

        return result;
    }

    private void clean(IProgress monitor, State state) {
        IProgress m = monitor.subProgress(1);
        List<String> paths = state.getPaths();
        m.beginTask("Cleaning...", paths.size());
        for (String path : paths) {
            File f = new File(path);
            if (f.exists()) {
                state.removeSignature(path);
                f.delete();
                m.worked(1);
                BundleHelper.throwIfCanceled(monitor);
            }
        }
        m.done();
    }

    private void distClean(IProgress monitor) throws IOException {
        IProgress m = monitor.subProgress(1);
        m.beginTask("Cleaning...", 1);
        BundleHelper.throwIfCanceled(monitor);
        FileUtils.deleteDirectory(new File(FilenameUtils.concat(rootDirectory, buildDirectory)));
        m.worked(1);
        m.done();
    }

    private List<TaskResult> doBuild(IProgress monitor, String... commands) throws Throwable, IOException, CompileExceptionError, MultipleCompileException {
        TimeProfiler.start("Prepare cache");
        resourceCache.init(getLocalResourceCacheDirectory(), getRemoteResourceCacheDirectory());
        resourceCache.setRemoteAuthentication(getRemoteResourceCacheUser(), getRemoteResourceCachePass());
        fileSystem.loadCache();
        IResource stateResource = fileSystem.get(FilenameUtils.concat(buildDirectory, "_BobBuildState_"));
        state = State.load(stateResource);
        TimeProfiler.stop();
        List<TaskResult> result = new ArrayList<TaskResult>();

        BundleHelper.throwIfCanceled(monitor);

        monitor.beginTask("Working...", 100);

        {
            IProgress mrep = monitor.subProgress(1);
            mrep.beginTask("Reading classes...", 1);
            scanJavaClasses();
            mrep.done();
        }

        loop:
        for (String command : commands) {
            BundleHelper.throwIfCanceled(monitor);
            TimeProfiler.start(command);
            switch (command) {
                case "build": {
                    ExtenderUtil.checkProjectForDuplicates(this); // Throws if there are duplicate files in the project (i.e. library and local files conflict)
                    loadIgnoredFilesAndFolders(); // load once before building to be able to use it in a few places
                    final String[] platforms = getPlatformStrings();
                    Future<Void> remoteBuildFuture = null;
                    // Get or build engine binary
                    boolean shouldBuildRemoteEngine = ExtenderUtil.hasNativeExtensions(this);
                    if (shouldBuildRemoteEngine) {
                        remoteBuildFuture = buildRemoteEngine(monitor, executor);
                    }
                    else {
                        // Remove the remote built executables in the build folder, they're still in the cache
                        cleanEngines(monitor, platforms);
                        if (hasOption("with-symbols")) {
                            IProgress progress = monitor.subProgress(1);
                            downloadSymbols(progress);
                            progress.done();
                        }
                    }

                    if (shouldBuildEngine() && BundleHelper.isArchiveIncluded(this)) {
                        result = createAndRunTasks(monitor);
                    }

                    if (remoteBuildFuture != null) {
                        // get the result from the remote build and catch
                        // if an exception was thrown in buildRemoteEngine() the
                        // original exception is included in the ExecutionException
                        try {
                            remoteBuildFuture.get();
                        }
                        catch (ExecutionException|InterruptedException e) {
                            Throwable cause = e.getCause();
                            if ((cause instanceof MultipleCompileException) ||
                                (cause instanceof CompileExceptionError)) {
                                throw cause;
                            }
                            else {
                                throw new CompileExceptionError(cause);
                            }
                        }
                    }

                    if (anyFailing(result)) {
                        break loop;
                    }
                    break;
                }
                case "clean": {
                    clean(monitor, state);
                    break;
                }
                case "distclean": {
                    distClean(monitor);
                    break;
                }
                case "bundle": {
                    bundle(monitor);
                    break;
                }
                default: break;
            }
            TimeProfiler.stop();
        }

        monitor.done();
        TimeProfiler.start("Save cache");
        state.save(stateResource);
        fileSystem.saveCache();
        TimeProfiler.stop();
        return result;
    }



    @SuppressWarnings({ "rawtypes", "unchecked" })
    private List<TaskResult> runTasks(IProgress monitor) throws IOException {
        // set of all completed tasks. The set includes both task run
        // in this session and task already completed (output already exists with correct signatures, see below)
        // the set also contains failed tasks
        Set<Task> completedTasks = new HashSet<>();

        // the set of all output files generated
        // in this or previous session
        Set<IResource> completedOutputs = new HashSet<>();

        List<TaskResult> result = new ArrayList<>();

        List<Task<?>> buildTasks = new ArrayList<>(this.getTasks());
        // set of *all* possible output files
        Set<IResource> allOutputs = new HashSet<>();
        for (Task<?> task : this.getTasks()) {
            allOutputs.addAll(task.getOutputs());
        }
        tasks.clear();

        TextureGenerator.maxThreads = getMaxCpuThreads();

        // Keep track of the paths for all outputs
        outputs = new HashMap<>(allOutputs.size());
        for (IResource res : allOutputs) {
            outputs.put(res.getAbsPath(), EnumSet.noneOf(OutputFlags.class));
        }

        // This flag is set to true as soon as one task has failed. This will
        // break out of the outer loop after the remaining tasks has been tried once.
        // NOTE The underlying problem is that if a task fails and has dependent
        // tasks, the dependent tasks will be tried forever. It should be solved
        // by marking all dependent tasks as failed instead of this flag.
        boolean taskFailed = false;
run:
        while (completedTasks.size() < buildTasks.size()) {
            for (Task<?> task : buildTasks) {
                BundleHelper.throwIfCanceled(monitor);

                // deps are the task input files generated by another task not yet completed,
                // i.e. "solve" the dependency graph
                Set<IResource> deps = new HashSet<>(task.getInputs());
                deps.retainAll(allOutputs);
                deps.removeAll(completedOutputs);
                if (deps.size() > 0) {
                    // postpone task. dependent input not yet generated
                    continue;
                }

                final List<IResource> outputResources = task.getOutputs();

                // do all output files exist?
                boolean allOutputExists = true;
                for (IResource r : outputResources) {
                    if (!r.exists()) {
                        allOutputExists = false;
                        break;
                    }
                }

                // compare all task signature. current task signature between previous
                // signature from state on disk
                TimeProfiler.start("compare signatures");
                TimeProfiler.addData("color", "#FFC0CB");
                TimeProfiler.addData("main input", String.valueOf(task.input(0)));
                byte[] taskSignature = task.calculateSignature();
                boolean allSigsEquals = true;
                for (IResource r : outputResources) {
                    byte[] s = state.getSignature(r.getAbsPath());
                    if (!Arrays.equals(s, taskSignature)) {
                        allSigsEquals = false;
                        break;
                    }
                }
                TimeProfiler.stop();

                boolean shouldRun = (!allOutputExists || !allSigsEquals) && !completedTasks.contains(task);

                if (!shouldRun) {
                    if (allOutputExists && allSigsEquals)
                    {
                        // Task is successfully completed now or in a previous build.
                        // Only if the conditions in the if-statements are true add the task to the completed set and the
                        // output files to the completed output set
                        completedTasks.add(task);
                        completedOutputs.addAll(outputResources);
                    }

                    monitor.worked(1);
                    continue;
                }

                TimeProfiler.start(task.getName());
                TimeProfiler.addData("output", task.getOutputsString());
                TimeProfiler.addData("type", "buildTask");

                completedTasks.add(task);

                TaskResult taskResult = new TaskResult(task);
                result.add(taskResult);
                Builder builder = task.getBuilder();
                boolean ok = true;
                int lineNumber = 0;
                String message = null;
                Throwable exception = null;
                boolean abort = false;
                Map<IResource, String> outputResourceToCacheKey = new HashMap<IResource, String>();
                try {
                    if (task.isCacheable() && resourceCache.isCacheEnabled()) {
                        // check if all output resources exist in the resource cache
                        boolean allResourcesCached = true;
                        for (IResource r : outputResources) {
                            final String key = ResourceCacheKey.calculate(task, options, r);
                            outputResourceToCacheKey.put(r, key);
                            if (!r.isCacheable()) {
                                allResourcesCached = false;
                            }
                            else if (!resourceCache.contains(key)) {
                                allResourcesCached = false;
                            }
                        }

                        // all resources exist in the cache
                        // copy them to the output
                        if (allResourcesCached) {
                            TimeProfiler.addData("takenFromCache", true);
                            for (IResource r : outputResources) {
                                r.setContent(resourceCache.get(outputResourceToCacheKey.get(r)));
                            }
                        }
                        // build task and cache output
                        else {
                            builder.build(task);
                            for (IResource r : outputResources) {
                                state.putSignature(r.getAbsPath(), taskSignature);
                                if (r.isCacheable()) {
                                    resourceCache.put(outputResourceToCacheKey.get(r), r.getContent());
                                }
                            }
                        }
                    }
                    else {
                        builder.build(task);
                        for (IResource r : outputResources) {
                            state.putSignature(r.getAbsPath(), taskSignature);
                        }
                    }
                    monitor.worked(1);

                    for (IResource r : outputResources) {
                        if (!r.exists()) {
                            message = String.format("Output '%s' not found", r.getAbsPath());
                            ok = false;
                            break;
                        }
                    }
                    completedOutputs.addAll(outputResources);
                    TimeProfiler.stop();

                } catch (CompileExceptionError e) {
                    TimeProfiler.stop();
                    ok = false;
                    lineNumber = e.getLineNumber();
                    message = e.getMessage();
                } catch (Throwable e) {
                    TimeProfiler.stop();
                    ok = false;
                    message = e.getMessage();
                    exception = e;
                    abort = true;

                    // to fix the issue it's easier to see the actual callstack
                    exception.printStackTrace(new java.io.PrintStream(System.out));
                }
                if (!ok) {
                    taskFailed = true;
                    taskResult.setOk(ok);
                    taskResult.setLineNumber(lineNumber);
                    taskResult.setMessage(message);
                    taskResult.setException(exception);
                    // Clear sigs for all outputs when a task fails
                    for (IResource r : outputResources) {
                        state.putSignature(r.getAbsPath(), new byte[0]);
                    }
                    if (abort) {
                        break run;
                    }
                }
            }
            if (taskFailed) {
                break;
            }
            // set of *all* possible output files
            // TODO: do we really need this?
            // It seems like we never create new tasks during building process
            for (Task<?> task : this.getTasks()) {
                allOutputs.addAll(task.getOutputs());
            }
            buildTasks.addAll(this.getTasks());
            tasks.clear();
        }
        return result;
    }

    /**
     * Set files to compile
     * @param inputs list of input files
     */
    public void setInputs(List<String> inputs) {
        this.inputs = new ArrayList<String>(inputs);
    }

    public HashMap<String, EnumSet<OutputFlags>> getOutputs() {
        return outputs;
    }

    public EnumSet<OutputFlags> getOutputFlags(String resourcePath) {
        return outputs.get(resourcePath);
    }

    /**
     * Add output flag to resource
     * @param resourcePath output resource absolute path
     * @param flag OutputFlag to add
     */
    public boolean addOutputFlags(String resourcePath, OutputFlags flag) {
        EnumSet<OutputFlags> currentFlags = outputs.get(resourcePath);
        if(currentFlags == null) {
            return false;
        }
        currentFlags.add(flag);
        outputs.replace(resourcePath, currentFlags);
        return true;
    }

    /**
     * Set URLs of libraries to use.
     * @param libUrls list of library URLs
     * @throws IOException
     */
    public void setLibUrls(List<URL> libUrls) throws IOException {
        this.libUrls = libUrls;
    }

    /**
     * Resolve (i.e. download from server) the stored lib URLs.
     * @throws IOException
     */
    public void resolveLibUrls(IProgress progress) throws IOException, LibraryException {
        try {
            String libPath = getLibPath();
            File libDir = new File(libPath);
            // Clean lib dir first
            //FileUtils.deleteQuietly(libDir);
            FileUtils.forceMkdir(libDir);
            // Download libs
            Map<String, File> libFiles = LibraryUtil.collectLibraryFiles(libPath, libUrls);
            int count = this.libUrls.size();
            IProgress subProgress = progress.subProgress(count);
            subProgress.beginTask("Download archive(s)", count);
            logInfo("Downloading %d archive(s)", count);
            for (int i = 0; i < count; ++i) {
                TimeProfiler.startF("Lib %2d", i);
                BundleHelper.throwIfCanceled(progress);
                URL url = libUrls.get(i);
                File f = libFiles.get(url.toString());

                logInfo("%2d: Downloading %s", i, url);
                TimeProfiler.addData("url", url.toString());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                // GitLab will respond with a 406 Not Acceptable if the request
                // is made without an Accept header
                connection.setRequestProperty("Accept", "application/zip");

                String etag = null;
                if (f != null) {
                    String etagB64 = LibraryUtil.getETagFromName(LibraryUtil.getHashedUrl(url), f.getName());
                    if (etagB64 != null) {
                        etag = new String(new Base64().decode(etagB64.getBytes())).replace("\"", ""); // actually includes the quotation marks
                        etag = String.format("\"%s\"", etag); // fixing broken etag
                        connection.addRequestProperty("If-None-Match", etag);
                    }
                }

                // Check if URL contains basic auth credentials
                String basicAuthData = null;
                try {
                    URI uri = new URI(url.toString());
                    basicAuthData = uri.getUserInfo();
                } catch (URISyntaxException e1) {
                    // Ignored, could not get URI and basic auth data from URL.
                }

                // Check if basic auth password is a token that should be replaced with
                // an environment variable.
                // The token should start and end with __ and exist as an environment
                // variable.
                if (basicAuthData != null) {
                    String[] parts = basicAuthData.split(":");
                    String username = parts[0];
                    String password = parts.length > 1 ? parts[1] : "";
                    if (password.startsWith("__") && password.endsWith("__")) {
                        String envKey = password.substring(2, password.length() - 2);
                        String envValue = getSystemEnv(envKey);
                        if (envValue != null) {
                            basicAuthData = username + ":" + envValue;
                        }
                    }
                }

                // Pass correct headers along to server depending on auth alternative.
                final String email = this.options.get("email");
                final String auth = this.options.get("auth");
                if (basicAuthData != null) {
                    String basicAuth = "Basic " + new String(new Base64().encode(basicAuthData.getBytes()));
                    connection.setRequestProperty("Authorization", basicAuth);
                } else if (email != null && auth != null) {
                    connection.addRequestProperty("X-Email", email);
                    connection.addRequestProperty("X-Auth", auth);
                }

                InputStream input = null;
                try {
                    connection.connect();
                    int code = connection.getResponseCode();

                    TimeProfiler.addData("status code", code);
                    if (code == 304) {
                        logInfo("%2d: Status %d: Already cached", i, code);
                    } else if (code >= 400) {
                        logWarning("%2d: Status %d: Failed to download %s", i, code, url);
                        throw new LibraryException(String.format("Status %d: Failed to download %s", code, url), new Exception());
                    } else {

                        String serverETag = connection.getHeaderField("ETag");
                        if (serverETag == null) {
                            serverETag = connection.getHeaderField("Etag");
                        }

                        if (serverETag == null) {
                            logWarning(String.format("The URL %s didn't provide an ETag", url));
                            serverETag = "";
                        }

                        if (etag != null && !etag.equals(serverETag)) {
                            logInfo("%2d: Status %d: ETag mismatch %s != %s. Deleting old file %s", i, code, etag!=null?etag:"", serverETag!=null?serverETag:"", f);
                            f.delete();
                            f = null;
                        }

                        input = new BufferedInputStream(connection.getInputStream());

                        if (f == null) {
                            f = new File(libPath, LibraryUtil.getFileName(url, serverETag));
                        }
                        FileUtils.copyInputStreamToFile(input, f);

                        try {
                            ZipFile zip = new ZipFile(f);
                            zip.close();
                        } catch (ZipException e) {
                            f.delete();
                            throw new LibraryException(String.format("The file obtained from %s is not a valid zip file", url.toString()), e);
                        }
                        logInfo("%2d: Status %d: Stored %s", i, code, f);
                    }
                    connection.disconnect();
                } catch (ConnectException e) {
                    throw new LibraryException(String.format("Connection refused by the server at %s", url.toString()), e);
                } catch (FileNotFoundException e) {
                    throw new LibraryException(String.format("The URL %s points to a resource which doesn't exist", url.toString()), e);
                } finally {
                    if(input != null) {
                        IOUtils.closeQuietly(input);
                    }
                    subProgress.worked(1);
                }

                BundleHelper.throwIfCanceled(subProgress);
                TimeProfiler.stop();
            }
        }
        catch(IOException ioe) {
            throw ioe;
        }
        catch(LibraryException le) {
            throw le;
        }
        catch(Exception e) {
            throw new LibraryException(e.getMessage(), e);
        }
   }

    /**
     * Set option
     * @param key option key
     * @param value option value
     */
    public void setOption(String key, String value) {
        options.put(key, value);
    }

    /**
     * Get option
     * @param key key to get option for
     * @param defaultValue default value
     * @return mapped value or default value is key doesn't exists
     */
    public String option(String key, String defaultValue) {
        String v = options.get(key);
        if (v != null)
            return v;
        else
            return defaultValue;
    }

    /**
     * Check if an option exists
     * @param key option key to check if it exists
     * @return true if the option exists
     */
    public boolean hasOption(String key) {
        return options.containsKey(key);
    }

    /**
     * Get a map of all options
     * @return A map of options
     */
    public Map<String, String> getOptions() {
        return options;
    }

    class Walker extends FileSystemWalker {

        private Set<String> skipDirs;

        public Walker(Set<String> skipDirs) {
            this.skipDirs = skipDirs;
        }

        @Override
        public void handleFile(String path, Collection<String> results) {
            path = FilenameUtils.normalize(path, true);
            boolean include = true;
            if (skipDirs != null) {
                for (String sd : skipDirs) {
                    if (FilenameUtils.wildcardMatch(path, sd + "/*")) {
                        include = false;
                    }
                }
            }
            // ignore all .files, for instance the .project file that is generated by many Eclipse based editors
            if (FilenameUtils.getBaseName(path).isEmpty()) {
                include = false;
            }
            if (include) {
                // We'll add all files, and prune them later, when we know what file formats we support (after th eplugins are built)
                results.add(path);
            }
        }

        @Override
        public boolean handleDirectory(String path, Collection<String> results) {
            path = FilenameUtils.normalize(path, true);
            if (skipDirs != null) {
                for (String sd : skipDirs) {
                    if (FilenameUtils.equalsNormalized(sd, path)) {
                        return false;
                    }
                    if (FilenameUtils.wildcardMatch(path, sd + "/*")) {
                        return false;
                    }
                }
            }
            return super.handleDirectory(path, results);
        }
    }

    /**
     * Find source files under the root directory
     * @param path path to begin in. Absolute or relative to root-directory
     * @param skipDirs
     * @throws IOException
     */
    public void findSources(String path, Set<String> skipDirs) throws IOException {
        if (new File(path).isAbsolute()) {
            path = normalizeNoEndSeparator(path, true);
            if (path.startsWith(rootDirectory)) {
                path = path.substring(rootDirectory.length());
            } else {
                throw new FileNotFoundException(String.format("the source '%s' must be located under the root '%s'", path, rootDirectory));
            }
        }
        String absolutePath = normalizeNoEndSeparator(FilenameUtils.concat(rootDirectory, path), true);
        if (!new File(absolutePath).exists()) {
            throw new FileNotFoundException(String.format("the path '%s' can not be found under the root '%s'", path, rootDirectory));
        }
        Walker walker = new Walker(skipDirs);
        List<String> results = new ArrayList<String>(1024);
        fileSystem.walk(path, walker, results);
        inputs = results;
    }

    private void pruneSources() {
        List<String> results = new ArrayList<>();
        for (String path : inputs) {
            String ext = "." + FilenameUtils.getExtension(path);
            Class<? extends Builder<?>> builderClass = extToBuilder.get(ext);
            if (builderClass != null)
            {
                results.add(path);
            }
        }
        inputs = results;
    }

    public IResource getResource(String path) {
        return fileSystem.get(FilenameUtils.normalize(path, true));
    }

    public IResource getResource(String category, String key, boolean mustExist) throws IOException {
        IResource resource = null;
        String val = this.projectProperties.getStringValue(category, key);
        if (val != null && val.trim().length() > 0) {
            resource = this.getResource(val);
        }
        if (mustExist) {
            if (resource == null) {
                throw new IOException(String.format("Resource is null: %s.%s = '%s'", category, key, val==null?"null":val));
            }
            if (!resource.exists()) {
                throw new IOException(String.format("Resource does not exist: %s.%s = '%s'", category, key, resource.getPath()));
            }
        }
        return resource;
    }

    public IResource getResource(String category, String key) throws IOException {
        return getResource(category, key, true);
    }

    public IResource getGameProjectResource() {
        return getResource("/game.project");
    }

    public IResource getGeneratedResource(long hash, String suffix) {
        Map<Long, IResource> submap = hashToResource.get(suffix);
        if (submap == null)
            return null;
        return submap.get(hash);
    }

    public IResource createGeneratedResource(long hash, String suffix) {
        Map<Long, IResource> submap = hashToResource.get(suffix);
        if (submap == null) {
            submap = new HashMap<>();
            hashToResource.put(suffix, submap);
        }

        IResource genResource = fileSystem.get(String.format("_generated_%x.%s", hash, suffix)).output();
        submap.put(hash, genResource);
        return genResource;
    }

    public static String stripLeadingSlash(String path) {
        while (path.length() > 0 && path.charAt(0) == '/') {
            path = path.substring(1);
        }
        return path;
    }

    public static int getDefaultMaxCpuThreads() {
        int maxThreads = 1;
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        if (availableProcessors > 4) {
            maxThreads = availableProcessors - 2;
        }
        else if (availableProcessors > 1) {
            maxThreads = availableProcessors - 1;
        }
        return maxThreads;
    }

    public void findResourcePaths(String _path, Collection<String> result) {
        final String path = Project.stripLeadingSlash(_path);
        fileSystem.walk(path, new FileSystemWalker() {
            public void handleFile(String path, Collection<String> results) {
                boolean shouldAdd = true;
                // Ignore for native extensions and the other systems.
                // Check comment for loadIgnoredFilesAndFolders()
                for (String prefix : excluedFilesAndFoldersEntries) {
                    if (path.startsWith(prefix)) {
                        shouldAdd = false;
                        break;
                    }
                }
                if (shouldAdd) {
                    results.add(FilenameUtils.normalize(path, true));
                }
            }
        }, result);
    }

    // Finds the first level of directories in a path
    public void findResourceDirs(String _path, Collection<String> result) {
        // Make sure the path has Unix separators, since this is how
        // paths are specified game project relative internally.
        final String path = Project.stripLeadingSlash(FilenameUtils.separatorsToUnix(_path));
        fileSystem.walk(path, new FileSystemWalker() {
            public boolean handleDirectory(String dir, Collection<String> results) {
                if (path.equals(dir)) {
                    return true;
                }
                results.add(FilenameUtils.getName(FilenameUtils.normalizeNoEndSeparator(dir)));
                return false; // skip recursion
            }
            public void handleFile(String path, Collection<String> results) { // skip any files
            }
        }, result);
    }

    public List<Task<?>> getTasks() {
        return Collections.unmodifiableList(new ArrayList(this.tasks.values()));
    }

    public TextureProfiles getTextureProfiles() {
        return textureProfiles;
    }

    public void setTextureProfiles(TextureProfiles textureProfiles) {
        this.textureProfiles = textureProfiles;
    }

}
