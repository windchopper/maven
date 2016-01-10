package com.github.windshopper.maven.plugins.wsdl.download;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.ow2.easywsdl.schema.SchemaFactory;
import org.ow2.easywsdl.schema.api.Schema;
import org.ow2.easywsdl.schema.api.SchemaWriter;
import org.ow2.easywsdl.wsdl.WSDLFactory;
import org.ow2.easywsdl.wsdl.api.Description;
import org.ow2.easywsdl.wsdl.api.WSDLReader;
import org.ow2.easywsdl.wsdl.api.WSDLWriter;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Mojo(
    name = "download",
    defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
    threadSafe = true)
public class WsdlDownloadMojo extends AbstractMojo {

    private static class UnifiedReference<T> {

        private final Supplier<T> entitySupplier;
        private final Consumer<URI> locationSetter;

        public UnifiedReference(Supplier<T> entitySupplier, Consumer<URI> locationSetter) {
            this.entitySupplier = entitySupplier;
            this.locationSetter = locationSetter;
        }

        public T entity() {
            return entitySupplier.get();
        }

        public void setLocation(Path location) {
            locationSetter.accept(URI.create(location.getFileName().toString()));
        }

    }

    /*
     *
     */

    private static final String SUFFIX__DEFINITION = ".wsdl";
    private static final String SUFFIX__SCHEMA = ".xsd";

    private static final Pattern keyValuePattern = Pattern.compile(".+[=](?<value>.+)");

    @Component private MavenProject project;
    @Parameter(required = true, property = "url") private URL[] urls;
    @Parameter(property = "downloadDirectory", defaultValue = "wsdl") private String downloadDirectory;

    private WSDLReader wsdlReader;
    private WSDLWriter wsdlWriter;
    private SchemaWriter schemaWriter;
    private Transformer transformer;

    private final Map<URI, Description> roots = new HashMap<>();
    private final Map<URI, Path> saved = new HashMap<>();

    private Path rootDownloadPath;
    private Path includeDownloadPath;

    /*
     *
     */

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            WSDLFactory wsdlFactory = WSDLFactory.newInstance();
            wsdlReader = wsdlFactory.newWSDLReader();
            wsdlWriter = wsdlFactory.newWSDLWriter();

            SchemaFactory schemaFactory = SchemaFactory.newInstance();
            schemaWriter = schemaFactory.newSchemaWriter();

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformer = transformerFactory.newTransformer();

            Stream.of(urls).forEach(url -> {
                Description description = loadWsdl(url);
                roots.put(description.getDocumentBaseURI(), description);
            });

            roots.values().forEach(this::saveDescription);
        } catch (Exception thrown) {
            throw new MojoFailureException(
                thrown.getMessage(),
                thrown);
        }
    }

    /*
     *
     */

    private Description loadWsdl(URL url) {
        try {
            return wsdlReader.read(url);
        } catch (Exception thrown) {
            throw new RuntimeException(thrown);
        }
    }

    private <T> void saveReferences(Function<T, Path> writeFunctor, Stream<UnifiedReference<T>> unifiedImports) {
        unifiedImports.forEach(anImport -> anImport.setLocation(writeFunctor.apply(anImport.entity())));
    }

    private Path saveDescription(Description description) {
        Path path = saved.get(
            description.getDocumentBaseURI()
        );

        if (path != null) {
            return path;
        }

        saveReferences(this::saveDescription, description.getImports().stream()
            .map(ref -> new UnifiedReference<>(ref::getDescription, ref::setLocationURI)));
        saveReferences(this::saveDescription, description.getIncludes().stream()
            .map(ref -> new UnifiedReference<>(ref::getDescription, ref::setLocationURI)));
        saveReferences(this::saveSchema, description.getTypes().getImportedSchemas().stream()
            .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)));
        saveReferences(this::saveSchema, description.getTypes().getSchemas().stream()
            .flatMap(schema -> Stream.concat(schema.getImports().stream()
                .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)), schema.getIncludes().stream()
                .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)))));

        try {
            getLog().info(
                "Writing wsdl: " + description.getDocumentBaseURI()
            );

            path = path(
                description.getDocumentBaseURI(),
                SUFFIX__DEFINITION
            );

            transformer.transform(
                new DOMSource(
                    wsdlWriter.getDocument(description)
                ),
                new StreamResult(
                    Files.newOutputStream(path)
                )
            );

            saved.put(
                description.getDocumentBaseURI(),
                path
            );

            return path;
        } catch (Exception thrown) {
            throw new RuntimeException(thrown);
        }
    }

    private Path saveSchema(Schema schema) {
        Path path = saved.get(
            schema.getDocumentURI()
        );

        if (path != null) {
            return path;
        }

        saveReferences(this::saveSchema, Stream.concat(schema.getImports().stream()
            .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)), schema.getIncludes().stream()
            .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI))));

        try {
            getLog().info(
                "Writing schema: " + schema.getDocumentURI()
            );

            path = path(
                schema.getDocumentURI(),
                SUFFIX__SCHEMA
            );

            transformer.transform(
                new DOMSource(
                    schemaWriter.getDocument(schema)
                ),
                new StreamResult(
                    Files.newOutputStream(path)
                )
            );

            saved.put(
                schema.getDocumentURI(),
                path
            );

            return path;
        } catch (Exception thrown) {
            throw new RuntimeException(thrown);
        }
    }

    private Path path(URI uri, String suffix) throws IOException {
        Matcher matcher = keyValuePattern.matcher(
            uri.getQuery()
        );

        String fileName;

        if (matcher.matches()) {
            fileName = matcher.group("value");
        } else {
            fileName = uri.getPath();
        }

        String finalFileName = fileName.replace(suffix, "").replaceAll("[\\./\\\\]", "_") + suffix;

        if (roots.containsKey(uri)) {
            return rootDownloadPath().resolve(finalFileName);
        } else {
            return includeDownloadPath().resolve(finalFileName);
        }
    }

    private Path rootDownloadPath() throws IOException {
        if (rootDownloadPath == null) {
            rootDownloadPath = Files.createDirectories(Paths.get(project.getBuild().getDirectory()).resolve(downloadDirectory));
        }

        return rootDownloadPath;
    }

    private Path includeDownloadPath() throws IOException {
        if (includeDownloadPath == null) {
            includeDownloadPath = Files.createDirectories(Paths.get(project.getBuild().getDirectory()).resolve(downloadDirectory).resolve("inc"));
        }

        return includeDownloadPath;
    }

}
