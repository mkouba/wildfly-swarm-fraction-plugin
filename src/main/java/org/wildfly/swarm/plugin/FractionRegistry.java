package org.wildfly.swarm.plugin;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.yaml.snakeyaml.Yaml;

/**
 * @author Bob McWhirter
 * @author Ken Finnigan
 */
public class FractionRegistry {

    private static final String FRACTION_TAGS_PROPERTY_NAME = "swarm.fraction.tags";

    private static final String FRACTION_INTERNAL_PROPERTY_NAME = "swarm.fraction.internal";

    private static final String FRACTION_STABILITY_PROPERTY_NAME = "swarm.fraction.stability";

    private static final String FRACTION_BOOTSTRAP_PROPERTY = "swarm.fraction.bootstrap";

    private static final String FRACTION_SCOPE_PROPERTY_NAME = "swarm.fraction.scope";

    private static final String BOM_PROPERTY = "swarm.bom";

    private Map<Key, FractionMetadata> fractionRegistry = new HashMap<>();

    private Map<Key, DependencyMetadata> dependencyRegistry = new HashMap<>();

    public static final FractionRegistry INSTANCE = new FractionRegistry();

    private final List<DependencyMetadata> bomInclusions = new ArrayList<>();

    private FractionRegistry() {

    }

    public FractionMetadata of(DependencyMetadata dependency) {
        Key key = Key.of(dependency);
        return fractionRegistry.get(key);
    }

    public FractionMetadata of(MavenProject project) {
        if (project == null) {
            return null;
        }
        if (project.getGroupId().equals("org.wildfly.swarm") && project.getArtifactId().equals("bootstrap")) {
            return null;
        }
        Key key = Key.of(project);
        if (this.fractionRegistry.containsKey(key)) {
            return this.fractionRegistry.get(key);
        }

        FractionMetadata meta = build(project);
        this.fractionRegistry.put(key, meta);
        return meta;
    }

    private FractionMetadata build(MavenProject project) {

        FractionMetadata meta = new FractionMetadata(project.getGroupId(), project.getArtifactId(), project.getVersion(),
                                                     project.getProperties().getProperty(FRACTION_SCOPE_PROPERTY_NAME));

        meta.setName(project.getName());
        meta.setDescription(project.getDescription());

        String stabilityName = project.getProperties().getProperty(FRACTION_STABILITY_PROPERTY_NAME);

        if (stabilityName != null) {
            StabilityLevel stabilityLevel;
            try {
                stabilityLevel = StabilityLevel.valueOf(stabilityName.toUpperCase());
            } catch (NullPointerException | IllegalArgumentException e) {
                stabilityLevel = StabilityLevel.UNSTABLE;
            }
            meta.setStabilityIndex(stabilityLevel);
        }

        String tags = project.getProperties().getProperty(FRACTION_TAGS_PROPERTY_NAME);
        if (tags != null) {
            meta.setTags(Arrays.asList(tags.split(",")));
        }

        String internal = project.getProperties().getProperty(FRACTION_INTERNAL_PROPERTY_NAME);
        if (internal != null && internal.equals("true")) {
            meta.setInternal(true);
        }

        String bootstrap = project.getProperties().getProperty(FRACTION_BOOTSTRAP_PROPERTY);
        if (bootstrap != null) {
            meta.setBootstrap(bootstrap);
        }

        File baseDir = project.getBasedir();
        if (baseDir != null && baseDir.exists()) {
            Path moduleConf = Paths.get(project.getBasedir().getAbsolutePath(), "module.conf");
            if (Files.exists(moduleConf)) {
                meta.setModuleConf(moduleConf);
            }
            Path manifest = Paths.get(project.getBasedir().getAbsolutePath(), "target", "classes", "META-INF", "fraction-manifest.yaml");
            if (Files.exists(manifest)) {
                Yaml yaml = new Yaml();
                try (FileReader reader = new FileReader(manifest.toFile())) {
                    Map<String, ?> result = (Map<String, ?>) yaml.load(reader);
                    List<String> transitiveDeps = (List<String>) result.get("transitive-dependencies");
                    if (transitiveDeps != null) {
                        for (String each : transitiveDeps) {
                            meta.addTransitiveDependency(DependencyMetadata.fromString(each));
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        meta.setJavaFraction(findJavaFraction(project));

        if (!meta.isFraction()) {
            String includeInBOM = project.getProperties().getProperty(BOM_PROPERTY);
            if (includeInBOM != null) {
                DependencyMetadata dep = new DependencyMetadata(meta);
                this.bomInclusions.add(dep);

            }

            // not a fraction, quit.
            return null;
        }

        meta.setHasJavaCode(hasJavaCode(project));
        meta.setBaseModulePath(baseModulePath(meta));
        findDetectorClasses(project, meta);

        project.getModel().getDependencies()
                .stream()
                .filter(d -> d.getScope().equals("compile"))
                .forEach(d -> {
                    Key key = Key.of(d);

                    DependencyMetadata depMeta = this.dependencyRegistry.get(key);
                    if (depMeta == null) {
                        depMeta = new DependencyMetadata(d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getClassifier(), d.getType());
                        this.dependencyRegistry.put(key, depMeta);
                    }

                    meta.addDependency(depMeta);
                });

        //System.err.println("-->" + project.getArtifact().getFile());


        return meta;
    }

    private void findDetectorClasses(MavenProject project, FractionMetadata meta) {
        Path src = Paths.get(project.getBuild().getSourceDirectory());

        if (Files.exists(src)) {
            try {
                Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                    boolean insideDetectPackage = false;

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (dir.endsWith("detect")) {
                            insideDetectPackage = true;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (insideDetectPackage) {
                            meta.addDetectorClass(src.relativize(file), file);
                        }
                        return super.visitFile(file, attrs);
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (insideDetectPackage) {
                            return FileVisitResult.TERMINATE;
                        }
                        return super.postVisitDirectory(dir, exc);
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static Path baseModulePath(FractionMetadata meta) {
        Path path = meta.getJavaFraction();
        if (path != null) {
            return path.getParent();
        }

        path = Paths.get(meta.getGroupId().replace('.', File.separatorChar) + File.separatorChar + meta.getArtifactId().replace('-', File.separatorChar));
        return path;
    }

    private static boolean hasJavaCode(MavenProject project) {
        Path src = Paths.get(project.getBuild().getSourceDirectory());

        AtomicReference<Boolean> hasJava = new AtomicReference<>(false);

        if (Files.exists(src)) {
            try {
                Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                    boolean insideDetectPackage = false;

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (dir.endsWith("detect")) {
                            insideDetectPackage = true;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!insideDetectPackage && file.toString().endsWith(".java")) {
                            hasJava.set(true);
                            return FileVisitResult.TERMINATE;
                        }
                        return super.visitFile(file, attrs);
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (insideDetectPackage) {
                            insideDetectPackage = false;
                        }
                        return super.postVisitDirectory(dir, exc);
                    }
                });
            } catch (IOException e) {
                // ignore
            }
        }

        return hasJava.get();
    }

    private static Path findJavaFraction(MavenProject project) {
        if (project.getGroupId().equals("org.wildfly.swarm") && project.getArtifactId().equals("spi")) {
            return null;
        }
        Path src = Paths.get(project.getBuild().getSourceDirectory());

        AtomicReference<Path> javaFraction = new AtomicReference<>();

        if (Files.exists(src)) {
            try {
                Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith("Fraction.java")) {
                            Path path = src.relativize(file);
                            javaFraction.set(path);
                            return FileVisitResult.TERMINATE;
                        }
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (IOException e) {
                // ignore
            }
        }

        return javaFraction.get();
    }

    public List<DependencyMetadata> bomInclusions() {
        return this.bomInclusions;
    }

    private static class Key {
        private final String groupId;

        private final String artifactId;

        private final String version;

        private final String classifier;

        private final String packaging;


        Key(String groupId, String artifactId, String version, String classifier, String packaging) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.classifier = classifier;
            this.packaging = packaging;
        }

        String compositeKey() {
            return this.groupId + ":" + this.artifactId + ":" + this.version + ":" + this.classifier + ":" + this.packaging;
        }

        @Override
        public int hashCode() {
            return compositeKey().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Key && compositeKey().equals(((Key) obj).compositeKey());
        }

        @Override
        public String toString() {
            return compositeKey();
        }

        public static Key of(MavenProject project) {
            return new Key(project.getGroupId(), project.getArtifactId(), project.getVersion(), null, project.getPackaging());
        }

        public static Key of(Artifact artifact) {
            return new Key(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getClassifier(), artifact.getType());
        }

        public static Key of(DependencyMetadata dependency) {
            return new Key(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getClassifier(), dependency.getPackaging());
        }

        public static Key of(Dependency dependency) {
            return new Key(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getClassifier(), dependency.getType());
        }

    }

}
