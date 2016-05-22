package ru.wind.tools.maven.wsdl.download;

import java.net.URI;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UnifiedReference<TargetType> {

    private final Supplier<TargetType> targetSupplier;
    private final Consumer<URI> locationConsumer;

    public UnifiedReference(Supplier<TargetType> targetSupplier, Consumer<URI> locationConsumer) {
        this.targetSupplier = targetSupplier;
        this.locationConsumer = locationConsumer;
    }

    public TargetType target() {
        return targetSupplier.get();
    }

    public void setLocation(URI location) {
        locationConsumer.accept(location);
    }

}
