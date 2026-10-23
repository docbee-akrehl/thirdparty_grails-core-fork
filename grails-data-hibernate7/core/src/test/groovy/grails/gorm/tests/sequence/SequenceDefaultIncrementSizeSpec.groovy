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

import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.generator.Generator
import org.hibernate.id.enhanced.NoopOptimizer
import org.hibernate.id.enhanced.PooledLoOptimizer
import org.hibernate.id.enhanced.SequenceStyleGenerator

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.annotation.Entity
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * {@code hibernate.grails.id.sequence.default_increment_size: 1} makes {@code id generator: 'sequence'} draw one
 * sequence value per id without a pooled optimizer, as GORM for Hibernate 5 did.
 *
 * <p>The schema is set up the way the docbee backend sets it up: Liquibase creates the tables and the sequences, with
 * {@code INCREMENT BY 1}, only after the session factory has started. Hibernate's
 * {@code hibernate.id.sequence.increment_size_mismatch_strategy: fix} therefore finds no sequence to compare with and
 * cannot correct a pooled increment. Several domain classes draw their ids from the same sequence. With the
 * Hibernate 7 default of GORM (increment 50, {@code pooled-lo}) each of them reserves a block of 50 ids per
 * {@code nextval}, the blocks overlap because the sequence only advances by one, and the inserts collide.
 */
abstract class SequenceDefaultIncrementSizeSpec extends Specification {

    static final String DEFAULT_INCREMENT_SIZE = 'hibernate.grails.id.sequence.default_increment_size'

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [
                (Settings.SETTING_DB_CREATE)                             : 'none',
                'hibernate.id.sequence.increment_size_mismatch_strategy': 'fix',
                (DEFAULT_INCREMENT_SIZE)                                 : 1
        ] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(settings),
                SeqDefaultAlpha, SeqDefaultBeta, SeqDefaultMapped)

        // what Liquibase does in the backend, after the session factory started
        execute('create table seq_default_alpha (id bigint not null, version bigint not null, name varchar(255) not null, primary key (id))')
        execute('create table seq_default_beta (id bigint not null, version bigint not null, name varchar(255) not null, primary key (id))')
        execute('create sequence seq_default_shared start with 1 increment by 1')
    }

    void "two domain classes on one INCREMENT BY 1 sequence get unique ids, one nextval per insert"() {
        given:
        List<Long> alphaIds = []
        List<Long> betaIds = []

        when: 'more inserts than one pool of the Hibernate 7 default (50) would hold, alternating between both'
        60.times { int i ->
            SeqDefaultAlpha.withNewTransaction {
                alphaIds << new SeqDefaultAlpha(name: "alpha $i").save(flush: true, failOnError: true).id
            }
            SeqDefaultBeta.withNewTransaction {
                betaIds << new SeqDefaultBeta(name: "beta $i").save(flush: true, failOnError: true).id
            }
        }

        then: 'every insert drew the next sequence value, so the ids of both tables never overlap'
        alphaIds == (0..<60).collect { 2L * it + 1 }
        betaIds == (0..<60).collect { 2L * it + 2 }
        rowCount('seq_default_alpha') == 60
        rowCount('seq_default_beta') == 60

        and: 'the sequence advanced once per insert'
        nextSequenceValue('seq_default_shared') == 121L
    }

    void "a sequence mapped without increment_size draws one value per id without an optimizer"() {
        when:
        SequenceStyleGenerator alpha = sequenceGenerator(SeqDefaultAlpha)
        SequenceStyleGenerator beta = sequenceGenerator(SeqDefaultBeta)

        then:
        alpha.optimizer instanceof NoopOptimizer
        alpha.optimizer.incrementSize == 1
        alpha.databaseStructure.incrementSize == 1
        beta.optimizer instanceof NoopOptimizer
        beta.optimizer.incrementSize == 1
    }

    void "an increment_size in the mapping wins over the configured default"() {
        when:
        SequenceStyleGenerator mapped = sequenceGenerator(SeqDefaultMapped)

        then:
        mapped.optimizer instanceof PooledLoOptimizer
        mapped.optimizer.incrementSize == 5
        mapped.databaseStructure.incrementSize == 5
    }

    protected SequenceStyleGenerator sequenceGenerator(Class<?> domainClass) {
        SessionFactoryImplementor sessionFactory = (SessionFactoryImplementor) hibernateDatastore.sessionFactory
        Generator generator = sessionFactory.mappingMetamodel.getEntityDescriptor(domainClass.name).generator
        return (SequenceStyleGenerator) generator
    }

    protected long nextSequenceValue(String sequence) {
        SessionFactoryImplementor sessionFactory = (SessionFactoryImplementor) hibernateDatastore.sessionFactory
        String sql = sessionFactory.jdbcServices.dialect.sequenceSupport.getSequenceNextValString(sequence)
        return (long) query(sql)
    }

    protected long rowCount(String table) {
        return (long) query("select count(*) from $table")
    }

    private Object query(String sql) {
        hibernateDatastore.dataSource.connection.withCloseable { conn ->
            conn.createStatement().withCloseable { stmt ->
                stmt.executeQuery(sql).withCloseable { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    private void execute(String sql) {
        hibernateDatastore.dataSource.connection.withCloseable { conn ->
            conn.createStatement().withCloseable { stmt -> stmt.execute(sql) }
        }
    }
}

@Entity
class SeqDefaultAlpha {
    String name

    static mapping = {
        table 'seq_default_alpha'
        id generator: 'sequence', params: [sequence: 'seq_default_shared']
    }
}

@Entity
class SeqDefaultBeta {
    String name

    static mapping = {
        table 'seq_default_beta'
        id generator: 'sequence', params: [sequence: 'seq_default_shared']
    }
}

@Entity
class SeqDefaultMapped {
    String name

    static mapping = {
        table 'seq_default_mapped'
        id generator: 'sequence', params: [sequence: 'seq_default_mapped_seq', increment_size: 5]
    }
}
