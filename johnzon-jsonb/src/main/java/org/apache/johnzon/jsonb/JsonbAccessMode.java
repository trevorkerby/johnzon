/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.johnzon.jsonb;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.johnzon.mapper.reflection.Converters.matches;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import javax.json.JsonBuilderFactory;
import javax.json.JsonValue;
import javax.json.bind.JsonbException;
import javax.json.bind.adapter.JsonbAdapter;
import javax.json.bind.annotation.JsonbCreator;
import javax.json.bind.annotation.JsonbDateFormat;
import javax.json.bind.annotation.JsonbNillable;
import javax.json.bind.annotation.JsonbNumberFormat;
import javax.json.bind.annotation.JsonbProperty;
import javax.json.bind.annotation.JsonbPropertyOrder;
import javax.json.bind.annotation.JsonbTransient;
import javax.json.bind.annotation.JsonbTypeAdapter;
import javax.json.bind.annotation.JsonbTypeDeserializer;
import javax.json.bind.annotation.JsonbTypeSerializer;
import javax.json.bind.config.PropertyNamingStrategy;
import javax.json.bind.config.PropertyOrderStrategy;
import javax.json.bind.config.PropertyVisibilityStrategy;
import javax.json.bind.serializer.JsonbDeserializer;
import javax.json.bind.serializer.JsonbSerializer;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonParserFactory;

import org.apache.johnzon.core.Types;
import org.apache.johnzon.jsonb.converter.JohnzonJsonbAdapter;
import org.apache.johnzon.jsonb.converter.JsonbDateConverter;
import org.apache.johnzon.jsonb.converter.JsonbLocalDateConverter;
import org.apache.johnzon.jsonb.converter.JsonbLocalDateTimeConverter;
import org.apache.johnzon.jsonb.converter.JsonbNumberConverter;
import org.apache.johnzon.jsonb.converter.JsonbValueConverter;
import org.apache.johnzon.jsonb.converter.JsonbZonedDateTimeConverter;
import org.apache.johnzon.jsonb.serializer.JohnzonDeserializationContext;
import org.apache.johnzon.jsonb.serializer.JohnzonSerializationContext;
import org.apache.johnzon.jsonb.spi.JohnzonAdapterFactory;
import org.apache.johnzon.mapper.Adapter;
import org.apache.johnzon.mapper.Converter;
import org.apache.johnzon.mapper.JohnzonAny;
import org.apache.johnzon.mapper.JohnzonConverter;
import org.apache.johnzon.mapper.MapperConverter;
import org.apache.johnzon.mapper.MappingParser;
import org.apache.johnzon.mapper.ObjectConverter;
import org.apache.johnzon.mapper.TypeAwareAdapter;
import org.apache.johnzon.mapper.access.AccessMode;
import org.apache.johnzon.mapper.access.BaseAccessMode;
import org.apache.johnzon.mapper.access.FieldAccessMode;
import org.apache.johnzon.mapper.access.FieldAndMethodAccessMode;
import org.apache.johnzon.mapper.access.Meta;
import org.apache.johnzon.mapper.access.MethodAccessMode;
import org.apache.johnzon.mapper.converter.ReversedAdapter;
import org.apache.johnzon.mapper.internal.AdapterKey;
import org.apache.johnzon.mapper.internal.ConverterAdapter;

public class JsonbAccessMode implements AccessMode, Closeable {
    private final PropertyNamingStrategy naming;
    private final String order;
    private final PropertyVisibilityStrategy visibility;
    private final AccessMode delegate;
    private final boolean caseSensitive;
    private final Map<AdapterKey, Adapter<?, ?>> defaultConverters;
    private final JohnzonAdapterFactory factory;
    private final Collection<JohnzonAdapterFactory.Instance<?>> toRelease = new ArrayList<>();
    private final JsonProvider jsonProvider;
    private final Supplier<JsonParserFactory> parserFactory;
    private final Supplier<JsonBuilderFactory> builderFactory;
    private final ConcurrentMap<Class<?>, ParsingCacheEntry> parsingCache = new ConcurrentHashMap<>();
    private final BaseAccessMode partialDelegate = new BaseAccessMode(false, false) {
        @Override
        protected Map<String, Reader> doFindReaders(Class<?> clazz) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Map<String, Writer> doFindWriters(Class<?> clazz) {
            throw new UnsupportedOperationException();
        }
    };
    private boolean failOnMissingCreatorValues;
    private final Types types = new Types();
    private final boolean globalIsNillable;

    // CHECKSTYLE:OFF
    public JsonbAccessMode(final PropertyNamingStrategy propertyNamingStrategy, final String orderValue,
                           final PropertyVisibilityStrategy visibilityStrategy, final boolean caseSensitive,
                           final Map<AdapterKey, Adapter<?, ?>> defaultConverters, final JohnzonAdapterFactory factory,
                           final JsonProvider jsonProvider, final Supplier<JsonBuilderFactory> builderFactory,
                           final Supplier<JsonParserFactory> parserFactory,
                           final AccessMode delegate,
                           final boolean failOnMissingCreatorValues,
                           final boolean globalIsNillable) {
        // CHECKSTYLE:ON
        this.globalIsNillable = globalIsNillable;
        this.naming = propertyNamingStrategy;
        this.order = orderValue;
        this.visibility = visibilityStrategy;
        this.caseSensitive = caseSensitive;
        this.delegate = delegate;
        this.defaultConverters = defaultConverters;
        this.factory = factory;
        this.builderFactory = builderFactory;
        this.jsonProvider = jsonProvider;
        this.parserFactory = parserFactory;
        this.failOnMissingCreatorValues = failOnMissingCreatorValues;
    }

    @Override
    public Comparator<String> fieldComparator(final Class<?> clazz) {
        final Comparator<String> orderComparator = orderComparator(clazz);
        return caseSensitive ? orderComparator : ((o1, o2) -> o1.equalsIgnoreCase(o2) ? 0 : orderComparator.compare(o1, o2));
    }

    @Override
    public Factory findFactory(final Class<?> clazz) {
        Constructor<?> constructor = null;
        Method factory = null;
        for (final Constructor<?> c : clazz.getConstructors()) {
            if (c.isAnnotationPresent(JsonbCreator.class)) {
                if (constructor != null) {
                    throw new JsonbException("Only one constructor or method can have @JsonbCreator");
                }
                constructor = c;
            }
        }
        for (final Method m : clazz.getMethods()) {
            final int modifiers = m.getModifiers();
            if (Modifier.isPublic(modifiers) && m.isAnnotationPresent(JsonbCreator.class)) {
                if (constructor != null || factory != null) {
                    throw new JsonbException("Only one constructor or method can have @JsonbCreator");
                }
                factory = m;
            }
        }
        final Constructor<?> finalConstructor = constructor;
        final Method finalFactory = factory;
        final Consumer<Object[]> factoryValidator = failOnMissingCreatorValues ?
                args -> {
                    if (args == null || Stream.of(args).anyMatch(Objects::isNull)) {
                        throw new JsonbException("Missing @JsonbCreator argument");
                    }
                } :
                args -> {};
        final Type[] types;
        final String[] params;
        final Adapter<?, ?>[] converters;
        final Adapter<?, ?>[] itemConverters;
        final ObjectConverter.Codec<?>[] objectConverters;
        if (finalConstructor != null || finalFactory != null) {
            types = finalConstructor != null ? finalConstructor.getGenericParameterTypes() : finalFactory.getGenericParameterTypes();
            params = new String[types.length];
            converters = new Adapter<?, ?>[types.length];
            itemConverters = new Adapter<?, ?>[types.length];
            objectConverters = new ObjectConverter.Codec<?>[types.length];

            int i = 0;
            for (final Parameter parameter : (finalConstructor == null ? finalFactory : finalConstructor).getParameters()) {
                final JsonbProperty property = getAnnotation(parameter, JsonbProperty.class);
                params[i] = property != null ? property.value() : parameter.getName();

                final JsonbTypeAdapter adapter = getAnnotation(parameter, JsonbTypeAdapter.class);
                final JsonbDateFormat dateFormat = getAnnotation(parameter, JsonbDateFormat.class);
                final JsonbNumberFormat numberFormat = getAnnotation(parameter, JsonbNumberFormat.class);
                final JohnzonConverter johnzonConverter = getAnnotation(parameter, JohnzonConverter.class);
                if (adapter == null && dateFormat == null && numberFormat == null && johnzonConverter == null) {
                    converters[i] = defaultConverters.get(parameter.getType());
                    itemConverters[i] = null;
                } else {
                    validateAnnotations(parameter, adapter, dateFormat, numberFormat, johnzonConverter);

                    try {
                        if (adapter != null) {
                            final Adapter converter = toConverter(
                                    this.types, parameter.getType(), adapter, dateFormat, numberFormat);
                            if (matches(parameter.getParameterizedType(), converter)) {
                                converters[i] = converter;
                                itemConverters[i] = null;
                            } else {
                                converters[i] = null;
                                itemConverters[i] = converter;
                            }
                        } else if (johnzonConverter != null) {
                            objectConverters[i] = (ObjectConverter.Codec<?>) johnzonConverter.value().newInstance();
                        }
                    } catch (final InstantiationException | IllegalAccessException e) {
                        throw new IllegalArgumentException(e);
                    }

                }
                i++;
            }
        } else {
            types = null;
            params = null;
            converters = null;
            itemConverters = null;
            objectConverters = null;
        }

        return constructor == null && factory == null ? delegate.findFactory(clazz) : (
                constructor != null ?
                        constructorFactory(finalConstructor, factoryValidator, types, params, converters, itemConverters, objectConverters) :
                        methodFactory(clazz, finalFactory, factoryValidator, types, params, converters, itemConverters, objectConverters));
    }

    private Factory methodFactory(final Class<?> clazz, final Method finalFactory,
                                  final Consumer<Object[]> factoryValidator, final Type[] types,
                                  final String[] params, final Adapter<?, ?>[] converters,
                                  final Adapter<?, ?>[] itemConverters, final ObjectConverter.Codec<?>[] objectConverters) {
        final Object instance = Modifier.isStatic(finalFactory.getModifiers()) ?
                null : tryToCreateInstance(finalFactory.getDeclaringClass());
        return new Factory() {
            @Override
            public Object create(final Object[] params) {
                factoryValidator.accept(params);
                try {
                    final Object invoke = finalFactory.invoke(instance, params);
                    if (!clazz.isInstance(invoke)) {
                        throw new IllegalArgumentException(invoke + " is not a " + clazz.getName());
                    }
                    return invoke;
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                } catch (final InvocationTargetException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }

            @Override
            public Type[] getParameterTypes() {
                return types;
            }

            @Override
            public String[] getParameterNames() {
                return params;
            }

            @Override
            public Adapter<?, ?>[] getParameterConverter() {
                return converters;
            }

            @Override
            public Adapter<?, ?>[] getParameterItemConverter() {
                return itemConverters;
            }

            @Override
            public ObjectConverter.Codec<?>[] getObjectConverter() {
                return objectConverters;
            }
        };
    }

    private Object tryToCreateInstance(final Class<?> declaringClass) {
        try {
            final Constructor<?> declaredConstructor = declaringClass.getDeclaredConstructor();
            if (!declaredConstructor.isAccessible()) {
                declaredConstructor.setAccessible(true);
            }
            return declaredConstructor.newInstance();
        } catch (final Exception e) {
            return null;
        }
    }

    private Factory constructorFactory(final Constructor<?> finalConstructor, final Consumer<Object[]> factoryValidator,
                                       final Type[] types, final String[] params, final Adapter<?, ?>[] converters,
                                       final Adapter<?, ?>[] itemConverters, final ObjectConverter.Codec<?>[] objectConverters) {
        return new Factory() {
            @Override
            public Object create(final Object[] params) {
                factoryValidator.accept(params);
                try {
                    return finalConstructor.newInstance(params);
                } catch (final InstantiationException | IllegalAccessException e) {
                    throw new IllegalStateException(e);
                } catch (final InvocationTargetException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }

            @Override
            public Type[] getParameterTypes() {
                return types;
            }

            @Override
            public String[] getParameterNames() {
                return params;
            }

            @Override
            public Adapter<?, ?>[] getParameterConverter() {
                return converters;
            }

            @Override
            public Adapter<?, ?>[] getParameterItemConverter() {
                return itemConverters;
            }

            @Override
            public ObjectConverter.Codec<?>[] getObjectConverter() {
                return objectConverters;
            }
        };
    }

    private void validateAnnotations(final Object parameter,
                                     final JsonbTypeAdapter adapter, final JsonbDateFormat dateFormat,
                                     final JsonbNumberFormat numberFormat,
                                     final JohnzonConverter johnzonConverter) {
        int notNull = adapter != null ? 1 : 0;
        notNull += dateFormat != null ? 1 : 0;
        notNull += numberFormat != null ? 1 : 0;
        notNull += johnzonConverter != null ? 1 : 0;
        if (notNull > 1) {
            throw new IllegalArgumentException("Conflicting @JsonbXXX/@JohnzonConverter on " + parameter);
        }
    }

    private Adapter<?, ?> toConverter(final Types types, final Type type,
                                      final JsonbTypeAdapter adapter, final JsonbDateFormat dateFormat,
                                      final JsonbNumberFormat numberFormat) {
        final Adapter converter;
        if (adapter != null) {
            final Class<? extends JsonbAdapter> value = adapter.value();
            final ParameterizedType pt = types.findParameterizedType(value, JsonbAdapter.class);
            final JohnzonAdapterFactory.Instance<? extends JsonbAdapter> instance = newInstance(value);
            toRelease.add(instance);
            final Type[] actualTypeArguments = pt.getActualTypeArguments();
            converter = new JohnzonJsonbAdapter(instance.getValue(), actualTypeArguments[0], actualTypeArguments[1]);
        } else if (dateFormat != null) { // TODO: support lists, LocalDate?
            if (Date.class == type) {
                converter = new ConverterAdapter<>(new JsonbDateConverter(dateFormat));
            } else if (LocalDateTime.class == type) {
                converter = new ConverterAdapter<>(new JsonbLocalDateTimeConverter(dateFormat));
            } else if (LocalDate.class == type) {
                converter = new ConverterAdapter<>(new JsonbLocalDateConverter(dateFormat));
            } else if (ZonedDateTime.class == type) {
                converter = new ConverterAdapter<>(new JsonbZonedDateTimeConverter(dateFormat));
            } else { // can happen if set on the class, todo: refine the checks
                converter = null; // todo: should we fallback on numberformat?
            }
        } else if (numberFormat != null) {  // TODO: support lists?
            converter = new ConverterAdapter<>(new JsonbNumberConverter(numberFormat));
        } else {
            converter = new ConverterAdapter<>(new JsonbValueConverter());
        }
        return converter;
    }

    private JohnzonAdapterFactory.Instance newInstance(final Class<?> value) {
        return factory.create(value);
    }

    @Override
    public Map<String, Reader> findReaders(final Class<?> clazz) {
        final Map<String, Reader> readers = delegate.findReaders(clazz);

        final Comparator<String> keyComparator = fieldComparator(clazz);
        final Map<String, Reader> result = keyComparator == null ? new HashMap<>() : new TreeMap<>(keyComparator);
        for (final Map.Entry<String, Reader> entry : readers.entrySet()) {
            final Reader initialReader = entry.getValue();
            if (isTransient(initialReader, visibility) || initialReader.getAnnotation(JohnzonAny.class) != null) {
                continue;
            }

            final Reader finalReader;
            if (FieldAndMethodAccessMode.CompositeDecoratedType.class.isInstance(initialReader)) { // unwrap to use the right reader
                final FieldAndMethodAccessMode.CompositeDecoratedType decoratedType = FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(initialReader);
                final DecoratedType type2 = decoratedType.getType2();
                if (MethodAccessMode.MethodReader.class.isInstance(type2)) {
                    finalReader = Reader.class.cast(type2);
                } else {
                    finalReader = initialReader;
                }
            } else {
                finalReader = initialReader;
            }

            // handle optionals since mapper is still only java 7
            final Type type;
            final Function<Object, Object> reader;
            if (isOptional(finalReader)) {
                type = ParameterizedType.class.cast(finalReader.getType()).getActualTypeArguments()[0];
                reader = i -> ofNullable(finalReader.read(i)).map(o -> Optional.class.cast(o).orElse(null)).orElse(null);
            } else if (OptionalInt.class == finalReader.getType()) {
                type = int.class;
                reader = i -> OptionalInt.class.cast(finalReader.read(i)).orElse(0);
            } else if (OptionalLong.class == finalReader.getType()) {
                type = long.class;
                reader = i -> OptionalLong.class.cast(finalReader.read(i)).orElse(0);
            } else if (OptionalDouble.class == finalReader.getType()) {
                type = double.class;
                reader = i -> OptionalDouble.class.cast(finalReader.read(i)).orElse(0);
            } else {
                type = finalReader.getType();
                reader = finalReader::read;
            }

            final WriterConverters writerConverters = new WriterConverters(initialReader, types);
            final JsonbProperty property = initialReader.getAnnotation(JsonbProperty.class);
            final JsonbNillable nillable = initialReader.getClassOrPackageAnnotation(JsonbNillable.class);
            final boolean isNillable = isNillable(property, nillable);
            final String key = property == null || property.value().isEmpty() ? naming.translateName(entry.getKey()) : property.value();
            if (result.put(key, new Reader() {
                @Override
                public Object read(final Object instance) {
                    return reader.apply(instance);
                }

                @Override
                public ObjectConverter.Writer<?> findObjectConverterWriter() {
                    return writerConverters.writer;
                }

                @Override
                public Type getType() {
                    return type;
                }

                @Override
                public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
                    return finalReader.getAnnotation(clazz);
                }

                @Override
                public <T extends Annotation> T getClassOrPackageAnnotation(final Class<T> clazz) {
                    return finalReader.getClassOrPackageAnnotation(clazz);
                }

                @Override
                public Adapter<?, ?> findConverter() {
                    return writerConverters.converter;
                }

                @Override
                public boolean isNillable(final boolean global) {
                    return isNillable;
                }
            }) != null) {
                throw new JsonbException("Ambiguous field " + key);
            }
        }
        return result;
    }

    @Override
    public Map<String, Writer> findWriters(final Class<?> clazz) {
        final Map<String, Writer> writers = delegate.findWriters(clazz);

        final Comparator<String> keyComparator = fieldComparator(clazz);
        final Map<String, Writer> result = keyComparator == null ? new HashMap<>() : new TreeMap<>(keyComparator);
        for (final Map.Entry<String, Writer> entry : writers.entrySet()) {
            Writer initialWriter = entry.getValue();
            if (isTransient(initialWriter, visibility)) {
                continue;
            }

            final Writer finalWriter;
            if (FieldAndMethodAccessMode.CompositeDecoratedType.class.isInstance(initialWriter)) { // unwrap to use the right reader
                final FieldAndMethodAccessMode.CompositeDecoratedType decoratedType = FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(initialWriter);
                final DecoratedType type2 = decoratedType.getType2();
                if (MethodAccessMode.MethodWriter.class.isInstance(type2)) {
                    finalWriter = Writer.class.cast(type2);
                } else {
                    finalWriter = initialWriter;
                }
            } else {
                finalWriter = initialWriter;
            }

            // handle optionals since mapper is still only java 7
            final Type type;
            final BiConsumer<Object, Object> writer;
            if (isOptional(initialWriter)) {
                type = ParameterizedType.class.cast(initialWriter.getType()).getActualTypeArguments()[0];
                writer = (i, val) -> finalWriter.write(i, Optional.ofNullable(val));
            } else if (OptionalInt.class == initialWriter.getType()) {
                type = int.class;
                writer = (i, val) -> finalWriter.write(i, OptionalInt.of(Number.class.cast(val).intValue()));
            } else if (OptionalLong.class == initialWriter.getType()) {
                type = long.class;
                writer = (i, val) -> finalWriter.write(i, OptionalLong.of(Number.class.cast(val).longValue()));
            } else if (OptionalDouble.class == initialWriter.getType()) {
                type = double.class;
                writer = (i, val) -> finalWriter.write(i, OptionalDouble.of(Number.class.cast(val).doubleValue()));
            } else {
                type = initialWriter.getType();
                writer = finalWriter::write;
            }

            final ReaderConverters converters = new ReaderConverters(initialWriter);
            final JsonbProperty property = initialWriter.getAnnotation(JsonbProperty.class);
            final JsonbNillable nillable = initialWriter.getClassOrPackageAnnotation(JsonbNillable.class);
            final boolean isNillable = isNillable(property, nillable);
            final String key = property == null || property.value().isEmpty() ? naming.translateName(entry.getKey()) : property.value();
            if (result.put(key, new Writer() {
                @Override
                public void write(final Object instance, final Object val) {
                    writer.accept(instance, val);
                }

                @Override
                public ObjectConverter.Reader<?> findObjectConverterReader() {
                    return converters.reader;
                }

                @Override
                public Type getType() {
                    return type;
                }

                @Override
                public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
                    return initialWriter.getAnnotation(clazz);
                }

                @Override
                public <T extends Annotation> T getClassOrPackageAnnotation(final Class<T> clazz) {
                    return initialWriter.getClassOrPackageAnnotation(clazz);
                }

                @Override
                public Adapter<?, ?> findConverter() {
                    return converters.converter;
                }

                @Override
                public boolean isNillable(final boolean global) {
                    return isNillable;
                }
            }) != null) {
                throw new JsonbException("Ambiguous field " + key);
            }
        }
        return result;
    }

    @Override
    public ObjectConverter.Reader<?> findReader(final Class<?> clazz) {
        return getClassEntry(clazz).readers.reader;
    }

    @Override
    public ObjectConverter.Writer<?> findWriter(final Class<?> clazz) {
        return getClassEntry(clazz).writers.writer;
    }

    @Override
    public Adapter<?, ?> findAdapter(final Class<?> clazz) { // TODO: find a way to not parse twice
        final Adapter<?, ?> converter = getClassEntry(clazz).readers.converter;
        if (converter != null && isReversedAdapter(clazz, converter.getClass(), converter)) {
            return new ReversedAdapter<>(converter);
        }
        return converter;
    }

    @Override
    public Method findAnyGetter(final Class<?> clazz) {
        return partialDelegate.findAnyGetter(clazz);
    }

    @Override
    public Method findAnySetter(final Class<?> clazz) {
        return partialDelegate.findAnySetter(clazz);
    }

    @Override
    public void afterParsed(final Class<?> clazz) {
        parsingCache.remove(clazz);
        partialDelegate.afterParsed(clazz);
    }

    private boolean isReversedAdapter(final Class<?> payloadType, final Class<?> aClass, final Adapter<?, ?> instance) {
        if (TypeAwareAdapter.class.isInstance(instance)) {
            return !payloadType.isAssignableFrom(Class.class.cast(TypeAwareAdapter.class.cast(instance).getTo()))
                    && payloadType.isAssignableFrom(Class.class.cast(TypeAwareAdapter.class.cast(instance).getFrom()));
        }
        final Type[] genericInterfaces = aClass.getGenericInterfaces();
        return Stream.of(genericInterfaces).filter(ParameterizedType.class::isInstance)
                .filter(i -> Adapter.class.isAssignableFrom(Class.class.cast(ParameterizedType.class.cast(i).getRawType())))
                .findFirst()
                .map(pt -> {
                    final Type argument = ParameterizedType.class.cast(pt).getActualTypeArguments()[0];
                    return Class.class.isInstance(argument) && payloadType.isAssignableFrom(Class.class.cast(argument));
                })
                .orElseGet(() -> {
                    final Class<?> superclass = aClass.getSuperclass();
                    return superclass != Object.class && isReversedAdapter(payloadType, superclass, instance);
                });
    }

    private boolean isNillable(final JsonbProperty property, final JsonbNillable nillable) {
        if (property != null) {
            return property.nillable();
        }
        if (nillable != null) {
            return nillable.value();
        }
        return globalIsNillable;
    }

    private ParsingCacheEntry getClassEntry(final Class<?> clazz) {
        ParsingCacheEntry cache = parsingCache.get(clazz);
        if (cache == null) {
            cache = new ParsingCacheEntry(new ClassDecoratedType(clazz), types);
            parsingCache.putIfAbsent(clazz, cache);
        }
        return cache;
    }

    private boolean isOptional(final DecoratedType value) {
        return ParameterizedType.class.isInstance(value.getType()) && Optional.class == ParameterizedType.class.cast(value.getType()).getRawType();
    }

    private boolean isTransient(final DecoratedType dt, final PropertyVisibilityStrategy visibility) {
        if (!FieldAndMethodAccessMode.CompositeDecoratedType.class.isInstance(dt)) {
            return isTransient(dt) || shouldSkip(visibility, dt);
        }
        final FieldAndMethodAccessMode.CompositeDecoratedType cdt = FieldAndMethodAccessMode.CompositeDecoratedType.class.cast(dt);
        return isTransient(cdt.getType1()) || isTransient(cdt.getType2()) ||
                (shouldSkip(visibility, cdt.getType1()) && shouldSkip(visibility, cdt.getType2()));
    }

    private boolean shouldSkip(final PropertyVisibilityStrategy visibility, final DecoratedType t) {
        return isNotVisible(visibility, t);
    }

    private boolean isTransient(final DecoratedType t) {
        return t.getAnnotation(JsonbTransient.class) != null;
    }

    private boolean isNotVisible(PropertyVisibilityStrategy visibility, DecoratedType t) {
        return !(FieldAccessMode.FieldDecoratedType.class.isInstance(t) ?
                visibility.isVisible(FieldAccessMode.FieldDecoratedType.class.cast(t).getField())
                : (MethodAccessMode.MethodDecoratedType.class.isInstance(t) &&
                visibility.isVisible(MethodAccessMode.MethodDecoratedType.class.cast(t).getMethod())));
    }

    private Comparator<String> orderComparator(final Class<?> clazz) {
        final Comparator<String> keyComparator;
        final JsonbPropertyOrder orderAnnotation = Meta.getAnnotation(clazz, JsonbPropertyOrder.class);
        if (orderAnnotation != null) {
            final List<String> indexed = new ArrayList<>(asList(orderAnnotation.value()));
            if (naming != null) { // JsonbPropertyOrder applies on java names
                for (int i = 0; i < indexed.size(); i++) {
                    indexed.set(i, naming.translateName(indexed.get(i)));
                }
            }
            keyComparator = (o1, o2) -> {
                if (o1 != null && o1.equals(o2)) {
                    return 0;
                }
                final int i1 = indexed.indexOf(o1);
                final int i2 = indexed.indexOf(o2);
                if (i1 < 0) {
                    if (i2 < 0) {
                        if (order != null) {
                            switch (order) {
                                case PropertyOrderStrategy.LEXICOGRAPHICAL:
                                    return o1.compareTo(o2);
                                case PropertyOrderStrategy.REVERSE:
                                    return o2.compareTo(o1);
                                case PropertyOrderStrategy.ANY:
                                default:
                                    return 1;
                            }
                        }
                    }
                    return 1;
                }
                if (i2 < 0) {
                    return -1;
                }
                return i1 - i2;
            };
        } else if (order != null) {
            switch (order) {
                case PropertyOrderStrategy.ANY:
                    keyComparator = null;
                    break;
                case PropertyOrderStrategy.LEXICOGRAPHICAL:
                    keyComparator = String::compareTo;
                    break;
                case PropertyOrderStrategy.REVERSE:
                    keyComparator = Comparator.reverseOrder();
                    break;
                default:
                    keyComparator = null;
            }
        } else {
            keyComparator = null;
        }
        return keyComparator;
    }

    @Override
    public void close() throws IOException {
        toRelease.forEach(JohnzonAdapterFactory.Instance::release);
        if (Closeable.class.isInstance(delegate)) {
            Closeable.class.cast(delegate).close();
        }
        toRelease.clear();
    }

    // belongs to Meta but java 8
    private static <T extends Annotation> T getAnnotation(final Parameter param, final Class<T> api) {
        final T annotation = param.getAnnotation(api);
        if (annotation != null) {
            return annotation;
        }
        return Meta.findMeta(param.getAnnotations(), api);
    }

    private class ReaderConverters {
        private Adapter<?, ?> converter;
        private ObjectConverter.Reader reader;

        ReaderConverters(final DecoratedType annotationHolder) {
            final boolean numberType = isNumberType(annotationHolder.getType());
            final boolean dateType = isDateType(annotationHolder.getType());
            final JsonbTypeDeserializer deserializer = annotationHolder.getAnnotation(JsonbTypeDeserializer.class);
            final JsonbTypeAdapter adapter = annotationHolder.getAnnotation(JsonbTypeAdapter.class);
            JsonbDateFormat dateFormat = dateType ? annotationHolder.getAnnotation(JsonbDateFormat.class) : null;
            JsonbNumberFormat numberFormat = numberType ? annotationHolder.getAnnotation(JsonbNumberFormat.class) : null;
            final JohnzonConverter johnzonConverter = annotationHolder.getAnnotation(JohnzonConverter.class);
            validateAnnotations(annotationHolder, adapter, dateFormat, numberFormat, johnzonConverter);
            if (dateFormat == null && dateType) {
                dateFormat = annotationHolder.getClassOrPackageAnnotation(JsonbDateFormat.class);
            }
            if (numberFormat == null && numberType) {
                numberFormat = annotationHolder.getClassOrPackageAnnotation(JsonbNumberFormat.class);
            }

            converter = adapter == null && dateFormat == null && numberFormat == null && johnzonConverter == null ?
                    defaultConverters.get(new AdapterKey(annotationHolder.getType(), String.class)) :
                    toConverter(types, annotationHolder.getType(), adapter, dateFormat, numberFormat);

            if (deserializer != null) {
                final Class<? extends JsonbDeserializer> value = deserializer.value();
                final JohnzonAdapterFactory.Instance<? extends JsonbDeserializer> instance = newInstance(value);
                final ParameterizedType pt = types.findParameterizedType(value, JsonbDeserializer.class);
                final Class<?> mappedType = types.findParamType(pt, JsonbDeserializer.class);
                toRelease.add(instance);
                final JsonBuilderFactory builderFactoryInstance = builderFactory.get();
                reader = new ObjectConverter.Reader() {
                    private final ConcurrentMap<Type, BiFunction<JsonValue, MappingParser, Object>> impl =
                            new ConcurrentHashMap<>();

                    @Override
                    public Object fromJson(final JsonValue value,
                                           final Type targetType,
                                           final MappingParser parser) {
                        final JsonbDeserializer jsonbDeserializer = instance.getValue();
                        if (targetType == mappedType) { // fast test and matches most cases
                            return mapItem(value, targetType, parser, jsonbDeserializer);
                        }

                        BiFunction<JsonValue, MappingParser, Object> fn = impl.get(targetType);
                        if (fn == null) {
                            if (value.getValueType() == JsonValue.ValueType.ARRAY) {
                                if (ParameterizedType.class.isInstance(targetType)) {
                                    final ParameterizedType parameterizedType = ParameterizedType.class.cast(targetType);
                                    final Class<?> paramType = types.findParamType(parameterizedType, Collection.class);
                                    if (paramType != null && (mappedType == null /*Object*/ || mappedType.isAssignableFrom(paramType))) {
                                        final Collector<Object, ?, ? extends Collection<Object>> collector =
                                                Set.class.isAssignableFrom(
                                                        types.asClass(parameterizedType.getRawType())) ? toSet() : toList();
                                        fn = (json, mp) -> json.asJsonArray().stream()
                                               .map(i -> mapItem(i, paramType, mp, jsonbDeserializer))
                                               .collect(collector);
                                    }
                                }
                            }
                            if (fn == null) {
                                fn = (json, mp) -> mapItem(json, targetType, mp, jsonbDeserializer);
                            }
                            impl.putIfAbsent(targetType, fn);
                        }
                        return fn.apply(value, parser);
                    }

                    private Object mapItem(final JsonValue jsonValue, final Type targetType,
                                           final MappingParser parser, final JsonbDeserializer jsonbDeserializer) {
                        return jsonbDeserializer.deserialize(
                                JsonValueParserAdapter.createFor(jsonValue, parserFactory),
                                new JohnzonDeserializationContext(parser, builderFactoryInstance, jsonProvider),
                                targetType);
                    }
                };
            } else if (johnzonConverter != null) {
                try {
                    MapperConverter mapperConverter = johnzonConverter.value().newInstance();
                    if (mapperConverter instanceof Converter) {
                        converter = new ConverterAdapter<>((Converter) mapperConverter);
                    } else if (mapperConverter instanceof ObjectConverter.Reader) {
                        reader = (ObjectConverter.Reader) mapperConverter;
                    }
                } catch (final InstantiationException | IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        }
    }

    private class WriterConverters {
        private Adapter<?, ?> converter;
        private ObjectConverter.Writer writer;

        WriterConverters(final DecoratedType reader, final Types types) {
            final boolean numberType = isNumberType(reader.getType());
            final boolean dateType = isDateType(reader.getType());
            final JsonbTypeSerializer serializer = reader.getAnnotation(JsonbTypeSerializer.class);
            final JsonbTypeAdapter adapter = reader.getAnnotation(JsonbTypeAdapter.class);
            JsonbDateFormat dateFormat = dateType ? reader.getAnnotation(JsonbDateFormat.class) : null;
            JsonbNumberFormat numberFormat = numberType ? reader.getAnnotation(JsonbNumberFormat.class) : null;
            final JohnzonConverter johnzonConverter = reader.getAnnotation(JohnzonConverter.class);
            validateAnnotations(reader, adapter, dateFormat, numberFormat, johnzonConverter);
            if (dateFormat == null && isDateType(reader.getType())) {
                dateFormat = reader.getClassOrPackageAnnotation(JsonbDateFormat.class);
            }
            if (numberFormat == null && isNumberType(reader.getType())) {
                numberFormat = reader.getClassOrPackageAnnotation(JsonbNumberFormat.class);
            }

            converter = adapter == null && dateFormat == null && numberFormat == null && johnzonConverter == null ?
                    defaultConverters.get(new AdapterKey(reader.getType(), String.class)) :
                    toConverter(types, reader.getType(), adapter, dateFormat, numberFormat);

            if (serializer != null) {
                final Class<? extends JsonbSerializer> value = serializer.value();
                final JohnzonAdapterFactory.Instance<? extends JsonbSerializer> instance = newInstance(value);
                toRelease.add(instance);
                writer = (instance1, jsonbGenerator) ->
                        instance.getValue().serialize(instance1, jsonbGenerator.getJsonGenerator(), new JohnzonSerializationContext(jsonbGenerator));
            } else if (johnzonConverter != null) {
                try {
                    MapperConverter mapperConverter = johnzonConverter.value().newInstance();
                    if (mapperConverter instanceof Converter) {
                        converter = new ConverterAdapter<>((Converter) mapperConverter) ;
                    } else if (mapperConverter instanceof ObjectConverter.Writer) {
                        writer = (ObjectConverter.Writer) mapperConverter;
                    }
                } catch (final InstantiationException | IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        }
    }

    private boolean isDateType(final Type type) {
        if (!Class.class.isInstance(type)) {
            return false;
        }
        final Class<?> clazz = Class.class.cast(type);
        return type.getTypeName().startsWith("java.time.") || Date.class == type || Calendar.class.isAssignableFrom(clazz);
    }

    private boolean isNumberType(final Type type) {
        if (!Class.class.isInstance(type)) {
            return false;
        }
        final Class<?> clazz = Class.class.cast(type);
        return Number.class.isAssignableFrom(clazz) || clazz.isPrimitive();
    }

    private static class ClassDecoratedType implements DecoratedType {
        private final Class<?> annotations;

        ClassDecoratedType(final Class<?> clazz) {
            this.annotations = clazz;
        }

        @Override
        public Type getType() {
            return annotations;
        }

        @Override
        public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
            return Meta.getAnnotation(annotations, clazz);
        }

        @Override
        public <T extends Annotation> T getClassOrPackageAnnotation(final Class<T> clazz) {
            return Meta.getAnnotation(clazz.getPackage(), clazz);
        }

        @Override
        public Adapter<?, ?> findConverter() {
            return null;
        }

        @Override
        public boolean isNillable(final boolean global) {
            return global;
        }
    }

    private class ParsingCacheEntry {
        private final ReaderConverters readers;
        private final WriterConverters writers;

        ParsingCacheEntry(final DecoratedType type, final Types types) {
            readers = new ReaderConverters(type);
            writers = new WriterConverters(type, types);
        }
    }
}
