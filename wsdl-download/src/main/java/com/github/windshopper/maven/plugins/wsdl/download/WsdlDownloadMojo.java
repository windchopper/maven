package com.github.windshopper.maven.plugins.wsdl.download;

import com.ebmwebsourcing.easycommons.xml.DefaultNamespaceContext;
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
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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

    private static class UnifiedRef<T> {

        private final Supplier<T> supplier;
        private final Consumer<URI> uriConsumer;

        public UnifiedRef(Supplier<T> supplier, Consumer<URI> uriConsumer) {
            this.supplier = supplier;
            this.uriConsumer = uriConsumer;
        }

        public T referenced() {
            return supplier.get();
        }

        public void setLocation(URI location) {
            uriConsumer.accept(location);
        }

    }

    /*
     *
     */

    private static final Pattern keyValuePattern = Pattern.compile(".+[=](?<value>.+)");

    @Component
    private MavenProject project;
    @Parameter(required = true)
    private URL[] urls = new URL[0];
    @Parameter(defaultValue = "wsdl")
    private String downloadDirectory;
    @Parameter(defaultValue = "true")
    private boolean removeJaxbGlobalBindings;

    private WSDLReader wsdlReader;
    private WSDLWriter wsdlWriter;
    private SchemaWriter schemaWriter;
    private Transformer transformer;
    private DefaultNamespaceContext namespaceContext;
    private XPath xpath;
    private final Map<URI, Description> roots = new HashMap<>();
    private final Map<URI, Path> paths = new HashMap<>();
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
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            namespaceContext = new DefaultNamespaceContext();
            namespaceContext.bindNamespace("jaxb", "http://java.sun.com/xml/ns/jaxb");
            namespaceContext.bindNamespace("xs", "http://www.w3.org/2001/XMLSchema");

            XPathFactory xpathFactory = XPathFactory.newInstance();
            xpath = xpathFactory.newXPath();
            xpath.setNamespaceContext(namespaceContext);

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
        getLog().info(
            "Loading wsdl: " + url
        );

        try {
            return wsdlReader.read(url);
        } catch (Exception thrown) {
            throw new RuntimeException(thrown);
        }
    }

    private <T> void saveRefs(URI referencerLocation, Function<T, Path> writeFunctor, Stream<UnifiedRef<T>> unifiedRefs) {
        unifiedRefs.forEach(ref -> {
            Path path = writeFunctor.apply(
                ref.referenced()
            );

            try {
                if (roots.containsKey(referencerLocation)) {
                    ref.setLocation(
                        new URI(
                            rootDownloadPath().relativize(path).toString().replace(File.separator, "/")
                        )
                    );
                } else {
                    ref.setLocation(
                        new URI(
                            path.getFileName().toString()
                        )
                    );
                }
            } catch (URISyntaxException thrown) {
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

                saveRefs(location, this::saveDescription, description.getImports().stream()
                    .map(ref -> new UnifiedRef<>(ref::getDescription, ref::setLocationURI)));
                saveRefs(location, this::saveDescription, description.getIncludes().stream()
                    .map(ref -> new UnifiedRef<>(ref::getDescription, ref::setLocationURI)));
                saveRefs(location, this::saveSchema, description.getTypes().getImportedSchemas().stream()
                    .map(ref -> new UnifiedRef<>(ref::getSchema, ref::setLocationURI)));
                saveRefs(location, this::saveSchema, description.getTypes().getSchemas().stream()
                    .flatMap(schema -> Stream.concat(
                        schema.getImports().stream()
                            .map(ref -> new UnifiedRef<>(ref::getSchema, ref::setLocationURI)),
                        schema.getIncludes().stream()
                            .map(ref -> new UnifiedRef<>(ref::getSchema, ref::setLocationURI)))));

                Document descriptionDocument = wsdlWriter.getDocument(description);

                // todo wsdl filtering

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
                "Saving schema: " + schema.getDocumentURI()
            );

            try {
                path = path(location, ".xsd");

                saveRefs(location, this::saveSchema, Stream.concat(
                    schema.getImports().stream()
                        .map(ref -> new UnifiedRef<>(ref::getSchema, ref::setLocationURI)),
                    schema.getIncludes().stream()
                        .map(ref -> new UnifiedRef<>(ref::getSchema, ref::setLocationURI))));

                Document schemaDocument = schemaWriter.getDocument(schema);

                if (removeJaxbGlobalBindings) {
                    removeJaxbGlobalBindings(schemaDocument);
                    removeExtensionBindingPrefixes(schemaDocument);
                }

                transformer.transform(
                    new DOMSource(schemaDocument), new StreamResult(
                        Files.newOutputStream(path)
                    )
                );
            } catch (Exception thrown) {
                throw new RuntimeException(thrown);
            }
        }

        return path;
    }

    private Path path(URI location, String suffix) {
        Matcher matcher = keyValuePattern.matcher(
            location.getQuery()
        );

        String fileName;

        if (matcher.matches()) {
            fileName = matcher.group("value");
        } else {
            fileName = location.getPath();
        }

        Path intermediatePath = Paths.get(
            fileName.replace(suffix, "").concat(suffix)
        );

        Path resultPath;

        if (roots.containsKey(location)) {
            resultPath = rootDownloadPath().resolve(
                intermediatePath.getFileName()
            );
        } else {
            resultPath = includeDownloadPath().resolve(
                String.format("include_%08x_", location.hashCode()) + intermediatePath.getFileName()
            );
        }

        paths.put(location, resultPath);

        return resultPath;
    }

    private Path rootDownloadPath() {
        if (rootDownloadPath == null) {
            try {
                rootDownloadPath = Files.createDirectories(
                    project.getBasedir().toPath().resolve(downloadDirectory)
                );
            } catch (IOException thrown) {
                throw new RuntimeException(thrown);
            }
        }

        return rootDownloadPath;
    }

    private Path includeDownloadPath() {
        if (includeDownloadPath == null) {
            try {
                includeDownloadPath = Files.createDirectories(
                    project.getBasedir().toPath().resolve(downloadDirectory).resolve("include")
                );
            } catch (IOException thrown) {
                throw new RuntimeException(thrown);
            }
        }

        return includeDownloadPath;
    }

    private void removeJaxbGlobalBindings(Document document) throws XPathExpressionException {
        String jaxbPrefix = document.lookupPrefix("http://java.sun.com/xml/ns/jaxb");

        if (jaxbPrefix != null) {
            XPathExpression xpathExpression = xpath.compile("//xs:schema/xs:annotation/xs:appinfo/jaxb:globalBindings");
            Node found = (Node) xpathExpression.evaluate(document, XPathConstants.NODE);

            if (found != null) {
                found.getParentNode().removeChild(found);
            }
        }
    }

    private void removeExtensionBindingPrefixes(Document document) throws XPathExpressionException {
        String jaxbxjcPrefix = document.lookupPrefix("http://java.sun.com/xml/ns/jaxb/xjc");

        if (jaxbxjcPrefix == null) {
            XPathExpression xpathExpression = xpath.compile("//xs:schema/@jaxb:extensionBindingPrefixes");
            Attr found = (Attr) xpathExpression.evaluate(document, XPathConstants.NODE);

            if (found != null) {
                found.getOwnerElement().removeAttributeNode(found);
            }
        }
    }

}
