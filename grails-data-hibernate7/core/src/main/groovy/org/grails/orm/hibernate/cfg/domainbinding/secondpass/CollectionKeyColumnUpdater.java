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
package org.grails.orm.hibernate.cfg.domainbinding.secondpass;

import java.util.Objects;

import org.hibernate.mapping.DependantValue;

import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernateToManyCollectionProperty;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernateToManyProperty;

/** Forces columns to be nullable and checks if the key is updatable. */
public class CollectionKeyColumnUpdater {

    private final CollectionKeyBinder collectionKeyBinder;

    /** Creates a new {@link CollectionKeyColumnUpdater} instance. */
    public CollectionKeyColumnUpdater(CollectionKeyBinder collectionKeyBinder) {
        this.collectionKeyBinder = collectionKeyBinder;
    }

    /** Creates the key, sets it on the collection, and updates its columns. */
    public void bind(HibernateToManyProperty property) {
        DependantValue key = collectionKeyBinder.bind(property);
        key.getColumns().stream().filter(Objects::nonNull).forEach(column -> column.setNullable(true));
        long unidirectionalCount = property.getHibernateOwner()
                .getPersistentPropertiesToBind()
                .stream()
                .filter(HibernateToManyProperty.class::isInstance)
                .map(HibernateToManyProperty.class::cast)
                .filter(p -> !p.isBidirectional())
                .count();

        // Hibernate writes no rows for a collection whose key is not updatable. So the key stays updatable for
        // - collections of basic values or enums (HibernateToManyCollectionProperty): they have no inverse side;
        // - collections stored in a join table (a unidirectional one-to-many, a many-to-many, a Map): the key is a
        //   column of the join table that only this collection writes (docbee: the elements of every unidirectional
        //   hasMany were lost once the owner had a second one; Hibernate 5's GORM stored them).
        // A collection bound with a foreign key in the element's table keeps the existing rule for issue 10811: its
        // key is non-updatable when the owner has two or more unidirectional to-many properties.
        key.setUpdateable(property instanceof HibernateToManyCollectionProperty ||
                !property.shouldBindWithForeignKey() ||
                unidirectionalCount <= 1);
    }

}
