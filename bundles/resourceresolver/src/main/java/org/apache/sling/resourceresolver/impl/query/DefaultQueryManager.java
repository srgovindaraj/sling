/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.resourceresolver.impl.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.query.Query;
import org.apache.sling.api.resource.query.Query.QueryType;
import org.apache.sling.api.resource.query.QueryBuilder;
import org.apache.sling.api.resource.query.QueryInstructions;
import org.apache.sling.api.resource.query.QueryInstructionsBuilder;
import org.apache.sling.api.resource.query.QueryManager;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;

public class DefaultQueryManager implements QueryManager {

    @Override
    public Iterator<Resource> find(final ResourceResolver resolver, final Query q, final QueryInstructions qi) {
        if ( !(resolver instanceof ResourceResolverImpl) ) {
            throw new IllegalArgumentException("Resource resolver is not provided by this bundle.");
        }
        return Collections.EMPTY_LIST.iterator();
    }

    @Override
    public QueryBuilder query() {
        return new BasicQueryBuilder();
    }

    @Override
    public Query andQuery(final Query q1, final Query... q2) {
        if ( q2 == null || q2.length == 0 ) {
            return q1;
        }
        final List<Query> list = new ArrayList<Query>();
        list.add(q1);
        for(final Query q : q2) {
            list.add(q);
        }
        return new BasicQuery(QueryType.COMPOUND_AND, list);
    }

    @Override
    public Query orQuery(final Query q1, final Query... q2) {
        if ( q2 == null || q2.length == 0 ) {
            return q1;
        }
        final List<Query> list = new ArrayList<Query>();
        list.add(q1);
        for(final Query q : q2) {
            list.add(q);
        }
        return new BasicQuery(QueryType.COMPOUND_OR, list);
    }

    @Override
    public QueryInstructionsBuilder instructions() {
        return new BasicQueryInstructionsBuilder();
    }
}