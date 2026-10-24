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
package grails.gorm.tests.criteria

import jakarta.persistence.criteria.JoinType

import org.hibernate.FetchMode
import org.hibernate.Hibernate
import org.hibernate.resource.jdbc.spi.StatementInspector
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
 * {@code fetchMode(path, FetchMode.JOIN)} loads the association with the query, as Hibernate 5's
 * {@code Criteria#setFetchMode} did, also along a nested path, and an alias of a fetched path reuses the fetch.
 */
class FetchModeJoinSpec extends Specification {

    @Shared SqlCapture sqlCapture = new SqlCapture()

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore = new HibernateDatastore(
            DatastoreUtils.createPropertyResolver(
                    (Settings.SETTING_DB_CREATE): 'create-drop',
                    'hibernate.session_factory.statement_inspector': sqlCapture
            ),
            FetchJoinDeal, FetchJoinCard, FetchJoinMaker
    )
    @Shared PlatformTransactionManager transactionManager = hibernateDatastore.getTransactionManager()

    void setup() {
        sqlCapture.clear()
    }

    @Rollback
    void "without fetchMode a to-one stays an uninitialized proxy"() {
        given:
        seed()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {}

        then:
        deals.size() == 2
        deals.every { !Hibernate.isInitialized(it.buyer) }
    }

    @Rollback
    void "fetchMode JOIN loads the to-one with the query"() {
        given:
        seed()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('buyer', FetchMode.JOIN)
        }

        then:
        deals.size() == 2
        deals.every { Hibernate.isInitialized(it.buyer) }
        deals.every { !Hibernate.isInitialized(it.seller) }
        deals*.buyer*.name.sort() == ['buyer 1', 'buyer 2']
    }

    @Rollback
    void "fetchMode JOIN is a left join and keeps the rows without the association"() {
        given:
        seed()
        new FetchJoinDeal(name: 'deal without buyer').save(flush: true, failOnError: true)
        FetchJoinDeal.withSession { it.clear() }

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('buyer', FetchMode.JOIN)
            fetchMode('buyer.maker', FetchMode.JOIN)
        }

        then:
        deals*.name.sort() == ['deal 1', 'deal 2', 'deal without buyer']
        deals.find { it.name == 'deal without buyer' }.buyer == null
    }

    @Rollback
    void "fetchMode JOIN on a nested path loads it along its parent's fetch"() {
        given:
        seed()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('buyer', FetchMode.JOIN)
            fetchMode('buyer.maker', FetchMode.JOIN)
            fetchMode('seller', FetchMode.JOIN)
        }

        then:
        deals.every { Hibernate.isInitialized(it.buyer) && Hibernate.isInitialized(it.buyer.maker) }
        deals.every { Hibernate.isInitialized(it.seller) && !Hibernate.isInitialized(it.seller.maker) }
    }

    @Rollback
    void "fetchMode JOIN on a nested path alone fetches its parent too"() {
        given:
        seed()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('seller.maker', FetchMode.JOIN)
        }

        then:
        deals.every { Hibernate.isInitialized(it.seller) && Hibernate.isInitialized(it.seller.maker) }
        deals*.seller*.maker*.name.sort() == ['seller maker 1', 'seller maker 2']
    }

    @Rollback
    void "fetchMode JOIN keeps one result per row and leaves a projection query unchanged"() {
        given:
        seed()

        expect:
        FetchJoinDeal.createCriteria().listDistinct {
            fetchMode('buyer', FetchMode.JOIN)
            fetchMode('buyer.maker', FetchMode.JOIN)
            eq('name', 'deal 1')
        }*.buyer*.maker*.name == ['buyer maker 1']
        FetchJoinDeal.createCriteria().get {
            fetchMode('buyer', FetchMode.JOIN)
            projections {
                count('id')
            }
        } == 2L
    }

    @Rollback
    void "an alias of a fetched path with the same join type reuses the fetch instead of joining the path again"() {
        given:
        seed()
        sqlCapture.clear()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            createAlias('buyer', 'b', JoinType.LEFT)
            createAlias('b.maker', 'bm', JoinType.LEFT)
            fetchMode('buyer', FetchMode.JOIN)
            fetchMode('buyer.maker', FetchMode.JOIN)
            eq('bm.name', 'buyer maker 2')
        }

        then:
        deals*.name == ['deal 2']
        Hibernate.isInitialized(deals[0].buyer) && Hibernate.isInitialized(deals[0].buyer.maker)
        sqlCapture.statements.size() == 1
        joins(sqlCapture.statements[0], 'fetch_join_card') == 1
        joins(sqlCapture.statements[0], 'fetch_join_maker') == 1
    }

    @Rollback
    void "an alias of a fetched path with another join type joins the path on its own and keeps restricting"() {
        given:
        seed()
        sqlCapture.clear()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            createAlias('buyer', 'b', JoinType.INNER)
            fetchMode('buyer', FetchMode.JOIN)
            eq('b.name', 'buyer 1')
        }

        then:
        deals*.name == ['deal 1']
        Hibernate.isInitialized(deals[0].buyer)
        joins(sqlCapture.statements[0], 'fetch_join_card') == 2
    }

    @Rollback
    void "fetchMode SELECT still loads the to-one lazily"() {
        given:
        seed()

        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('buyer', FetchMode.SELECT)
        }

        then:
        deals.every { !Hibernate.isInitialized(it.buyer) }
    }

    private static void seed() {
        (1..2).each { int i ->
            new FetchJoinDeal(name: "deal $i",
                    buyer: new FetchJoinCard(name: "buyer $i", maker: new FetchJoinMaker(name: "buyer maker $i").save()).save(),
                    seller: new FetchJoinCard(name: "seller $i", maker: new FetchJoinMaker(name: "seller maker $i").save()).save())
                    .save(flush: true, failOnError: true)
        }
        FetchJoinDeal.withSession { it.clear() }
    }

    private static int joins(String sql, String table) {
        sql.toLowerCase().count(' join ' + table + ' ')
    }

    static class SqlCapture implements StatementInspector {

        final List<String> statements = Collections.synchronizedList([])

        @Override
        String inspect(String sql) {
            statements << sql
            sql
        }

        void clear() {
            statements.clear()
        }
    }
}

@Entity
class FetchJoinDeal {
    String name
    FetchJoinCard buyer
    FetchJoinCard seller

    static constraints = {
        buyer nullable: true
        seller nullable: true
    }
}

@Entity
class FetchJoinCard {
    String name
    FetchJoinMaker maker
}

@Entity
class FetchJoinMaker {
    String name
}
