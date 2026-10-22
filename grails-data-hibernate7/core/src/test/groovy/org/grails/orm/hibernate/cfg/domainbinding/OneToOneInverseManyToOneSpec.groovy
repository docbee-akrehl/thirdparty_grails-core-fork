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
package org.grails.orm.hibernate.cfg.domainbinding

import java.sql.DatabaseMetaData
import java.sql.ResultSet

import org.springframework.transaction.PlatformTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * A single to-one whose target has exactly one back-reference to the owner is paired with that back-reference as
 * its inverse side, also when the back-reference is the many-to-one side of a {@code hasMany} of the owner. GORM
 * then models the to-one as a one-to-one whose inverse side is a many-to-one. Hibernate 5's GORM mapped it with a
 * foreign key column of its own, without a unique constraint; building the datastore must not fail.
 *
 * <pre>
 * O2OInvCustomer.defaultLocation  &lt;-&gt; O2OInvLocation.customer    (hasMany locations)
 * O2OInvTicket.createdStatusChange &lt;-&gt; O2OInvStatusChange.ticket (hasMany statusChanges, belongsTo ticket)
 * O2OInvTicket.urgency: a read-only to-one on the identifier column of the ticket table
 * </pre>
 */
abstract class OneToOneInverseManyToOneSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore
    @Shared PlatformTransactionManager transactionManager

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [(Settings.SETTING_DB_CREATE): 'create-drop'] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(
                DatastoreUtils.createPropertyResolver(settings),
                O2OInvCustomer, O2OInvLocation, O2OInvTicket, O2OInvStatusChange, O2OInvUrgency
        )
        transactionManager = hibernateDatastore.getTransactionManager()
    }

    void "GORM pairs the to-one with the many-to-one side of the hasMany"() {
        given:
        def mappingContext = hibernateDatastore.mappingContext
        def defaultLocation = mappingContext.getPersistentEntity(O2OInvCustomer.name).getPropertyByName('defaultLocation')
        def createdStatusChange = mappingContext.getPersistentEntity(O2OInvTicket.name).getPropertyByName('createdStatusChange')

        expect: 'the situation this specification is about'
        defaultLocation instanceof org.grails.datastore.mapping.model.types.OneToOne
        defaultLocation.inverseSide instanceof org.grails.datastore.mapping.model.types.ManyToOne
        createdStatusChange instanceof org.grails.datastore.mapping.model.types.OneToOne
        createdStatusChange.inverseSide instanceof org.grails.datastore.mapping.model.types.ManyToOne
    }

    void "each side has a foreign key column of its own, as with Hibernate 5"() {
        expect:
        columns('o2o_inv_customer').containsAll(['id', 'name', 'default_location_id'])
        columns('o2o_inv_location').containsAll(['id', 'name', 'customer_id'])
        columns('o2o_inv_ticket').containsAll(['id', 'title', 'created_status_change_id'])
        columns('o2o_inv_status_change').containsAll(['id', 'status', 'ticket_id'])
    }

    void "the foreign key column of the to-one is not unique"() {
        expect: 'the unique indexes are read (the primary keys are unique)'
        uniqueColumns('o2o_inv_customer').contains('id')
        uniqueColumns('o2o_inv_ticket').contains('id')

        and:
        uniqueColumns('o2o_inv_customer').disjoint(['default_location_id'])
        uniqueColumns('o2o_inv_location').disjoint(['customer_id'])
        uniqueColumns('o2o_inv_ticket').disjoint(['created_status_change_id'])
        uniqueColumns('o2o_inv_status_change').disjoint(['ticket_id'])
    }

    @Rollback
    void "a customer with locations and a default location is saved and loaded"() {
        given:
        def customer = new O2OInvCustomer(name: 'c').save(flush: true, failOnError: true)
        def first = new O2OInvLocation(name: 'l1', customer: customer).save(flush: true, failOnError: true)
        def second = new O2OInvLocation(name: 'l2', customer: customer).save(flush: true, failOnError: true)
        customer.addToLocations(first).addToLocations(second)
        customer.defaultLocation = second
        customer.save(flush: true, failOnError: true)
        hibernateDatastore.currentSession.clear()

        when:
        def loaded = O2OInvCustomer.get(customer.id)

        then:
        loaded.defaultLocation.name == 'l2'
        loaded.locations*.name.sort() == ['l1', 'l2']
        O2OInvLocation.findByName('l1').customer.id == customer.id
        O2OInvLocation.findByName('l2').customer.id == customer.id

        when: 'two customers share nothing, but each may point at its own location'
        def other = new O2OInvCustomer(name: 'o').save(flush: true, failOnError: true)
        def otherLocation = new O2OInvLocation(name: 'o1', customer: other).save(flush: true, failOnError: true)
        other.addToLocations(otherLocation)
        other.defaultLocation = otherLocation
        other.save(flush: true, failOnError: true)
        hibernateDatastore.currentSession.clear()

        then:
        O2OInvCustomer.get(other.id).defaultLocation.name == 'o1'
        O2OInvCustomer.get(customer.id).defaultLocation.name == 'l2'
    }

    @Rollback
    void "a customer without a default location is saved and loaded"() {
        given:
        def customer = new O2OInvCustomer(name: 'c').save(flush: true, failOnError: true)
        new O2OInvLocation(name: 'l1', customer: customer).save(flush: true, failOnError: true)
        hibernateDatastore.currentSession.clear()

        expect:
        O2OInvCustomer.get(customer.id).defaultLocation == null
        O2OInvCustomer.get(customer.id).locations*.name == ['l1']
    }

    @Rollback
    void "a ticket with status changes and a created status change is saved and loaded"() {
        given:
        def ticket = new O2OInvTicket(title: 't')
        def created = new O2OInvStatusChange(status: 'NEW')
        ticket.addToStatusChanges(created)
        ticket.addToStatusChanges(new O2OInvStatusChange(status: 'OPEN'))
        ticket.save(flush: true, failOnError: true)
        ticket.createdStatusChange = created
        ticket.save(flush: true, failOnError: true)
        hibernateDatastore.currentSession.clear()

        when:
        def loaded = O2OInvTicket.get(ticket.id)

        then:
        loaded.createdStatusChange.status == 'NEW'
        loaded.statusChanges*.status.sort() == ['NEW', 'OPEN']
        loaded.statusChanges.every { it.ticket.id == ticket.id }
    }

    @Rollback
    void "a read-only to-one on the identifier column does not duplicate the column"() {
        given:
        def ticket = new O2OInvTicket(title: 't').save(flush: true, failOnError: true)
        hibernateDatastore.currentSession.clear()

        when:
        def loaded = O2OInvTicket.get(ticket.id)
        loaded.title = 't2'
        loaded.save(flush: true, failOnError: true)
        hibernateDatastore.currentSession.clear()
        loaded = O2OInvTicket.get(ticket.id)

        then:
        loaded.title == 't2'
        loaded.urgency.id == ticket.id
        loaded.urgency.score == ticket.id * 2
    }

    private Set<String> columns(String table) {
        Set<String> names = [] as Set
        withMetaData { DatabaseMetaData metaData, String tableName ->
            ResultSet rs = metaData.getColumns(null, null, tableName, null)
            while (rs.next()) {
                names << rs.getString('COLUMN_NAME').toLowerCase()
            }
        }(table)
        names
    }

    private Set<String> uniqueColumns(String table) {
        Set<String> names = [] as Set
        withMetaData { DatabaseMetaData metaData, String tableName ->
            ResultSet rs = metaData.getIndexInfo(null, null, tableName, true, false)
            while (rs.next()) {
                String column = rs.getString('COLUMN_NAME')
                if (column) {
                    names << column.toLowerCase()
                }
            }
        }(table)
        names
    }

    private Closure withMetaData(Closure<?> reader) {
        return { String table ->
            O2OInvCustomer.withNewSession { session ->
                session.doWork { connection ->
                    DatabaseMetaData metaData = connection.metaData
                    String tableName = metaData.storesUpperCaseIdentifiers() ? table.toUpperCase() : table
                    reader.call(metaData, tableName)
                }
            }
        }
    }
}

@Entity
class O2OInvCustomer {
    String name
    O2OInvLocation defaultLocation

    static hasMany = [locations: O2OInvLocation]

    static constraints = {
        defaultLocation nullable: true
    }

    static mapping = {
        table 'o2o_inv_customer'
    }
}

@Entity
class O2OInvLocation {
    String name
    O2OInvCustomer customer

    static mapping = {
        table 'o2o_inv_location'
    }
}

@Entity
class O2OInvTicket {
    String title
    O2OInvStatusChange createdStatusChange
    O2OInvUrgency urgency

    static hasMany = [statusChanges: O2OInvStatusChange]

    static constraints = {
        createdStatusChange nullable: true
        urgency nullable: true
    }

    static mapping = {
        table 'o2o_inv_ticket'
        // the urgency reads the ticket's own row, through the identifier column
        urgency column: 'id', updateable: false, insertable: false, cascade: 'none', lazy: true
    }
}

@Entity
class O2OInvStatusChange {
    String status

    static belongsTo = [ticket: O2OInvTicket]

    static mapping = {
        table 'o2o_inv_status_change'
    }
}

@Entity
class O2OInvUrgency {
    Double score

    static constraints = {
        score nullable: true
    }

    static mapping = {
        table 'o2o_inv_ticket'
        version false
        id generator: 'assigned'
        score formula: 'id * 2'
    }
}
