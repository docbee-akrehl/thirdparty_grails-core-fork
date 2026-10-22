/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.orm.hibernate.query

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import spock.lang.Specification

import grails.gorm.tests.criteria.ServiceLoadedOrder
import org.grails.datastore.mapping.query.Query

/**
 * The {@link CriteriaHandlerProvider} and {@link PredicateGenerator.CriterionHandlerProvider} services are loaded
 * once, race-free: a thread that runs its first query while another thread is still loading them waits for the
 * handlers instead of falling through to the built-in handling. Besides the thread context class loader, the class
 * loader of the GORM classes is searched.
 */
class CriteriaHandlerProviderLoadingSpec extends Specification {

    void setup() {
        CriteriaHandlers.clear()
        BlockingCriteriaHandlerProvider.reset()
        BlockingCriterionHandlerProvider.reset()
    }

    void cleanup() {
        BlockingCriteriaHandlerProvider.release.countDown()
        BlockingCriterionHandlerProvider.release.countDown()
        CriteriaHandlers.clear()
    }

    void "a thread looking up a handler while another thread loads the CriteriaHandlerProvider services waits for them"() {
        given:
        ClassLoader loader = servicesLoader(CriteriaHandlerProvider, BlockingCriteriaHandlerProvider)
        AtomicReference<Object> first = new AtomicReference<>()
        AtomicReference<Object> second = new AtomicReference<>()
        Thread firstThread = thread(loader) { first.set(CriteriaHandlers.findOrderHandler(new BlockingProviderOrder('x'))) }
        Thread secondThread = thread(loader) { second.set(CriteriaHandlers.findOrderHandler(new BlockingProviderOrder('x'))) }

        when: 'the first thread is inside the provider, the second thread starts its lookup'
        firstThread.start()
        assert BlockingCriteriaHandlerProvider.loading.await(10, TimeUnit.SECONDS)
        secondThread.start()
        awaitWaitingOrDone(secondThread)
        BlockingCriteriaHandlerProvider.release.countDown()
        firstThread.join(10000)
        secondThread.join(10000)

        then: 'both threads find the handler'
        first.get() != null
        second.get() != null
        BlockingCriteriaHandlerProvider.calls.get() == 1
    }

    void "a thread translating a criterion while another thread loads the CriterionHandlerProvider services waits for them"() {
        given:
        ClassLoader loader = servicesLoader(PredicateGenerator.CriterionHandlerProvider, BlockingCriterionHandlerProvider)
        AtomicReference<Object> first = new AtomicReference<>()
        AtomicReference<Object> second = new AtomicReference<>()
        Thread firstThread = thread(loader) {
            PredicateGenerator.loadServiceProviders()
            first.set(PredicateGenerator.CUSTOM_HANDLERS.get(BlockingProviderCriterion))
        }
        Thread secondThread = thread(loader) {
            PredicateGenerator.loadServiceProviders()
            second.set(PredicateGenerator.CUSTOM_HANDLERS.get(BlockingProviderCriterion))
        }

        when:
        firstThread.start()
        assert BlockingCriterionHandlerProvider.loading.await(10, TimeUnit.SECONDS)
        secondThread.start()
        awaitWaitingOrDone(secondThread)
        BlockingCriterionHandlerProvider.release.countDown()
        firstThread.join(10000)
        secondThread.join(10000)

        then:
        first.get() != null
        second.get() != null
    }

    void "the services are also found through the class loader of the GORM classes"() {
        given: 'a thread context class loader that sees no service'
        ClassLoader empty = new URLClassLoader(new URL[0], (ClassLoader) null)
        AtomicReference<Object> handler = new AtomicReference<>()
        Thread lookup = thread(empty) { handler.set(CriteriaHandlers.findOrderHandler(new ServiceLoadedOrder('x'))) }

        when:
        lookup.start()
        lookup.join(10000)

        then: 'the provider listed in META-INF/services of the test class path is loaded'
        handler.get() != null
    }

    private static Thread thread(ClassLoader contextClassLoader, Closure body) {
        Thread thread = new Thread(body as Runnable)
        thread.contextClassLoader = contextClassLoader
        thread.daemon = true
        thread
    }

    /**
     * Waits until the thread is blocked or waiting (for the loading thread) or has finished (without waiting).
     */
    private static void awaitWaitingOrDone(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state in [Thread.State.NEW, Thread.State.RUNNABLE] && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
    }

    /**
     * A class loader that lists an extra service implementation in {@code META-INF/services}.
     */
    private static ClassLoader servicesLoader(Class<?> service, Class<?> implementation) {
        Path file = Files.createTempFile('services', '.txt')
        file.toFile().deleteOnExit()
        Files.writeString(file, implementation.name + '\n')
        URL url = file.toUri().toURL()
        String resource = 'META-INF/services/' + service.name
        new ClassLoader(CriteriaHandlerProviderLoadingSpec.classLoader) {
            @Override
            protected Enumeration<URL> findResources(String name) {
                name == resource ? Collections.enumeration([url]) : Collections.emptyEnumeration()
            }
        }
    }
}

class BlockingProviderOrder extends Query.Order {
    BlockingProviderOrder(String property) {
        super(property)
    }
}

class BlockingProviderCriterion implements Query.Criterion {
}

/**
 * A provider whose handlers are only returned once the test releases it, to keep the loading thread inside it.
 */
class BlockingCriteriaHandlerProvider implements CriteriaHandlerProvider {
    static CountDownLatch loading
    static CountDownLatch release
    static java.util.concurrent.atomic.AtomicInteger calls

    static void reset() {
        loading = new CountDownLatch(1)
        release = new CountDownLatch(1)
        calls = new java.util.concurrent.atomic.AtomicInteger()
    }

    @Override
    Map<Class<? extends Query.Order>, OrderHandler<?>> orderHandlers() {
        calls.incrementAndGet()
        loading.countDown()
        release.await(10, TimeUnit.SECONDS)
        [(BlockingProviderOrder): { CriteriaHandlerContext context, Query.Order order -> null } as OrderHandler]
    }
}

/**
 * A {@link PredicateGenerator.CriterionHandlerProvider} that blocks like {@link BlockingCriteriaHandlerProvider}.
 */
class BlockingCriterionHandlerProvider implements PredicateGenerator.CriterionHandlerProvider {
    static CountDownLatch loading
    static CountDownLatch release

    static void reset() {
        loading = new CountDownLatch(1)
        release = new CountDownLatch(1)
    }

    @Override
    Class<? extends Query.Criterion> criterionType() {
        loading.countDown()
        release.await(10, TimeUnit.SECONDS)
        BlockingProviderCriterion
    }

    @Override
    PredicateGenerator.CriterionHandler criterionHandler() {
        { query, root, cb, criterion -> null } as PredicateGenerator.CriterionHandler
    }
}
