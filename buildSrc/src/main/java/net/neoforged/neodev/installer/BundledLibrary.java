package net.neoforged.neodev.installer;

import net.neoforged.neodev.utils.MavenIdentifier;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A fork-owned library that replaces one upstream entry in installed profiles. */
public abstract class BundledLibrary {
    @InputFile
    public abstract RegularFileProperty getFile();

    @Input
    public abstract Property<MavenIdentifier> getOriginalIdentifier();

    @Input
    public abstract Property<MavenIdentifier> getIdentifier();

    @Input
    public abstract Property<String> getUrl();

    List<Library> resolve(List<URI> repositories, Collection<IdentifiedFile> files) throws IOException {
        var original = getOriginalIdentifier().get();
        var retained = files.stream().filter(file -> !file.getIdentifier().get().equals(original)).toList();
        if (files.size() - retained.size() != 1) {
            throw new IllegalStateException("Expected exactly one library to replace: " + original);
        }
        var libraries = new ArrayList<>(LibraryCollector.resolveLibraries(repositories, retained));
        var path = getFile().getAsFile().get().toPath();
        libraries.add(new Library(getIdentifier().get().artifactNotation(), new LibraryDownload(new LibraryArtifact(
                LibraryCollector.sha1Hash(path), Files.size(path), getUrl().get(), getIdentifier().get().repositoryPath()))));
        return libraries;
    }

    String replaceClasspath(String classpath) {
        var original = getOriginalIdentifier().get().repositoryPath();
        if (!classpath.contains(original)) {
            throw new IllegalStateException("Missing upstream library in server classpath: " + original);
        }
        return classpath.replace(original, getIdentifier().get().repositoryPath());
    }
}
