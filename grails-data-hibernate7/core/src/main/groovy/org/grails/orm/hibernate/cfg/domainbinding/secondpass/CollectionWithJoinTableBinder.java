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

import jakarta.annotation.Nonnull;

import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Set;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.Table;

import org.grails.orm.hibernate.cfg.HibernateCompositeIdentity;
import org.grails.orm.hibernate.cfg.PersistentEntityNamingStrategy;
import org.grails.orm.hibernate.cfg.domainbinding.binder.CollectionForPropertyConfigBinder;
import org.grails.orm.hibernate.cfg.domainbinding.binder.CompositeIdentifierToManyToOneBinder;
import org.grails.orm.hibernate.cfg.domainbinding.binder.SimpleValueColumnBinder;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernateBasicProperty;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernateToManyProperty;

import static org.grails.orm.hibernate.cfg.domainbinding.binder.GrailsDomainBinder.EMPTY_PATH;

/** Binds a collection with a join table. */
@SuppressWarnings("PMD.DataflowAnomalyAnalysis")
public class CollectionWithJoinTableBinder {

    private final PersistentEntityNamingStrategy namingStrategy;
    private final UnidirectionalOneToManyInverseValuesBinder unidirectionalOneToManyInverseValuesBinder;
    private final CompositeIdentifierToManyToOneBinder compositeIdentifierToManyToOneBinder;
    private final CollectionForPropertyConfigBinder collectionForPropertyConfigBinder;
    private final SimpleValueColumnBinder simpleValueColumnBinder;
    private final BasicCollectionElementBinder basicCollectionElementBinder;

    /** Creates a new {@link CollectionWithJoinTableBinder} instance. */
    public CollectionWithJoinTableBinder(
            PersistentEntityNamingStrategy namingStrategy,
            UnidirectionalOneToManyInverseValuesBinder unidirectionalOneToManyInverseValuesBinder,
            CompositeIdentifierToManyToOneBinder compositeIdentifierToManyToOneBinder,
            CollectionForPropertyConfigBinder collectionForPropertyConfigBinder,
            SimpleValueColumnBinder simpleValueColumnBinder,
            BasicCollectionElementBinder basicCollectionElementBinder) {
        this.namingStrategy = namingStrategy;
        this.unidirectionalOneToManyInverseValuesBinder = unidirectionalOneToManyInverseValuesBinder;
        this.compositeIdentifierToManyToOneBinder = compositeIdentifierToManyToOneBinder;
        this.collectionForPropertyConfigBinder = collectionForPropertyConfigBinder;
        this.simpleValueColumnBinder = simpleValueColumnBinder;
        this.basicCollectionElementBinder = basicCollectionElementBinder;
    }

    /** Bind collection with join table. */
    public void bindCollectionWithJoinTable(@Nonnull HibernateToManyProperty property) {
        Collection collection = property.getCollection();
        collection.setInverse(false);
        SimpleValue element;
        if (property instanceof HibernateBasicProperty basic) {
            element = basicCollectionElementBinder.bind(basic);
        } else {
            element = unidirectionalOneToManyInverseValuesBinder.bind(property);
            final var domainClass = property.getHibernateAssociatedEntity();
            if (domainClass != null) {
                if (domainClass.getHibernateCompositeIdentity().isPresent()) {
                    HibernateCompositeIdentity ci =
                            domainClass.getHibernateCompositeIdentity().get();
                    compositeIdentifierToManyToOneBinder.bindCompositeIdentifierToManyToOne(
                            property, element, ci, domainClass, EMPTY_PATH);
                } else {
                    simpleValueColumnBinder.bindSimpleValue(
                            element, "long", property.resolveJoinTableForeignKeyColumnName(namingStrategy), true);
                }
            }
            if (collection instanceof Set && !sharesCollectionTable(property)) {
                bindElementColumnsNotNull(element);
            }
        }

        collection.setElement(element);
        collectionForPropertyConfigBinder.bindCollectionForPropertyConfig(property);
    }

    /**
     * Whether another to-many property of the owner is stored in the same table (two {@code hasMany} to the same class
     * without {@code mappedBy}, issue 10811). Each of them writes its own nullable key column next to the shared element
     * column, so the element column must not become part of a primary key, which would make each key column not null.
     */
    private boolean sharesCollectionTable(HibernateToManyProperty property) {
        Table table = property.getCollection().getCollectionTable();
        return property.getHibernateOwner().getPersistentPropertiesToBind().stream()
                .filter(HibernateToManyProperty.class::isInstance)
                .map(HibernateToManyProperty.class::cast)
                .filter(other -> other != property && other.getHibernateCollection() != null)
                .anyMatch(other -> other.getHibernateCollection().getCollectionTable() == table);
    }

    /**
     * Hibernate locates the join table row of a removed set element by the owner key and the non-nullable element
     * columns only; with a nullable element column {@code removeFrom} deleted every row of the owner (docbee: Hibernate
     * 5 also matched nullable element columns). A row always references an element, so its columns are not null, as
     * for a many-to-many and for the basic values Hibernate itself treats as non-null elements.
     */
    private void bindElementColumnsNotNull(SimpleValue element) {
        element.getSelectables().stream()
                .filter(Column.class::isInstance)
                .map(Column.class::cast)
                .forEach(column -> column.setNullable(false));
    }
}
