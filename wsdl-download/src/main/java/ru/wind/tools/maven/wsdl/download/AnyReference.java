package ru.wind.tools.maven.wsdl.download;

import java.net.URI;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AnyReference<T> {

    private final Supplier<T> targetSupplier;
    private final Consumer<URI> locationConsumer;

    public AnyReference(Supplier<T> targetSupplier, Consumer<URI> locationConsumer) {
        this.targetSupplier = targetSupplier;
        this.locationConsumer = locationConsumer;
    }

    public T target() {
        return targetSupplier.get();
    }

    public void setLocation(URI location) {
        locationConsumer.accept(location);
    }

}
