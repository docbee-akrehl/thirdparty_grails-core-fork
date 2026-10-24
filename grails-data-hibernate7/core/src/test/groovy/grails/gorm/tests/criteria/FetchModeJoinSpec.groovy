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

import org.hibernate.FetchMode
import org.hibernate.Hibernate

import grails.gorm.annotation.Entity
import grails.gorm.tests.HibernateGormDatastoreSpec

/**
 * {@code fetchMode(path, FetchMode.JOIN)} loads the association with the query, as Hibernate 5's
 * {@code Criteria#setFetchMode} did, also along a nested path.
 */
class FetchModeJoinSpec extends HibernateGormDatastoreSpec {

    void setupSpec() {
        manager.registerDomainClasses(FetchJoinDeal, FetchJoinCard, FetchJoinMaker)
    }

    void setup() {
        (1..2).each { int i ->
            new FetchJoinDeal(name: "deal $i",
                    buyer: new FetchJoinCard(name: "buyer $i", maker: new FetchJoinMaker(name: "buyer maker $i").save()).save(),
                    seller: new FetchJoinCard(name: "seller $i", maker: new FetchJoinMaker(name: "seller maker $i").save()).save())
                    .save(flush: true, failOnError: true)
        }
        FetchJoinDeal.withSession { it.clear() }
    }

    void "without fetchMode a to-one stays an uninitialized proxy"() {
        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {}

        then:
        deals.size() == 2
        deals.every { !Hibernate.isInitialized(it.buyer) }
    }

    void "fetchMode JOIN loads the to-one with the query"() {
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

    void "fetchMode JOIN on a nested path loads it along its parent's fetch"() {
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

    void "fetchMode JOIN on a nested path alone fetches its parent too"() {
        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('seller.maker', FetchMode.JOIN)
        }

        then:
        deals.every { Hibernate.isInitialized(it.seller) && Hibernate.isInitialized(it.seller.maker) }
        deals*.seller*.maker*.name.sort() == ['seller maker 1', 'seller maker 2']
    }

    void "fetchMode JOIN keeps one result per row and leaves a projection query unchanged"() {
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

    void "fetchMode JOIN next to an alias of the same path fetches it and keeps the alias' restriction"() {
        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            createAlias('buyer', 'b')
            fetchMode('buyer', FetchMode.JOIN)
            eq('b.name', 'buyer 2')
        }

        then:
        deals*.name == ['deal 2']
        Hibernate.isInitialized(deals[0].buyer)
    }

    void "fetchMode SELECT still loads the to-one lazily"() {
        when:
        List<FetchJoinDeal> deals = FetchJoinDeal.createCriteria().list {
            fetchMode('buyer', FetchMode.SELECT)
        }

        then:
        deals.every { !Hibernate.isInitialized(it.buyer) }
    }
}

@Entity
class FetchJoinDeal {
    String name
    FetchJoinCard buyer
    FetchJoinCard seller
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
