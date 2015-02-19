/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.util;

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.nifi.annotation.behavior.TriggerSerially;
import org.apache.nifi.annotation.lifecycle.OnAdded;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.annotation.lifecycle.OnRemoved;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.QueueSize;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.provenance.ProvenanceReporter;
import org.apache.nifi.reporting.InitializationException;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StandardProcessorTestRunner implements TestRunner {

    private final Processor processor;
    private final MockProcessContext context;
    private final MockFlowFileQueue flowFileQueue;
    private final MockSessionFactory sessionFactory;
    private final SharedSessionState sharedState;
    private final AtomicLong idGenerator;
    private final boolean triggerSerially;

    private int numThreads = 1;
    private final AtomicInteger invocations = new AtomicInteger(0);

    private static final Logger logger = LoggerFactory.getLogger(StandardProcessorTestRunner.class);
    private static final Set<Class<? extends Annotation>> deprecatedTypeAnnotations = new HashSet<>();
    private static final Set<Class<? extends Annotation>> deprecatedMethodAnnotations = new HashSet<>();
    
    static {
        // do this in a separate method, just so that we can add a @SuppressWarnings annotation
        // because we want to indicate explicitly that we know that we are using deprecated
        // classes here.
        populateDeprecatedMethods();
    }
    
    StandardProcessorTestRunner(final Processor processor) {
        this.processor = processor;
        this.idGenerator = new AtomicLong(0L);
        this.sharedState = new SharedSessionState(processor, idGenerator);
        this.flowFileQueue = sharedState.getFlowFileQueue();
        this.sessionFactory = new MockSessionFactory(sharedState);
        this.context = new MockProcessContext(processor);

        detectDeprecatedAnnotations(processor);
        
        final MockProcessorInitializationContext mockInitContext = new MockProcessorInitializationContext(processor, context);
        processor.initialize(mockInitContext);

        try {
            ReflectionUtils.invokeMethodsWithAnnotation(OnAdded.class, processor);
        } catch (Exception e) {
            Assert.fail("Could not invoke methods annotated with @OnAdded annotation due to: " + e);
        }

        triggerSerially = null != processor.getClass().getAnnotation(TriggerSerially.class);
    }

    @SuppressWarnings("deprecation")
    private static void populateDeprecatedMethods() {
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.CapabilityDescription.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.EventDriven.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.SideEffectFree.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.SupportsBatching.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.Tags.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.TriggerWhenEmpty.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.TriggerWhenAnyDestinationAvailable.class);
        deprecatedTypeAnnotations.add(org.apache.nifi.processor.annotation.TriggerSerially.class);
        
        deprecatedMethodAnnotations.add(org.apache.nifi.processor.annotation.OnRemoved.class);
        deprecatedMethodAnnotations.add(org.apache.nifi.processor.annotation.OnAdded.class);
        deprecatedMethodAnnotations.add(org.apache.nifi.processor.annotation.OnScheduled.class);
        deprecatedMethodAnnotations.add(org.apache.nifi.processor.annotation.OnShutdown.class);
        deprecatedMethodAnnotations.add(org.apache.nifi.processor.annotation.OnStopped.class);
        deprecatedMethodAnnotations.add(org.apache.nifi.processor.annotation.OnUnscheduled.class);
    }
    
    private static void detectDeprecatedAnnotations(final Processor processor) {
        for ( final Class<? extends Annotation> annotationClass : deprecatedTypeAnnotations ) {
            if ( processor.getClass().isAnnotationPresent(annotationClass) ) {
                logger.warn("Processor is using deprecated Annotation " + annotationClass.getCanonicalName());
            }
        }
        
        for ( final Class<? extends Annotation> annotationClass : deprecatedMethodAnnotations ) {
            for ( final Method method : processor.getClass().getMethods() ) {
                if ( method.isAnnotationPresent(annotationClass) ) {
                    logger.warn("Processor is using deprecated Annotation " + annotationClass.getCanonicalName() + " for method " + method);
                }
            }
        }
        
    }
    
    @Override
    public void setValidateExpressionUsage(final boolean validate) {
        context.setValidateExpressionUsage(validate);
    }

    @Override
    public Processor getProcessor() {
        return processor;
    }

    @Override
    public MockProcessContext getProcessContext() {
        return context;
    }

    @Override
    public void run() {
        run(1);
    }

    @Override
    public void run(int iterations) {
        run(iterations, true);
    }

    @Override
    public void run(final int iterations, final boolean stopOnFinish) {
        run(iterations, stopOnFinish, true);
    }
    
    @Override
    public void run(final int iterations, final boolean stopOnFinish, final boolean initialize) {
        if (iterations < 1) {
            throw new IllegalArgumentException();
        }

        context.assertValid();
        context.enableExpressionValidation();
        try {
            if ( initialize ) {
                try {
                    ReflectionUtils.invokeMethodsWithAnnotation(OnScheduled.class, processor, context);
                } catch (Exception e) {
                    e.printStackTrace();
                    Assert.fail("Could not invoke methods annotated with @OnScheduled annotation due to: " + e);
                }
            }

            final ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
            @SuppressWarnings("unchecked")
            final Future<Throwable>[] futures = new Future[iterations];
            for (int i = 0; i < iterations; i++) {
                final Future<Throwable> future = executorService.submit(new RunProcessor());
                futures[i] = future;
            }

            executorService.shutdown();

            int finishedCount = 0;
            boolean unscheduledRun = false;
            for (final Future<Throwable> future : futures) {
                try {
                    final Throwable thrown = future.get();   // wait for the result
                    if (thrown != null) {
                        throw new AssertionError(thrown);
                    }

                    if (++finishedCount == 1) {
                        unscheduledRun = true;
                        try {
                            ReflectionUtils.invokeMethodsWithAnnotation(OnUnscheduled.class, processor, context);
                        } catch (Exception e) {
                            Assert.fail("Could not invoke methods annotated with @OnUnscheduled annotation due to: " + e);
                        }
                    }
                } catch (final Exception e) {
                }
            }

            if (!unscheduledRun) {
                try {
                    ReflectionUtils.invokeMethodsWithAnnotation(OnUnscheduled.class, processor, context);
                } catch (Exception e) {
                    Assert.fail("Could not invoke methods annotated with @OnUnscheduled annotation due to: " + e);
                }
            }

            if (stopOnFinish) {
                try {
                    ReflectionUtils.invokeMethodsWithAnnotation(OnStopped.class, processor);
                } catch (Exception e) {
                    Assert.fail("Could not invoke methods annotated with @OnStopped annotation due to: " + e);
                }
            }
        } finally {
            context.disableExpressionValidation();
        }
    }

    @Override
    public void shutdown() {
        try {
            ReflectionUtils.invokeMethodsWithAnnotation(OnShutdown.class, processor);
        } catch (Exception e) {
            Assert.fail("Could not invoke methods annotated with @OnShutdown annotation due to: " + e);
        }
    }

    private class RunProcessor implements Callable<Throwable> {

        @Override
        public Throwable call() throws Exception {
            invocations.incrementAndGet();
            try {
                processor.onTrigger(context, sessionFactory);
            } catch (final Throwable t) {
                return t;
            }

            return null;
        }
    }

    @Override
    public ProcessSessionFactory getProcessSessionFactory() {
        return sessionFactory;
    }

    @Override
    public void assertAllFlowFilesTransferred(final String relationship) {
        for (final MockProcessSession session : sessionFactory.getCreatedSessions()) {
            session.assertAllFlowFilesTransferred(relationship);
        }
    }

    @Override
    public void assertAllFlowFilesTransferred(final Relationship relationship) {
        for (final MockProcessSession session : sessionFactory.getCreatedSessions()) {
            session.assertAllFlowFilesTransferred(relationship);
        }
    }

    @Override
    public void assertAllFlowFilesTransferred(final String relationship, final int count) {
        assertAllFlowFilesTransferred(relationship);
        assertTransferCount(relationship, count);
    }

    @Override
    public void assertAllFlowFilesTransferred(final Relationship relationship, final int count) {
        assertAllFlowFilesTransferred(relationship);
        assertTransferCount(relationship, count);
    }

    @Override
    public void assertTransferCount(final Relationship relationship, final int count) {
        Assert.assertEquals(count, getFlowFilesForRelationship(relationship).size());
    }

    @Override
    public void assertTransferCount(final String relationship, final int count) {
        Assert.assertEquals(count, getFlowFilesForRelationship(relationship).size());
    }

    @Override
    public void assertValid() {
        context.assertValid();
    }

    @Override
    public void assertNotValid() {
        Assert.assertFalse("Processor appears to be valid but expected it to be invalid", context.isValid());
    }

    @Override
    public boolean isQueueEmpty() {
        return flowFileQueue.isEmpty();
    }

    @Override
    public void assertQueueEmpty() {
        Assert.assertTrue(flowFileQueue.isEmpty());
    }

    @Override
    public void assertQueueNotEmpty() {
        Assert.assertFalse(flowFileQueue.isEmpty());
    }

    @Override
    public void clearTransferState() {
        for (final MockProcessSession session : sessionFactory.getCreatedSessions()) {
            session.clearTransferState();
        }
    }

    @Override
    public void enqueue(final FlowFile... flowFiles) {
        for (final FlowFile flowFile : flowFiles) {
            flowFileQueue.offer((MockFlowFile) flowFile);
        }
    }

    @Override
    public void enqueue(final Path path) throws IOException {
        enqueue(path, new HashMap<String, String>());
    }

    @Override
    public void enqueue(final Path path, final Map<String, String> attributes) throws IOException {
        final Map<String, String> modifiedAttributes = new HashMap<>(attributes);
        if (!modifiedAttributes.containsKey(CoreAttributes.FILENAME.key())) {
            modifiedAttributes.put(CoreAttributes.FILENAME.key(), path.toFile().getName());
        }
        try (final InputStream in = Files.newInputStream(path)) {
            enqueue(in, modifiedAttributes);
        }
    }

    @Override
    public void enqueue(final byte[] data) {
        enqueue(data, new HashMap<String, String>());
    }

    @Override
    public void enqueue(final byte[] data, final Map<String, String> attributes) {
        enqueue(new ByteArrayInputStream(data), attributes);
    }

    @Override
    public void enqueue(final InputStream data) {
        enqueue(data, new HashMap<String, String>());
    }

    @Override
    public void enqueue(final InputStream data, final Map<String, String> attributes) {
        final MockProcessSession session = new MockProcessSession(new SharedSessionState(processor, idGenerator));
        MockFlowFile flowFile = session.create();
        flowFile = session.importFrom(data, flowFile);
        flowFile = session.putAllAttributes(flowFile, attributes);
        enqueue(flowFile);
    }

    @Override
    public byte[] getContentAsByteArray(final MockFlowFile flowFile) {
        return flowFile.getData();
    }

    @Override
    public List<MockFlowFile> getFlowFilesForRelationship(final String relationship) {
        final Relationship rel = new Relationship.Builder().name(relationship).build();
        return getFlowFilesForRelationship(rel);
    }

    @Override
    public List<MockFlowFile> getFlowFilesForRelationship(final Relationship relationship) {
        final List<MockFlowFile> flowFiles = new ArrayList<>();
        for (final MockProcessSession session : sessionFactory.getCreatedSessions()) {
            flowFiles.addAll(session.getFlowFilesForRelationship(relationship));
        }

        Collections.sort(flowFiles, new Comparator<MockFlowFile>() {
            @Override
            public int compare(final MockFlowFile o1, final MockFlowFile o2) {
                return Long.compare(o1.getCreationTime(), o2.getCreationTime());
            }
        });

        return flowFiles;
    }

    @Override
    public ProvenanceReporter getProvenanceReporter() {
        return sharedState.getProvenanceReporter();
    }

    @Override
    public QueueSize getQueueSize() {
        return flowFileQueue.size();
    }

    @Override
    public Long getCounterValue(final String name) {
        return sharedState.getCounterValue(name);
    }

    @Override
    public int getRemovedCount() {
        int count = 0;
        for (final MockProcessSession session : sessionFactory.getCreatedSessions()) {
            count += session.getRemovedCount();
        }

        return count;
    }

    @Override
    public void setAnnotationData(final String annotationData) {
        context.setAnnotationData(annotationData);
    }

    @Override
    public ValidationResult setProperty(final String propertyName, final String propertyValue) {
        return context.setProperty(propertyName, propertyValue);
    }

    @Override
    public ValidationResult setProperty(final PropertyDescriptor descriptor, final String value) {
        return context.setProperty(descriptor, value);
    }

    @Override
    public ValidationResult setProperty(final PropertyDescriptor descriptor, final AllowableValue value) {
        return context.setProperty(descriptor, value.getValue());
    }

    @Override
    public void setThreadCount(final int threadCount) {
        if (threadCount > 1 && triggerSerially) {
            Assert.fail("Cannot set thread-count higher than 1 because the processor is triggered serially");
        }

        this.numThreads = threadCount;
    }

    @Override
    public int getThreadCount() {
        return numThreads;
    }

    @Override
    public void setRelationshipAvailable(final Relationship relationship) {
        final Set<Relationship> unavailable = new HashSet<>(context.getUnavailableRelationships());
        unavailable.remove(relationship);
        context.setUnavailableRelationships(unavailable);
    }

    @Override
    public void setRelationshipAvailable(final String relationshipName) {
        setRelationshipAvailable(new Relationship.Builder().name(relationshipName).build());
    }

    @Override
    public void setRelationshipUnavailable(final Relationship relationship) {
        final Set<Relationship> unavailable = new HashSet<>(context.getUnavailableRelationships());
        unavailable.add(relationship);
        context.setUnavailableRelationships(unavailable);
    }

    @Override
    public void setRelationshipUnavailable(final String relationshipName) {
        setRelationshipUnavailable(new Relationship.Builder().name(relationshipName).build());
    }

    @Override
    public void addControllerService(final String identifier, final ControllerService service) throws InitializationException {
        addControllerService(identifier, service, new HashMap<String, String>());
    }

    @Override
    public void addControllerService(final String identifier, final ControllerService service, final Map<String, String> properties) throws InitializationException {
        // hold off on failing due to deprecated annotation for now... will introduce later.
//        for ( final Method method : service.getClass().getMethods() ) {
//            if ( method.isAnnotationPresent(org.apache.nifi.controller.annotation.OnConfigured.class) ) {
//                Assert.fail("Controller Service " + service + " is using deprecated Annotation " + org.apache.nifi.controller.annotation.OnConfigured.class + " for method " + method);
//            }
//        }
        
        final ComponentLog logger = new MockProcessorLog(identifier, service);
        final MockControllerServiceInitializationContext initContext = new MockControllerServiceInitializationContext(requireNonNull(service), requireNonNull(identifier), logger);
        service.initialize(initContext);

        final Map<PropertyDescriptor, String> resolvedProps = new HashMap<>();
        for (final Map.Entry<String, String> entry : properties.entrySet()) {
            resolvedProps.put(service.getPropertyDescriptor(entry.getKey()), entry.getValue());
        }

        try {
            ReflectionUtils.invokeMethodsWithAnnotation(OnAdded.class, service);
        } catch (final InvocationTargetException | IllegalAccessException | IllegalArgumentException e) {
            throw new InitializationException(e);
        }

        context.addControllerService(identifier, service, resolvedProps, null);
    }

    
    @Override
    public void assertNotValid(final ControllerService service) {
        final ValidationContext validationContext = new MockValidationContext(context).getControllerServiceValidationContext(service);
        final Collection<ValidationResult> results = context.getControllerService(service.getIdentifier()).validate(validationContext);
        
        for ( final ValidationResult result : results ) {
            if ( !result.isValid() ) {
                return;
            }
        }
        
        Assert.fail("Expected Controller Service " + service + " to be invalid but it is valid");
    }
    
    @Override
    public void assertValid(final ControllerService service) {
        final ValidationContext validationContext = new MockValidationContext(context).getControllerServiceValidationContext(service);
        final Collection<ValidationResult> results = context.getControllerService(service.getIdentifier()).validate(validationContext);
        
        for ( final ValidationResult result : results ) {
            if ( !result.isValid() ) {
                Assert.fail("Expected Controller Service to be valid but it is invalid due to: " + result.toString());
            }
        }
    }
    
    
    @Override
    public void disableControllerService(final ControllerService service) {
        final ControllerServiceConfiguration configuration = context.getConfiguration(service.getIdentifier());
        if ( configuration == null ) {
            throw new IllegalArgumentException("Controller Service " + service + " is not known");
        }
        
        if ( !configuration.isEnabled() ) {
            throw new IllegalStateException("Controller service " + service + " cannot be disabled because it is not enabled");
        }
        
        try {
            ReflectionUtils.invokeMethodsWithAnnotation(OnDisabled.class, service);
        } catch (final Exception e) {
            e.printStackTrace();
            Assert.fail("Failed to disable Controller Service " + service + " due to " + e);
        }
        
        configuration.setEnabled(false);
    }
    
    @Override
    public void enableControllerService(final ControllerService service) {
        final ControllerServiceConfiguration configuration = context.getConfiguration(service.getIdentifier());
        if ( configuration == null ) {
            throw new IllegalArgumentException("Controller Service " + service + " is not known");
        }
        
        if ( configuration.isEnabled() ) {
            throw new IllegalStateException("Cannot enable Controller Service " + service + " because it is not disabled");
        }
        
        try {
            final ConfigurationContext configContext = new MockConfigurationContext(configuration.getProperties(), context);
            ReflectionUtils.invokeMethodsWithAnnotation(OnEnabled.class, service, configContext);
        } catch (final InvocationTargetException ite) {
            ite.getCause().printStackTrace();
            Assert.fail("Failed to enable Controller Service " + service + " due to " + ite.getCause());
        } catch (final Exception e) {
            e.printStackTrace();
            Assert.fail("Failed to enable Controller Service " + service + " due to " + e);
        }

        configuration.setEnabled(true);        
    }
    
    @Override
    public boolean isControllerServiceEnabled(final ControllerService service) {
        final ControllerServiceConfiguration configuration = context.getConfiguration(service.getIdentifier());
        if ( configuration == null ) {
            throw new IllegalArgumentException("Controller Service " + service + " is not known");
        }

        return configuration.isEnabled();
    }
    
    @Override
    public void removeControllerService(final ControllerService service) {
        disableControllerService(service);
        
        try {
            ReflectionUtils.invokeMethodsWithAnnotation(OnRemoved.class, service);
        } catch (final Exception e) {
            e.printStackTrace();
            Assert.fail("Failed to remove Controller Service " + service + " due to " + e);
        }
        
        context.removeControllerService(service);
    }
    
    @Override
    public void setAnnotationData(final ControllerService service, final String annotationData) {
        final ControllerServiceConfiguration configuration = getConfigToUpdate(service);
        configuration.setAnnotationData(annotationData);
    }
    
    private ControllerServiceConfiguration getConfigToUpdate(final ControllerService service) {
        final ControllerServiceConfiguration configuration = context.getConfiguration(service.getIdentifier());
        if ( configuration == null ) {
            throw new IllegalArgumentException("Controller Service " + service + " is not known");
        }
        
        if ( configuration.isEnabled() ) {
            throw new IllegalStateException("Controller service " + service + " cannot be modified because it is not disabled");
        }
        
        return configuration;
    }
    
    @Override
    public ValidationResult setProperty(final ControllerService service, final PropertyDescriptor property, final AllowableValue value) {
        return setProperty(service, property, value.getValue());
    }
    
    @Override
    public ValidationResult setProperty(final ControllerService service, final PropertyDescriptor property, final String value) {
        final ControllerServiceConfiguration configuration = getConfigToUpdate(service);
        final Map<PropertyDescriptor, String> curProps = configuration.getProperties();
        final Map<PropertyDescriptor, String> updatedProps = new HashMap<>(curProps);
        
        final ValidationContext validationContext = new MockValidationContext(context).getControllerServiceValidationContext(service);
        final ValidationResult validationResult = property.validate(value, validationContext);
        
        updatedProps.put(property, value);
        configuration.setProperties(updatedProps);
        
        return validationResult;
    }
    
    @Override
    public ValidationResult setProperty(final ControllerService service, final String propertyName, final String value) {
        final PropertyDescriptor descriptor = service.getPropertyDescriptor(propertyName);
        if ( descriptor == null ) {
            return new ValidationResult.Builder()
                .input(propertyName)
                .explanation(propertyName + " is not a known Property for Controller Service " + service)
                .subject("Invalid property")
                .valid(false)
                .build();
        }
        return setProperty(service, descriptor, value);
    }
    
    
    @Override
    public ControllerService getControllerService(final String identifier) {
        return context.getControllerService(identifier);
    }

    @Override
    public <T extends ControllerService> T getControllerService(final String identifier, final Class<T> serviceType) {
        final ControllerService service = context.getControllerService(identifier);
        return serviceType.cast(service);
    }

    @Override
    public boolean removeProperty(PropertyDescriptor descriptor) {
        return context.removeProperty(descriptor);
    }

}
