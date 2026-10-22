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
package org.grails.orm.hibernate.query;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaSubQuery;

import org.springframework.core.convert.ConversionService;

import grails.gorm.DetachedCriteria;
import org.grails.datastore.mapping.query.Query;
import org.grails.datastore.mapping.query.api.QueryableCriteria;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.GrailsHibernatePersistentEntity;

/**
 * The {@link CriteriaHandlerContext} of a query translated by {@link JpaCriteriaQueryCreator}.
 *
 * @since 8.0
 */
final class JpaCriteriaHandlerContext implements CriteriaHandlerContext {

    private final HibernateCriteriaBuilder criteriaBuilder;
    private final ConversionService conversionService;
    private final AbstractQuery<?> query;
    private final From<?, ?> root;
    private final JpaQueryContext context;
    private final GrailsHibernatePersistentEntity entity;

    JpaCriteriaHandlerContext(HibernateCriteriaBuilder criteriaBuilder, ConversionService conversionService,
            AbstractQuery<?> query, From<?, ?> root, JpaQueryContext context, GrailsHibernatePersistentEntity entity) {
        this.criteriaBuilder = criteriaBuilder;
        this.conversionService = conversionService;
        this.query = query;
        this.root = root != null ? root : context.getRoot();
        this.context = context;
        this.entity = entity;
    }

    @Override
    public HibernateCriteriaBuilder getCriteriaBuilder() {
        return criteriaBuilder;
    }

    @Override
    public AbstractQuery<?> getQuery() {
        return query;
    }

    @Override
    public From<?, ?> getRoot() {
        return root;
    }

    @Override
    public GrailsHibernatePersistentEntity getEntity() {
        return entity;
    }

    @Override
    public Expression<?> resolve(String property) {
        Expression<?> expression = context.getFullyQualifiedExpression(property);
        if (expression == null) {
            throw new IllegalArgumentException("Cannot resolve property [" + property + "] of the criteria query" +
                    (entity != null ? " of class [" + entity.getJavaClass().getName() + "]" : ""));
        }
        return expression;
    }

    @Override
    public From<?, ?> getJoin(String aliasOrPath) {
        From<?, ?> join = context.getFrom(aliasOrPath);
        if (join == null && context.hasAlias(aliasOrPath) &&
                context.getFullyQualifiedExpression(aliasOrPath) instanceof From<?, ?> aliasJoin) {
            join = aliasJoin;
        }
        return join;
    }

    @Override
    public Predicate toPredicate(Query.Criterion criterion) {
        return new PredicateGenerator(criteriaBuilder, conversionService).handleCriterion(query, root, context, entity, criterion);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Subquery<T> subquery(QueryableCriteria<?> criteria, Class<T> resultType) {
        Query.ProjectionList projections = new Query.ProjectionList();
        for (Query.Projection projection : criteria.getProjections()) {
            projections.add(projection);
        }
        Subquery<T> subquery = query.subquery(resultType);
        var creator = new JpaCriteriaQueryCreator(projections, criteriaBuilder,
                (GrailsHibernatePersistentEntity) criteria.getPersistentEntity(), (DetachedCriteria) criteria, conversionService);
        creator.setParentContext(context);
        creator.populateSubquery((JpaSubQuery) subquery);
        return subquery;
    }

    @Override
    public <T> ParameterExpression<T> parameter(Class<T> type, T value) {
        ParameterExpression<T> parameter = criteriaBuilder.parameter(type);
        context.bindParameter(parameter, value);
        return parameter;
    }
}
