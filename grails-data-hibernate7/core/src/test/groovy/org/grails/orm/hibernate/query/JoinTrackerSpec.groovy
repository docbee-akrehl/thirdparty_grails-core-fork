/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.orm.hibernate.query

import jakarta.persistence.criteria.From
import spock.lang.Specification

/**
 * Unit tests for JoinTracker.
 */
class JoinTrackerSpec extends Specification {

    def "test root and joins"() {
        given:
        def root = Mock(From)
        def tracker = new JoinTracker(root)
        def join = Mock(From)

        when:
        tracker.addJoin("face", join)

        then:
        tracker.getRoot() == root
        tracker.getJoin("face") == join
    }

    def "test parent delegation"() {
        given:
        def parentRoot = Mock(From)
        def parent = new JoinTracker(parentRoot)
        def subRoot = Mock(From)
        def child = new JoinTracker(parent, subRoot)
        
        def parentJoin = Mock(From)

        when:
        parent.addJoin("face", parentJoin)

        then:
        child.getJoin("face") == parentJoin
        child.getRoot() == subRoot
    }

    def "test child override join"() {
        given:
        def parentRoot = Mock(From)
        def parent = new JoinTracker(parentRoot)
        def subRoot = Mock(From)
        def child = new JoinTracker(parent, subRoot)
        
        def parentJoin = Mock(From)
        def childJoin = Mock(From)

        when:
        parent.addJoin("face", parentJoin)
        child.addJoin("face", childJoin)

        then:
        child.getJoin("face") == childJoin
        parent.getJoin("face") == parentJoin
    }

    def "a subquery tracker sees the aliases of the enclosing query but not its association paths"() {
        given:
        def parent = new JoinTracker(Mock(From))
        def aliasJoin = Mock(From)
        def pathJoin = Mock(From)
        parent.addJoin("c", aliasJoin)
        parent.addAssociationPathJoin("customer", pathJoin)
        def subquery = new JoinTracker(parent, Mock(From), true)
        def association = new JoinTracker(parent, Mock(From))

        expect:
        parent.getJoin("customer") == pathJoin
        parent.getLocalJoin("customer") == pathJoin
        subquery.getJoin("c") == aliasJoin
        subquery.getJoin("customer") == null

        and: 'a tracker that does not scope association paths, as of an association block, sees both'
        association.getJoin("c") == aliasJoin
        association.getJoin("customer") == pathJoin
    }

    def "a subquery tracker sees the aliases of every enclosing query but none of their association paths"() {
        given:
        def outer = new JoinTracker(Mock(From))
        def aliasJoin = Mock(From)
        outer.addJoin("c", aliasJoin)
        outer.addAssociationPathJoin("customer", Mock(From))
        def association = new JoinTracker(outer, Mock(From))
        association.addAssociationPathJoin("mandant", Mock(From))
        def subquery = new JoinTracker(association, Mock(From), true)
        def ownJoin = Mock(From)

        when:
        subquery.addAssociationPathJoin("customer", ownJoin)

        then:
        subquery.getJoin("c") == aliasJoin
        subquery.getJoin("customer") == ownJoin
        subquery.getJoin("mandant") == null
    }
}
