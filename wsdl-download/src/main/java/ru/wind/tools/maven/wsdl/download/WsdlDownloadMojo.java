package ru.wind.tools.maven.wsdl.download;

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
import org.w3c.dom.Document;
import ru.wind.common.util.Buffered;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Mojo(
    name = "wsdlDownload",
    defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
    threadSafe = true) public class WsdlDownloadMojo extends AbstractMojo {

    private static final Pattern pairPattern = Pattern.compile(".+[=](?<value>.+)");

    @Component private MavenProject project;

    @Parameter(required = true) private URL[] urls;
    @Parameter(defaultValue = "${project.basedir}/src/main/wsdl") private String downloadDirectory;

    private WSDLReader wsdlReader;
    private WSDLWriter wsdlWriter;
    private SchemaWriter schemaWriter;
    private Transformer transformer;

    private final Map<URI, Description> roots = new HashMap<>();
    private final Map<URI, Path> paths = new HashMap<>();

    private Buffered<Path, IOException> rootDownloadPath = new Buffered<>(Long.MAX_VALUE, TimeUnit.MILLISECONDS,
        () -> Files.createDirectories(Paths.get(downloadDirectory)));
    private Buffered<Path, IOException> includeDownloadPath = new Buffered<>(Long.MAX_VALUE, TimeUnit.MILLISECONDS,
        () -> Files.createDirectories(Paths.get(downloadDirectory).resolve("include")));

    /*
     *
     */

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            WSDLFactory wsdlFactory = WSDLFactory.newInstance();
            wsdlReader = wsdlFactory.newWSDLReader();
            wsdlWriter = wsdlFactory.newWSDLWriter();

            SchemaFactory schemaFactory = SchemaFactory.newInstance();
            schemaWriter = schemaFactory.newSchemaWriter();

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

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
        getLog().info("Loading wsdl: " + url);
        try {
            return wsdlReader.read(url);
        } catch (Exception thrown) {
            throw new RuntimeException(thrown);
        }
    }

    private <T> void saveRefs(URI referencerLocation, Path referencerPath, Function<T, Path> writeFunctor, Stream<UnifiedReference<T>> unifiedRefs) {
        unifiedRefs.forEach(ref -> {
            Path path = writeFunctor.apply(
                ref.target());

            try {
                if (roots.containsKey(referencerLocation)) {
                    ref.setLocation(
                        new URI(
                            rootDownloadPath.value().relativize(path).toString().replace(File.separator, "/")));
                } else {
                    ref.setLocation(
                        new URI(
                            referencerPath.getParent().relativize(path).toString().replace(File.separatorChar, '/')));
                }
            } catch (Exception thrown) {
                throw new RuntimeException(thrown);
            }
        });
    }

    private Path saveDescription(Description description) {
        URI location = description.getDocumentBaseURI();
        Path path = paths.get(location);

        if (path == null) {
            getLog().info(
                "Saving wsdl: " + location
            );

            try {
                path = path(location, ".wsdl");

                saveRefs(location, path, this::saveDescription, description.getImports().stream()
                    .map(ref -> new UnifiedReference<>(ref::getDescription, ref::setLocationURI)));
                saveRefs(location, path, this::saveDescription, description.getIncludes().stream()
                    .map(ref -> new UnifiedReference<>(ref::getDescription, ref::setLocationURI)));
                saveRefs(location, path, this::saveSchema, description.getTypes().getImportedSchemas().stream()
                    .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)));
                saveRefs(location, path, this::saveSchema, description.getTypes().getSchemas().stream()
                    .flatMap(schema -> Stream.concat(
                        schema.getImports().stream()
                            .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)),
                        schema.getIncludes().stream()
                            .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)))));

                Document descriptionDocument = wsdlWriter.getDocument(description);

                transformer.transform(
                    new DOMSource(descriptionDocument), new StreamResult(
                        Files.newOutputStream(path)
                    )
                );
            } catch (Exception thrown) {
                throw new RuntimeException(thrown);
            }
        }

        return path;
    }

    private Path saveSchema(Schema schema) {
        URI location = schema.getDocumentURI();
        Path path = paths.get(location);

        if (path == null) {
            getLog().info(
                "Saving schema: " + schema.getDocumentURI());

            try {
                path = path(location, ".xsd");

                saveRefs(location, path, this::saveSchema, Stream.concat(
                    schema.getImports().stream()
                        .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI)),
                    schema.getIncludes().stream()
                        .map(ref -> new UnifiedReference<>(ref::getSchema, ref::setLocationURI))));

                Document schemaDocument = schemaWriter.getDocument(schema);

                transformer.transform(
                    new DOMSource(schemaDocument), new StreamResult(
                        Files.newOutputStream(path)));
            } catch (Exception thrown) {
                throw new RuntimeException(thrown);
            }
        }

        return path;
    }

    private Path path(URI location, String suffix) {
        Matcher matcher = pairPattern.matcher(
            location.getQuery() == null ? "" : location.getQuery());

        String fileName;
        Path intermediatePath;

        if (matcher.matches()) {
            fileName = matcher.group("value");
            intermediatePath = Paths.get(fileName.replace(suffix, "").concat(suffix));
        } else {
            fileName = location.getPath();
            intermediatePath = Paths.get(fileName.replace(suffix, "").concat(suffix)).getFileName();
        }

        Path resultPath;

        try {
            if (roots.containsKey(location)) {
                resultPath = rootDownloadPath.value().resolve(intermediatePath.getFileName());
            } else {
                resultPath = includeDownloadPath.value().resolve(Paths.get(intermediatePath.toString().replaceAll("\\.\\.", "dd")));
            }

            Files.createDirectories(resultPath.getParent());
        } catch (IOException thrown) {
            throw new RuntimeException(thrown);
        }

        paths.put(location, resultPath);

        return resultPath;
    }

}
