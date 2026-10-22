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

import java.util.Locale;
import java.util.Map;

import jakarta.persistence.criteria.Nulls;

import org.grails.datastore.mapping.query.Query;

/**
 * An order that also defines where {@code null} values are placed ({@code NULLS FIRST} / {@code NULLS LAST}).
 * A plain {@link Query.Order} leaves that to the database, whose default differs between databases.
 *
 * <pre>
 * Book.createCriteria().list {
 *     createAlias('author', 'a', JoinType.LEFT)
 *     order('a.name', 'asc', nulls: 'last')
 *     order(HibernateOrder.desc('published').nullsFirst())
 * }
 * </pre>
 *
 * @since 8.0
 */
public class HibernateOrder extends Query.Order {

    private static final long serialVersionUID = 1L;

    /**
     * The argument of the criteria {@code order} method that sets the null precedence.
     */
    public static final String NULLS_ARGUMENT = "nulls";

    /**
     * The argument of the criteria {@code order} method that makes the order ignore the case.
     */
    public static final String IGNORE_CASE_ARGUMENT = "ignoreCase";

    private Nulls nullPrecedence = Nulls.NONE;

    public HibernateOrder(String property) {
        super(property);
    }

    public HibernateOrder(String property, Direction direction) {
        super(property, direction);
    }

    public HibernateOrder(String property, Direction direction, Nulls nullPrecedence) {
        super(property, direction);
        nulls(nullPrecedence);
    }

    /**
     * Creates an ascending order of the given property.
     *
     * @param property the property, which may be an alias path such as {@code a.name}
     * @return the order
     */
    public static HibernateOrder asc(String property) {
        return new HibernateOrder(property, Direction.ASC);
    }

    /**
     * Creates a descending order of the given property.
     *
     * @param property the property, which may be an alias path such as {@code a.name}
     * @return the order
     */
    public static HibernateOrder desc(String property) {
        return new HibernateOrder(property, Direction.DESC);
    }

    /**
     * Creates an order from the arguments of the criteria {@code order} method:
     * {@code nulls} ({@code 'first'}, {@code 'last'}, {@code 'none'} or a {@link Nulls}) and {@code ignoreCase}.
     *
     * @param arguments the named arguments
     * @param property the property to order by
     * @param direction {@code "asc"} or {@code "desc"}, case-insensitive; {@code null} for ascending
     * @return the order
     * @throws IllegalArgumentException if the direction or the null precedence is not valid
     */
    public static HibernateOrder of(Map<?, ?> arguments, String property, String direction) {
        HibernateOrder order = new HibernateOrder(property, toDirection(direction), toNulls(arguments.get(NULLS_ARGUMENT)));
        Object ignoreCase = arguments.get(IGNORE_CASE_ARGUMENT);
        if (Boolean.TRUE.equals(ignoreCase) || (ignoreCase != null && Boolean.parseBoolean(ignoreCase.toString()))) {
            order.ignoreCase();
        }
        return order;
    }

    /**
     * Places the {@code null} values first.
     *
     * @return this order
     */
    public HibernateOrder nullsFirst() {
        return nulls(Nulls.FIRST);
    }

    /**
     * Places the {@code null} values last.
     *
     * @return this order
     */
    public HibernateOrder nullsLast() {
        return nulls(Nulls.LAST);
    }

    /**
     * Sets where the {@code null} values are placed.
     *
     * @param nullPrecedence the null precedence; {@code null} or {@link Nulls#NONE} leaves it to the database
     * @return this order
     */
    public HibernateOrder nulls(Nulls nullPrecedence) {
        this.nullPrecedence = nullPrecedence != null ? nullPrecedence : Nulls.NONE;
        return this;
    }

    /**
     * @return where the {@code null} values are placed; {@link Nulls#NONE} if left to the database
     */
    public Nulls getNullPrecedence() {
        return nullPrecedence;
    }

    @Override
    public HibernateOrder ignoreCase() {
        super.ignoreCase();
        return this;
    }

    private static Direction toDirection(String direction) {
        if (direction == null) {
            return Direction.ASC;
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if (Direction.ASC.name().equals(normalized) || Direction.DESC.name().equals(normalized)) {
            return Direction.valueOf(normalized);
        }
        throw new IllegalArgumentException("Invalid order direction [" + direction + "], expected 'asc' or 'desc'");
    }

    private static Nulls toNulls(Object nulls) {
        if (nulls == null || nulls instanceof Nulls) {
            return (Nulls) nulls;
        }
        String normalized = nulls.toString().trim().toUpperCase(Locale.ROOT);
        for (Nulls candidate : Nulls.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Invalid null precedence [" + nulls + "], expected 'first', 'last' or 'none'");
    }
}
