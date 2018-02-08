package ru.wind.tools.maven.wsdl.download;

import com.github.windchopper.common.util.stream.FailableConsumer;
import com.github.windchopper.common.util.stream.FailableFunction;
import com.github.windchopper.common.util.stream.FailableSupplier;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class PL<T> {

    private final T target;

    private <E extends Throwable> PL(@Nonnull FailableSupplier<T, E> supplier) throws E {
        target = Objects.requireNonNull(supplier.get());
    }

    public static <V> PL<V> of(@Nonnull V value) {
        return new PL<>(() -> value);
    }

    public static <V, X extends Exception> PL<V> of(@Nonnull FailableSupplier<V, X> supplier) throws X {
        return new PL<>(supplier);
    }

    public <V> PL<T> set(@Nonnull Function<T, Consumer<V>> consumerFunction, V value) {
        consumerFunction.apply(target).accept(value);
        return this;
    }

    public <V> PL<T> add(@Nonnull Function<T, Supplier<Collection<V>>> supplierFunction, Collection<V> values) {
        supplierFunction.apply(target).get().addAll(values);
        return this;
    }

    public <E extends Throwable> PL<T> accept(@Nonnull FailableConsumer<T, E> consumer) throws E {
        consumer.accept(target);
        return this;
    }

    public <V> PL<T> accept(@Nonnull BiConsumer<T, V> consumer, V argument) {
        consumer.accept(target, argument);
        return this;
    }

    public <O, X extends Throwable> PL<O> map(@Nonnull FailableFunction<T, O, X> mapper) throws X {
        return new PL<>(() -> mapper.apply(target));
    }

    public T get() {
        return target;
    }

}
