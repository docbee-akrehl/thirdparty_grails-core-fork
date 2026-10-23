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
package grails.gorm.tests.sequence

import java.util.concurrent.atomic.AtomicInteger

import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.id.enhanced.NoopOptimizer
import org.hibernate.id.enhanced.Optimizer
import org.hibernate.id.enhanced.PooledLoOptimizer
import org.hibernate.id.enhanced.PooledOptimizer
import org.hibernate.id.enhanced.SequenceStyleGenerator

import spock.lang.Specification
import spock.lang.Unroll

import grails.gorm.annotation.Entity
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * How {@code hibernate.grails.id.sequence.default_increment_size} sets the increment size and optimizer of
 * {@code id generator: 'sequence'} when the mapping gives no {@code increment_size}, and that without it the generator
 * keeps the GORM for Hibernate 7 default (increment 50, {@code pooled-lo}).
 */
class SequenceDefaultIncrementSizeSettingSpec extends Specification {

    static final String DEFAULT_INCREMENT_SIZE = 'hibernate.grails.id.sequence.default_increment_size'
    static final AtomicInteger DATABASES = new AtomicInteger()

    void "without the setting a sequence keeps the default of GORM for Hibernate 7: increment 50, pooled-lo"() {
        expect:
        withDatastore([:]) { HibernateDatastore datastore ->
            assertOptimizer(datastore, SeqDefaultAlpha, PooledLoOptimizer, 50)
            assertOptimizer(datastore, SeqDefaultMapped, PooledLoOptimizer, 5)
            assertOptimizer(datastore, SeqDefaultMappedOptimizer, PooledOptimizer, 50)
        }
    }

    @Unroll
    void "the setting given as #description draws one value per id without an optimizer"() {
        expect:
        withDatastore(settings) { HibernateDatastore datastore ->
            assertOptimizer(datastore, SeqDefaultAlpha, NoopOptimizer, 1)
        }

        where:
        description                     | settings
        'a number in a flat key'        | [(DEFAULT_INCREMENT_SIZE): 1]
        'a string in a flat key'        | [(DEFAULT_INCREMENT_SIZE): ' 1 ']
        'a number in nested maps'       | [hibernate: [grails: [id: [sequence: [default_increment_size: 1]]]]]
    }

    void "a setting above one is the increment size of a pooled-lo optimizer"() {
        expect:
        withDatastore([(DEFAULT_INCREMENT_SIZE): '20']) { HibernateDatastore datastore ->
            assertOptimizer(datastore, SeqDefaultAlpha, PooledLoOptimizer, 20)
        }
    }

    void "the mapping wins: its increment_size over the setting, its optimizer together with the setting"() {
        expect:
        withDatastore([(DEFAULT_INCREMENT_SIZE): 20]) { HibernateDatastore datastore ->
            assertOptimizer(datastore, SeqDefaultMapped, PooledLoOptimizer, 5)
            assertOptimizer(datastore, SeqDefaultMappedOptimizer, PooledOptimizer, 20)
        }
    }

    @Unroll
    void "an invalid setting (#value) stops the start with a message naming the setting"() {
        when:
        withDatastore([(DEFAULT_INCREMENT_SIZE): value]) { HibernateDatastore datastore -> }

        then:
        Exception e = thrown()
        messages(e).any { it.contains(DEFAULT_INCREMENT_SIZE) && it.contains(String.valueOf(value)) }

        where:
        value << ['0', -1, 'fifty', '1.5']
    }

    private static void withDatastore(Map<String, Object> extraSettings, Closure<?> check) {
        Map<String, Object> settings = [
                (Settings.SETTING_DB_CREATE): 'create-drop',
                'dataSource.url'            : "jdbc:h2:mem:sequenceDefaultIncrementSizeSetting${DATABASES.incrementAndGet()};LOCK_TIMEOUT=10000".toString()
        ] as Map<String, Object>
        settings.putAll(extraSettings)
        HibernateDatastore datastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(settings),
                SeqDefaultAlpha, SeqDefaultMapped, SeqDefaultMappedOptimizer)
        try {
            check.call(datastore)
        } finally {
            datastore.close()
        }
    }

    private static void assertOptimizer(HibernateDatastore datastore, Class<?> domainClass,
                                        Class<? extends Optimizer> optimizerType, int incrementSize) {
        SessionFactoryImplementor sessionFactory = (SessionFactoryImplementor) datastore.sessionFactory
        SequenceStyleGenerator generator = (SequenceStyleGenerator) sessionFactory.mappingMetamodel
                .getEntityDescriptor(domainClass.name).generator
        assert optimizerType.isInstance(generator.optimizer)
        assert generator.optimizer.incrementSize == incrementSize
        assert generator.databaseStructure.incrementSize == incrementSize
    }

    private static List<String> messages(Throwable e) {
        List<String> messages = []
        for (Throwable t = e; t != null; t = (t.cause == t ? null : t.cause)) {
            messages << String.valueOf(t.message)
        }
        return messages
    }
}

@Entity
class SeqDefaultMappedOptimizer {
    String name

    static mapping = {
        table 'seq_default_mapped_optimizer'
        id generator: 'sequence', params: [sequence: 'seq_default_mapped_optimizer_seq', optimizer: 'pooled']
    }
}
