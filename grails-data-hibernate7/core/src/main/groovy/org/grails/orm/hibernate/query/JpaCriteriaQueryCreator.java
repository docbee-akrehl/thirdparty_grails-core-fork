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
package org.grails.orm.hibernate.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.FetchParent;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Root;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaSubQuery;

import org.springframework.core.convert.ConversionService;

import grails.gorm.DetachedCriteria;
import org.grails.datastore.gorm.query.criteria.DetachedAssociationCriteria;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.query.Query;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.GrailsHibernatePersistentEntity;

/**
 * A class that creates a JPA {@link CriteriaQuery} from a GORM {@link Query} and {@link DetachedCriteria}.
 *
 * @since 8.0
 */
public class JpaCriteriaQueryCreator<T> {

    private final Query.ProjectionList projections;
    private final HibernateCriteriaBuilder criteriaBuilder;
    private final GrailsHibernatePersistentEntity entity;
    private final DetachedCriteria<?> detachedCriteria;
    private final ConversionService conversionService;
    private final HibernateQuery hibernateQuery;
    private JpaQueryContext parentContext;
    private JpaQueryContext context;

    public JpaCriteriaQueryCreator(
            Query.ProjectionList projections,
            CriteriaBuilder criteriaBuilder,
            PersistentEntity entity,
            DetachedCriteria<?> detachedCriteria,
            ConversionService conversionService) {
        this(projections, (HibernateCriteriaBuilder) criteriaBuilder, (GrailsHibernatePersistentEntity) entity, detachedCriteria, conversionService, null);
    }

    public JpaCriteriaQueryCreator(
            Query.ProjectionList projections,
            HibernateCriteriaBuilder criteriaBuilder,
            GrailsHibernatePersistentEntity entity,
            DetachedCriteria<?> detachedCriteria,
            ConversionService conversionService,
            HibernateQuery hibernateQuery) {
        this.projections = projections;
        this.criteriaBuilder = criteriaBuilder;
        this.entity = entity;
        this.detachedCriteria = detachedCriteria;
        this.conversionService = conversionService;
        this.hibernateQuery = hibernateQuery;
    }

    public void setParentContext(JpaQueryContext parentContext) {
        this.parentContext = parentContext;
    }

    /**
     * Returns the values of the criteria parameters in the query or subquery last built by this creator, which
     * must be bound to the query once it is created. A subquery built under a parent context records its values
     * in the parent's context, so they are bound with the query that contains it.
     *
     * @return the value of each parameter
     */
    public Map<ParameterExpression<?>, Object> getParameterValues() {
        return context != null ? context.getParameterValues() : Collections.emptyMap();
    }

    public JpaCriteriaQuery<?> createQuery() {
        var projectionList = collectProjections();
        var cq = createCriteriaQuery(projectionList);
        Class<?> javaClass = entity.getJavaClass();
        Root<?> root = cq.from(javaClass);
        
        List<HibernateAlias> aliases = collectAliases();
        context = JpaQueryContext.forSubquery(parentContext, aliases, root);
        registerDetachedJoins(context);
        discoverAliases(detachedCriteria.getCriteria(), context);
        joinAliases(aliases, context, cq, root);

        applyEagerFetchJoins(root, projectionList);

        var handlerContext = new JpaCriteriaHandlerContext(criteriaBuilder, conversionService, cq, root, context, entity);
        new JpaProjectionAdapter(criteriaBuilder, context, entity, handlerContext).adapt(projections, (AbstractQuery<?>) cq);
        assignGroupBy(cq, context);

        assignOrderBy(cq, context, handlerContext);
        assignCriteria(cq, root, context, entity);
        return cq;
    }

    /**
     * Eagerly fetch-join the associations requested via {@code fetch:[assoc:'join']} (which a
     * dynamic finder turns into {@link HibernateQuery#join(String)} calls). Hibernate 5 initializes
     * these collections; without an explicit JPA {@code root.fetch(...)} they would only be plain
     * joins (or, when not referenced in a predicate, not materialized at all), leaving the
     * association uninitialized. Only applied to full-entity selects - a fetch join on a projection
     * query (for example {@code count}) is invalid.
     */
    private void applyEagerFetchJoins(Root<?> root, List<Query.Projection> projectionList) {
        if (hibernateQuery == null) {
            return;
        }
        boolean entitySelect = projectionList.stream().noneMatch(p -> !(p instanceof Query.DistinctProjection));
        if (!entitySelect) {
            return;
        }
        Map<String, ?> joinTypes = detachedCriteria.getJoinTypes();
        // a nested path fetches along the fetches of its parents, e.g. rack.books from the fetch of rack
        Map<String, FetchParent<?, ?>> fetchByPath = new HashMap<>();
        for (String fetchPath : hibernateQuery.getFetchJoinPaths()) {
            if (fetchPath == null) {
                continue;
            }
            FetchParent<?, ?> parent = root;
            String prefix = null;
            try {
                for (String segment : fetchPath.split("\\.")) {
                    prefix = prefix == null ? segment : prefix + '.' + segment;
                    FetchParent<?, ?> current = parent;
                    Object configured = (joinTypes != null) ? joinTypes.get(prefix) : null;
                    JoinType joinType = (configured instanceof JoinType jt) ? jt : JoinType.LEFT;
                    parent = fetchByPath.computeIfAbsent(prefix, key -> current.fetch(segment, joinType));
                }
            }
            catch (IllegalArgumentException ignored) {
                // Not a fetchable association on this root; the plain join handling remains in place.
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void populateSubquery(JpaSubQuery<T> subquery) {
        var projectionList = collectProjections();
        Class<?> javaClass = entity.getJavaClass();
        Root<?> root = subquery.from(javaClass);
        
        List<HibernateAlias> aliases = collectAliases();
        context = JpaQueryContext.forSubquery(parentContext, aliases, root);
        registerDetachedJoins(context);
        discoverAliases(detachedCriteria.getCriteria(), context);
        joinAliases(aliases, context, subquery, root);

        var handlerContext = new JpaCriteriaHandlerContext(criteriaBuilder, conversionService, subquery, root, context, entity);
        new JpaProjectionAdapter(criteriaBuilder, context, entity, handlerContext).adapt(projections, (AbstractQuery<?>) subquery);

        assignGroupBy(subquery, context);

        assignCriteria(subquery, root, context, entity);
    }

    /**
     * Returns the aliases created with {@code createAlias} for the query: those of the {@link HibernateQuery}
     * and those added to the detached criteria.
     */
    private List<HibernateAlias> collectAliases() {
        List<HibernateAlias> aliases = new ArrayList<>();
        if (hibernateQuery != null) {
            aliases.addAll(hibernateQuery.getAliases());
        }
        for (Query.Criterion criterion : detachedCriteria.getCriteria()) {
            if (criterion instanceof HibernateAlias ha) {
                aliases.add(ha);
            }
        }
        return aliases;
    }

    /**
     * Joins every alias created with {@code createAlias}, as Hibernate 5's criteria did, whether or not a
     * restriction, projection or order refers to it: an inner join restricts the results to the rows having
     * the association, and a join of a collection repeats the row for each element. Joining them before the
     * criteria are translated also gives each alias the join type it was created with. The with-clause of an
     * alias becomes the {@code on} condition of its join, translated once all aliases are joined, so it can
     * refer to any of them.
     */
    private void joinAliases(List<HibernateAlias> aliases, JpaQueryContext context, AbstractQuery<?> query, Root<?> root) {
        for (HibernateAlias alias : aliases) {
            context.getFullyQualifiedExpression(alias.alias());
        }
        for (HibernateAlias alias : aliases) {
            if (alias.withClause() != null && context.getFrom(alias.alias()) instanceof Join<?, ?> join) {
                var predicateGenerator = new PredicateGenerator(criteriaBuilder, conversionService);
                join.on(predicateGenerator.handleCriterion(query, root, context, entity, alias.withClause()));
            }
        }
    }

    private List<Query.Projection> collectProjections() {
        return projections.getProjectionList();
    }

    private JpaCriteriaQuery<?> createCriteriaQuery(List<Query.Projection> projections) {
        List<Query.Projection> expressionProjections = projections.stream()
                .filter(p -> !(p instanceof Query.DistinctProjection) && !(p instanceof SqlGroupProjection))
                .toList();

        if (expressionProjections.size() > 1) {
            return (JpaCriteriaQuery<?>) criteriaBuilder.createTupleQuery();
        } else if (expressionProjections.isEmpty()) {
            return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(entity.getJavaClass());
        } else {
            var first = expressionProjections.get(0);
            ProjectionHandler<Query.Projection> handler = CriteriaHandlers.findProjectionHandler(first);
            if (handler != null) {
                return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(handler.resultType(first));
            } else if (first instanceof Query.CountProjection || first instanceof Query.CountDistinctProjection) {
                return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(Long.class);
            } else if (first instanceof Query.AvgProjection) {
                return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(Double.class);
            } else if (first instanceof Query.IdProjection) {
                var identity = entity.getIdentity();
                Class<?> projectionType = identity != null ? identity.getType() : Object.class;
                return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(projectionType);
            } else if (first instanceof Query.PropertyProjection propertyProjection) {
                return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(resolveProjectionType(propertyProjection));
            } else if (first instanceof SqlProjection sqlProjection) {
                return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(sqlProjection.getType());
            }

            return (JpaCriteriaQuery<?>) criteriaBuilder.createQuery(entity.getJavaClass());
        }
    }

    private void assignGroupBy(AbstractQuery<?> query, JpaQueryContext context) {
        var translator = new JpaProjectionTranslator(criteriaBuilder, context, entity);
        var groupByExpressions = projections.getProjectionList().stream()
                .map(projection -> {
                    if (projection instanceof Query.GroupPropertyProjection groupPropertyProjection) {
                        return context.getFullyQualifiedExpression(groupPropertyProjection.getPropertyName());
                    } else if (projection instanceof SqlGroupProjection sqlGroupProjection) {
                        // the column alias of a SQL projection does not reach the SQL, so a group by clause that
                        // names one, quoted or not, groups by the SQL it stands for; as in standard SQL, a name in
                        // double quotes is case sensitive, while backquotes (MySQL) and square brackets (SQL Server)
                        // leave a column alias case insensitive
                        String groupBy = sqlGroupProjection.getSql().trim();
                        String name = SqlProjection.unquote(groupBy);
                        boolean caseSensitive = groupBy.startsWith("\"") && !name.equals(groupBy);
                        SqlProjection aliased = findSqlProjection(name, !caseSensitive);
                        return translator.translateSql(aliased != null ? aliased.getSql() : sqlGroupProjection.getSql(), null);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toArray(Expression[]::new);
        if (groupByExpressions.length > 0) {
            query.groupBy(groupByExpressions);
        }
    }

    /**
     * Returns the SQL projection whose column alias is the given name, or {@code null}.
     */
    private SqlProjection findSqlProjection(String name, boolean ignoreCase) {
        for (Query.Projection projection : projections.getProjectionList()) {
            if (projection instanceof SqlProjection sqlProjection && sqlProjection.getColumnAlias() != null &&
                    (ignoreCase ? sqlProjection.getColumnAlias().equalsIgnoreCase(name) : sqlProjection.getColumnAlias().equals(name))) {
                return sqlProjection;
            }
        }
        return null;
    }

    private Class<?> resolveProjectionType(Query.PropertyProjection projection) {
        PersistentEntity persistentEntity = entity.getMappingContext().getPersistentEntity(entity.getJavaClass().getName());
        String propertyName = projection.getPropertyName();
        if (propertyName.contains(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)) {
            propertyName = propertyName.split(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR, 2)[1];
        }

        var property = persistentEntity.getPropertyByName(propertyName);
        if (property == null) {
            return Object.class;
        }
        return property.getType();
    }

    @SuppressWarnings("unchecked")
    private void assignOrderBy(CriteriaQuery<?> cq, JpaQueryContext context, CriteriaHandlerContext handlerContext) {
        List<Query.Order> orders = detachedCriteria.getOrders();
        if (!orders.isEmpty()) {
            var jpaOrders = orders.stream()
                    .map(order -> {
                        OrderHandler<Query.Order> handler = CriteriaHandlers.findOrderHandler(order);
                        if (handler != null) {
                            return handler.handle(handlerContext, order);
                        }
                        var propertyName = order.getProperty();
                        SqlProjection sqlProjection = findSqlProjection(propertyName, false);
                        Expression<?> expression = sqlProjection != null ?
                                context.getSelectionAlias(sqlProjection.getColumnAlias()) :
                                context.getFullyQualifiedExpression(propertyName);
                        if (order.isIgnoreCase() && expression.getJavaType().equals(String.class)) {
                            expression = criteriaBuilder.lower((Expression<String>) expression);
                        }
                        Nulls nulls = order instanceof HibernateOrder hibernateOrder ? hibernateOrder.getNullPrecedence() : Nulls.NONE;
                        return order.getDirection().equals(Query.Order.Direction.ASC) ?
                                criteriaBuilder.asc(expression, nulls) :
                                criteriaBuilder.desc(expression, nulls);
                    })
                    .toArray(jakarta.persistence.criteria.Order[]::new);
            cq.orderBy(jpaOrders);
        }
    }

    private void discoverAliases(List<Query.Criterion> criteria, JpaQueryContext context) {
        if (criteria == null) return;
        for (Query.Criterion criterion : criteria) {
            if (criterion instanceof HibernateAlias ha) {
                // If the alias is already defined in parent and materialized, just link it
                if (!context.hasAlias(ha.alias())) {
                    context.registerAlias(ha.alias(), ha);
                }
            } else if (criterion instanceof DetachedAssociationCriteria<?> dac) {
                if (dac.getAlias() != null) {
                    context.registerAlias(dac.getAlias(), new HibernateAlias(dac.getAssociationPath(), dac.getAlias()));
                }
                discoverAliases(dac.getCriteria(), context);
            } else if (criterion instanceof Query.PropertyNameCriterion pnc) {
                String propertyName = pnc.getProperty();
                if (propertyName.contains(".")) {
                    String alias = propertyName.substring(0, propertyName.indexOf("."));
                    // Only register if not already known in this context or as an explicit alias of an enclosing
                    // query: the implicit alias of an enclosing query is not shared with a subquery
                    if (context.needsImplicitAlias(alias)) {
                        context.registerImplicitAlias(alias);
                    }
                }
            } else if (criterion instanceof Query.Junction junction) {
                discoverAliases(junction.getCriteria(), context);
            }
        }
    }

    private void registerDetachedJoins(JpaQueryContext context) {
        detachedCriteria.getJoinTypes().forEach((path, joinType) ->
                context.registerAlias(path, new HibernateAlias(path, path, joinType))
        );
    }

    private void assignCriteria(
            AbstractQuery<?> cq, Root<?> root, JpaQueryContext context, GrailsHibernatePersistentEntity entity) {
        List<Query.Criterion> criteriaList = detachedCriteria.getCriteria();
        if (!criteriaList.isEmpty()) {
            discoverAliases(criteriaList, context);
            var predicateGenerator = new PredicateGenerator(criteriaBuilder, conversionService);
            var predicate = predicateGenerator.generate(cq, root, criteriaList, context, entity);
            if (predicate != null) {
                cq.where(predicate);
            }
        }
    }
}
